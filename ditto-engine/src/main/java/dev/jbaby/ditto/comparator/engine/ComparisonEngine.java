package dev.jbaby.ditto.comparator.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bson.BsonDocument;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import dev.jbaby.ditto.comparator.api.ComparisonCompletedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonFailedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.ProgressListener;
import dev.jbaby.ditto.comparator.api.ThresholdSource;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.history.ThresholdAdvisor;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.report.ReportStore;
import dev.jbaby.ditto.comparator.scan.DocumentSource;
import dev.jbaby.ditto.comparator.scan.LookupJoinComparator;
import dev.jbaby.ditto.comparator.scan.MatchedOnlyComparator;
import dev.jbaby.ditto.comparator.scan.MergeJoinComparator;
import dev.jbaby.ditto.comparator.scan.PreflightResult;
import dev.jbaby.ditto.comparator.scan.ProgressReporter;
import dev.jbaby.ditto.comparator.scan.SampleComparator;
import dev.jbaby.ditto.comparator.structure.MapDetector;

/**
 * Runs comparisons in one {@link DocumentStore}: merges the request with the configuration, picks thresholds (from
 * the request, the history or the configuration), checks and opens both sides, resolves AUTO mode, detects maps,
 * scans, assembles and stores the report and publishes an event. Thread-safe.
 */
public final class ComparisonEngine {

    private static final Logger log = LoggerFactory.getLogger(ComparisonEngine.class);

    private final DocumentStore store;
    private final ComparatorProperties properties;
    private final Hasher hasher;
    private final ReportAssembler assembler;
    private final @Nullable ReportStore reports;
    private final @Nullable ThresholdAdvisor thresholdAdvisor;
    private final ApplicationEventPublisher events;
    private final MergeJoinComparator mergeJoin = new MergeJoinComparator();
    private final LookupJoinComparator lookupJoin = new LookupJoinComparator();
    private final SampleComparator sampler = new SampleComparator();
    private final MatchedOnlyComparator matchedOnly = new MatchedOnlyComparator();

    /**
     * @param reports          where reports are stored, {@code null} to not store them
     * @param thresholdAdvisor derives thresholds from history, {@code null} to always use the configured ones
     * @param events           receives a completed or failed event per comparison
     */
    public ComparisonEngine(DocumentStore store, ComparatorProperties properties, Hasher hasher,
                            ReportAssembler assembler, @Nullable ReportStore reports,
                            @Nullable ThresholdAdvisor thresholdAdvisor, ApplicationEventPublisher events) {
        this.store = store;
        this.properties = properties;
        this.hasher = hasher;
        this.assembler = assembler;
        this.reports = reports;
        this.thresholdAdvisor = thresholdAdvisor;
        this.events = events;
    }

    /** A request builder for {@code name + backup-suffix} vs. {@code name}. */
    public ComparisonRequest.Builder backupRequest(String name) {
        return ComparisonRequest.builder(name + properties.getBackupSuffix(), name);
    }

    public ComparisonReport compare(ComparisonRequest request, ProgressListener listener) {
        ComparisonSettings settings = properties.settingsFor(request, store.kind(), store.defaultDatabase());
        Map<String, String> labels = properties.labelsFor(request);
        try {
            ComparisonReport report = run(settings, labels, request.thresholds() != null, listener);
            events.publishEvent(new ComparisonCompletedEvent(report));
            return report;
        } catch (RuntimeException e) {
            RuntimeException thrown = store.translate(e, settings);
            events.publishEvent(new ComparisonFailedEvent(settings, thrown, labels));
            throw thrown;
        }
    }

