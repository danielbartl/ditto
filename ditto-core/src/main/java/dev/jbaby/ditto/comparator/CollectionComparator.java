package dev.jbaby.ditto.comparator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bson.BsonDocument;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;

import dev.jbaby.ditto.comparator.api.ComparisonCompletedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonException;
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
import dev.jbaby.ditto.comparator.report.ReportRepository;
import dev.jbaby.ditto.comparator.scan.CollectionHandle;
import dev.jbaby.ditto.comparator.scan.MergeJoinComparator;
import dev.jbaby.ditto.comparator.scan.Preflight;
import dev.jbaby.ditto.comparator.scan.ProgressReporter;
import dev.jbaby.ditto.comparator.scan.SampleComparator;
import dev.jbaby.ditto.comparator.structure.MapDetector;

/**
 * Compares two MongoDB collections and judges how similar they are. Without any configuration:
 * <pre>{@code
 * ComparisonReport report = comparator.compareWithBackup("products");   // products_backup -> products
 * if (report.verdict() == Level.RED) { ... }
 * report.hints().forEach(hint -> log.info(hint.message()));            // what to configure next
 * }</pre>
 * With options:
 * <pre>{@code
 * comparator.compare(comparator.backupRequest("products").ignoredPaths("meta.syncedAt").build());
 * }</pre>
 * Blocking; runs on the calling thread and honours thread interruption. Thread-safe: concurrent comparisons are
 * independent. Publishes a {@link ComparisonCompletedEvent} or {@link ComparisonFailedEvent} for every comparison.
 */
public class CollectionComparator {

    private static final Logger log = LoggerFactory.getLogger(CollectionComparator.class);

    private final MongoDatabaseFactory databaseFactory;
    private final ComparatorProperties properties;
    private final Hasher hasher;
    private final Preflight preflight;
    private final MergeJoinComparator mergeJoin;
    private final SampleComparator sampler;
    private final ReportAssembler assembler;
    private final @Nullable ReportRepository repository;
    private final @Nullable ThresholdAdvisor thresholdAdvisor;
    private final ApplicationEventPublisher events;

    /**
     * Created by the auto-configuration; inject the bean instead of calling this. The constructor takes internal types
     * and may change in any release.
     *
     * @param repository       where reports are stored, {@code null} to not store them
     * @param thresholdAdvisor derives thresholds from history, {@code null} to always use the configured ones
     * @param events           receives a completed or failed event per comparison
     */
    public CollectionComparator(MongoDatabaseFactory databaseFactory, ComparatorProperties properties, Hasher hasher,
                                Preflight preflight, MergeJoinComparator mergeJoin, SampleComparator sampler,
                                ReportAssembler assembler, @Nullable ReportRepository repository,
                                @Nullable ThresholdAdvisor thresholdAdvisor, ApplicationEventPublisher events) {
        this.databaseFactory = databaseFactory;
        this.properties = properties;
        this.hasher = hasher;
        this.preflight = preflight;
        this.mergeJoin = mergeJoin;
        this.sampler = sampler;
        this.assembler = assembler;
        this.repository = repository;
        this.thresholdAdvisor = thresholdAdvisor;
        this.events = events;
    }

    /**
     * Compares {@code collection + backup-suffix} (default {@code _backup}) as baseline with {@code collection} as
     * candidate, in the default database, with all defaults.
     */
    public ComparisonReport compareWithBackup(String collection) {
        return compare(backupRequest(collection).build());
    }

    /** Compares two collections of the default database with all defaults. */
    public ComparisonReport compare(String baselineCollection, String candidateCollection) {
        return compare(ComparisonRequest.of(baselineCollection, candidateCollection));
    }

    /** A request builder for {@code collection + backup-suffix} vs. {@code collection}, to add options to. */
    public ComparisonRequest.Builder backupRequest(String collection) {
        return ComparisonRequest.builder(collection + properties.getBackupSuffix(), collection);
    }

    public ComparisonReport compare(ComparisonRequest request) {
        return compare(request, ProgressListener.NONE);
    }

    /**
     * @throws ComparisonException  if the comparison cannot be carried out (missing collection, unusable keys,
     *                              interrupted)
     * @throws DataAccessException  on database errors
     */
    public ComparisonReport compare(ComparisonRequest request, ProgressListener listener) {
        ComparisonSettings settings = properties.settingsFor(request,
                databaseFactory.getMongoDatabase().getName());
        try {
            ComparisonReport report = run(settings, request.thresholds() != null, listener);
            events.publishEvent(new ComparisonCompletedEvent(report));
            return report;
        } catch (RuntimeException e) {
            RuntimeException thrown = translate(e, settings);
            events.publishEvent(new ComparisonFailedEvent(settings, thrown));
            throw thrown;
        }
    }