    private ComparisonReport run(ComparisonSettings requested, Map<String, String> labels,
                                 boolean thresholdsFromRequest, ProgressListener listener) {
        ComparisonSettings settings = requested;
        ThresholdSource thresholdSource;
        if (thresholdsFromRequest) {
            thresholdSource = ThresholdSource.request();
        } else if (thresholdAdvisor != null) {
            ThresholdAdvisor.Advice advice = thresholdAdvisor.advise(requested);
            settings = requested.withThresholds(advice.thresholds());
            thresholdSource = advice.source();
        } else {
            thresholdSource = ThresholdSource.configured();
        }
        String label = label(settings);
        Instant startedAt = Instant.now();
        List<String> decisions = new ArrayList<>();

        DocumentStore.Sides sides = store.open(settings);
        DocumentSource baseline = sides.baseline();
        DocumentSource candidate = sides.candidate();
        PreflightResult checked = sides.preflight();
        checked.warnings().forEach(warning -> log.warn("{}: {}", label, warning));
        settings = settings.withMode(resolveMode(settings, checked, decisions));
        settings = detectMaps(settings, baseline, candidate, decisions);
        log.info("Comparing {}, mode {}, thresholds {}{}", label, describe(settings.mode()),
                thresholdSource.kind() == ThresholdSource.Kind.HISTORY
                        ? "from " + thresholdSource.historyRuns() + " previous runs" : thresholdSource.kind(),
                labels.isEmpty() ? "" : ", labels " + labels);
        ScanAccumulator accumulator = new ScanAccumulator(settings, hasher);
        ScanResult scan = settings.matchedOnly()
                ? compareMatchedOnly(baseline, candidate, settings, checked, accumulator, label, listener, decisions)
                : switch (settings.mode()) {
                    case ComparisonMode.Full full -> compareFull(baseline, candidate, settings, accumulator,
                            new ProgressReporter(label, settings.tuning().progressInterval(),
                                    checked.baselineCount() + checked.candidateCount(), listener), decisions);
                    case ComparisonMode.Sample sample -> sampler.compare(baseline, candidate, settings, sample.size(),
                            accumulator, new ProgressReporter(label, settings.tuning().progressInterval(),
                                    2L * sample.size(), listener));
                    case ComparisonMode.Auto auto -> throw new IllegalStateException("AUTO mode was not resolved");
                };
        ComparisonReport report = assembler.assemble(settings, labels, thresholdSource, decisions, checked, scan,
                startedAt, Instant.now());
        log.info("{}: verdict {} (keySimilarity {}, unchangedRate {}, {} ms)", label, report.verdict(),
                report.keys().keySimilarity().value(), report.content().unchangedRate().value(),
                report.run().durationMillis());
        report.hints().forEach(hint -> log.info("{}: hint: {}{}", label, hint.message(),
                hint.property() == null ? "" : " [" + hint.property() + "]"));
        store(report, label);
        return report;
    }

    /** One merge-join pass if both sides are read in key order, else a lookup join. */
    private ScanResult compareFull(DocumentSource baseline, DocumentSource candidate, ComparisonSettings settings,
                                   ScanAccumulator accumulator, ProgressReporter progress, List<String> decisions) {
        if (baseline.keyOrdered() && candidate.keyOrdered()) {
            return mergeJoin.compare(baseline, candidate, settings, accumulator, progress);
        }
        decisions.add("Full comparison by key lookups: read the baseline and looked up its keys in the candidate,"
                + " then read the candidate's keys and looked them up in the baseline (the store cannot return"
                + " documents sorted by key)");
        return lookupJoin.compare(baseline, candidate, settings, accumulator, progress);
    }

    /** Reads the smaller side (fully or sampled) and looks its keys up in the other. */
    private ScanResult compareMatchedOnly(DocumentSource baseline, DocumentSource candidate,
                                          ComparisonSettings settings, PreflightResult checked,
                                          ScanAccumulator accumulator, String label, ProgressListener listener,
                                          List<String> decisions) {
        boolean readBaseline = MatchedOnlyComparator.readsBaseline(checked.baselineCount(), checked.candidateCount());
        DocumentSource read = readBaseline ? baseline : candidate;
        long readCount = readBaseline ? checked.baselineCount() : checked.candidateCount();
        Integer sampleSize = settings.mode() instanceof ComparisonMode.Sample(int size) ? size : null;
        decisions.add(String.format(Locale.ROOT, "Matched-only: compared only documents whose key exists on both"
                + " sides, by reading %s the %s (%,d documents) and looking up its keys in the %s; documents on one"
                + " side only are counted but not compared, and keySimilarity is not judged",
                sampleSize == null ? "all of" : String.format(Locale.ROOT, "a sample of %,d keys of", sampleSize),
                read.side(), readCount, readBaseline ? candidate.side() : baseline.side()));
        long expected = 2L * (sampleSize == null ? readCount : Math.min(sampleSize, readCount));
        return matchedOnly.compare(baseline, candidate, settings, readBaseline, sampleSize, accumulator,
                new ProgressReporter(label, settings.tuning().progressInterval(), expected, listener));
    }

    /** Adds maps with dynamic keys found in a small sample of both sides to the wildcard paths. */
    private ComparisonSettings detectMaps(ComparisonSettings settings, DocumentSource baseline,
                                          DocumentSource candidate, List<String> decisions) {
        ComparatorProperties.MapDetection detection = properties.getMapDetection();
        if (!detection.isEnabled()) {
            return settings;
        }
        List<BsonDocument> sample = new ArrayList<>(baseline.randomDocuments(detection.getSampleSize()));
        sample.addAll(candidate.randomDocuments(detection.getSampleSize()));
        List<MapDetector.DetectedMap> maps = new MapDetector(detection.getMinDistinctKeys())
                .detect(sample, settings.ignoredPaths(), settings.orderSensitivePaths(), settings.wildcardPaths());
        if (maps.isEmpty()) {
            return settings;
        }
        List<String> wildcards = new ArrayList<>(settings.wildcardPaths());
        for (MapDetector.DetectedMap map : maps) {
            wildcards.add(map.pattern());
            decisions.add(String.format(Locale.ROOT, "Treated %s as a map with dynamic keys (%,d distinct field names,"
                    + " %.1f per object, in %,d sampled documents); configure wildcard-paths to make it explicit or"
                    + " set ditto.map-detection.enabled=false", map.pattern(), map.distinctKeys(),
                    map.averageKeys(), map.documents()));
        }
        return settings.withWildcardPaths(wildcards);
    }

    /**
     * AUTO: FULL up to the full-scan limit per side, SAMPLE above. Matched-only reads just the smaller side, so only
     * its size counts. Explicit modes are kept.
     */
    private static ComparisonMode resolveMode(ComparisonSettings settings, PreflightResult checked,
                                              List<String> decisions) {
        if (!(settings.mode() instanceof ComparisonMode.Auto)) {
            return settings.mode();
        }
        ComparisonSettings.Tuning tuning = settings.tuning();
        if (settings.matchedOnly()) {
            long smaller = Math.min(checked.baselineCount(), checked.candidateCount());
            boolean full = smaller <= tuning.fullScanLimit();
            decisions.add(String.format(Locale.ROOT, "Mode AUTO chose %s: matched-only reads the smaller side,"
                    + " %,d documents, full-scan limit %,d", full ? "FULL" : String.format(Locale.ROOT,
                    "SAMPLE of %,d keys", tuning.autoSampleSize()), smaller, tuning.fullScanLimit()));
            return full ? ComparisonMode.full() : ComparisonMode.sample(tuning.autoSampleSize());
        }
        long largest = Math.max(checked.baselineCount(), checked.candidateCount());
        if (largest <= tuning.fullScanLimit()) {
            decisions.add(String.format(Locale.ROOT, "Mode AUTO chose FULL: %,d and %,d documents, full-scan limit"
                    + " %,d", checked.baselineCount(), checked.candidateCount(), tuning.fullScanLimit()));
            return ComparisonMode.full();
        }
        decisions.add(String.format(Locale.ROOT, "Mode AUTO chose SAMPLE of %,d keys per side: %,d documents exceed"
                + " the full-scan limit %,d (ditto.full-scan-limit)", tuning.autoSampleSize(), largest,
                tuning.fullScanLimit()));
        return ComparisonMode.sample(tuning.autoSampleSize());
    }

    private void store(ComparisonReport report, String label) {
        if (reports == null) {
            return;
        }
        try {
            reports.save(report);
        } catch (RuntimeException e) {
            // the report itself is valid; a failed write must not hide the verdict from the caller
            log.error("{}: storing report {} failed", label, report.id(), e);
        }
    }

    /** {@code baseline -> candidate}, for messages. */
    public static String label(ComparisonSettings settings) {
        return settings.baseline() + " -> " + settings.candidate();
    }

    private static String describe(ComparisonMode mode) {
        return switch (mode) {
            case ComparisonMode.Full full -> "FULL";
            case ComparisonMode.Sample(int size) -> "SAMPLE (" + size + " keys per side)";
            case ComparisonMode.Auto auto -> "AUTO";
        };
    }
}