    private RuntimeException translate(RuntimeException e, ComparisonSettings settings) {
        return switch (e) {
            case MongoInterruptedException interrupted -> {
                Thread.currentThread().interrupt();
                yield new ComparisonException("Comparison of " + label(settings) + " interrupted", interrupted);
            }
            case MongoException mongo -> {
                DataAccessException translated =
                        databaseFactory.getExceptionTranslator().translateExceptionIfPossible(mongo);
                yield translated != null ? translated : mongo;
            }
            default -> e;
        };
    }

    private ComparisonReport run(ComparisonSettings requested, boolean thresholdsFromRequest,
                                 ProgressListener listener) {
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
        CollectionHandle baseline = CollectionHandle.of("baseline", settings.baseline(),
                databaseFactory.getMongoDatabase(settings.baseline().database()));
        CollectionHandle candidate = CollectionHandle.of("candidate", settings.candidate(),
                databaseFactory.getMongoDatabase(settings.candidate().database()));

        Preflight.Result checked = preflight.run(baseline, candidate, settings);
        checked.warnings().forEach(warning -> log.warn("{}: {}", label, warning));
        settings = settings.withMode(resolveMode(settings, checked, decisions));
        settings = detectMaps(settings, baseline, candidate, decisions);
        log.info("Comparing {}, mode {}, thresholds {}", label, describe(settings.mode()),
                thresholdSource.kind() == ThresholdSource.Kind.HISTORY
                        ? "from " + thresholdSource.historyRuns() + " previous runs" : thresholdSource.kind());
        ScanAccumulator accumulator = new ScanAccumulator(settings, hasher);
        ScanResult scan = switch (settings.mode()) {
            case ComparisonMode.Full full -> mergeJoin.compare(baseline, candidate, settings, accumulator,
                    new ProgressReporter(label, settings.tuning().progressInterval(),
                            checked.baselineCount() + checked.candidateCount(), listener));
            case ComparisonMode.Sample sample -> sampler.compare(baseline, candidate, settings, sample.size(),
                    accumulator, new ProgressReporter(label, settings.tuning().progressInterval(),
                            2L * sample.size(), listener));
            case ComparisonMode.Auto auto -> throw new IllegalStateException("AUTO mode was not resolved");
        };
        ComparisonReport report = assembler.assemble(settings, thresholdSource, decisions, checked, scan, startedAt,
                Instant.now());
        log.info("{}: verdict {} (keySimilarity {}, unchangedRate {}, {} ms)", label, report.verdict(),
                report.keys().keySimilarity().value(), report.content().unchangedRate().value(),
                report.run().durationMillis());
        report.hints().forEach(hint -> log.info("{}: hint: {}{}", label, hint.message(),
                hint.property() == null ? "" : " [" + hint.property() + "]"));
        store(report, label);
        return report;
    }

    /** Adds maps with dynamic keys found in a small sample of both sides to the wildcard paths. */
    private ComparisonSettings detectMaps(ComparisonSettings settings, CollectionHandle baseline,
                                          CollectionHandle candidate, List<String> decisions) {
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
                    + " set comparator.map-detection.enabled=false", map.pattern(), map.distinctKeys(),
                    map.averageKeys(), map.documents()));
        }
        return settings.withWildcardPaths(wildcards);
    }

    /** AUTO: FULL up to the full-scan limit per side, SAMPLE above. Explicit modes are kept. */
    private static ComparisonMode resolveMode(ComparisonSettings settings, Preflight.Result checked,
                                              List<String> decisions) {
        if (!(settings.mode() instanceof ComparisonMode.Auto)) {
            return settings.mode();
        }
        ComparisonSettings.Tuning tuning = settings.tuning();
        long largest = Math.max(checked.baselineCount(), checked.candidateCount());
        if (largest <= tuning.fullScanLimit()) {
            decisions.add(String.format(Locale.ROOT, "Mode AUTO chose FULL: %,d and %,d documents, full-scan limit"
                    + " %,d", checked.baselineCount(), checked.candidateCount(), tuning.fullScanLimit()));
            return ComparisonMode.full();
        }
        decisions.add(String.format(Locale.ROOT, "Mode AUTO chose SAMPLE of %,d keys per side: %,d documents exceed"
                + " the full-scan limit %,d (comparator.full-scan-limit)", tuning.autoSampleSize(), largest,
                tuning.fullScanLimit()));
        return ComparisonMode.sample(tuning.autoSampleSize());
    }

    private void store(ComparisonReport report, String label) {
        if (repository == null) {
            return;
        }
        try {
            repository.save(report);
        } catch (RuntimeException e) {
            // the report itself is valid; a failed write must not hide the verdict from the caller
            log.error("{}: storing report {} failed", label, report.id(), e);
        }
    }

    private static String label(ComparisonSettings settings) {
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
