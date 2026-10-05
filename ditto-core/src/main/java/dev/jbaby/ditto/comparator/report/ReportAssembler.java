package dev.jbaby.ditto.comparator.report;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonReport.ContentMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.Examples;
import dev.jbaby.ditto.comparator.api.ComparisonReport.KeyMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RunMetadata;
import dev.jbaby.ditto.comparator.api.ComparisonReport.StructureMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.Rate;
import dev.jbaby.ditto.comparator.api.ThresholdSource;
import dev.jbaby.ditto.comparator.metrics.PathChangeStats.PathChangeCount;
import dev.jbaby.ditto.comparator.metrics.ScanResult;
import dev.jbaby.ditto.comparator.metrics.Wilson;
import dev.jbaby.ditto.comparator.path.PathMatcher;
import dev.jbaby.ditto.comparator.scan.MatchedOnlyComparator;
import dev.jbaby.ditto.comparator.scan.Preflight;
import dev.jbaby.ditto.comparator.structure.StructureDiff;
import dev.jbaby.ditto.comparator.verdict.RuleInput;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;

/**
 * Turns the raw counts of a scan into the {@link ComparisonReport}: rates (exact in FULL mode, Wilson estimates
 * extrapolated to the collection sizes in SAMPLE mode), structure findings, the verdict and warnings.
 */
public final class ReportAssembler {

    private final VerdictEvaluator verdictEvaluator;

    public ReportAssembler(VerdictEvaluator verdictEvaluator) {
        this.verdictEvaluator = verdictEvaluator;
    }

    public ComparisonReport assemble(ComparisonSettings settings, Map<String, String> labels,
                                     ThresholdSource thresholdSource, List<String> decisions,
                                     Preflight.Result preflight, ScanResult scan, Instant startedAt,
                                     Instant finishedAt) {
        boolean full = switch (settings.mode()) {
            case ComparisonMode.Full fullScan -> true;
            case ComparisonMode.Sample sample -> false;
            case ComparisonMode.Auto auto -> throw new IllegalArgumentException("AUTO mode must be resolved first");
        };
        int top = settings.tuning().topChangedPaths();
        Measured measured = settings.matchedOnly() ? matchedOnly(scan, preflight, full, top, expected(settings))
                : full ? exact(scan, top, expected(settings))
                : estimated(scan, preflight, top, expected(settings));
        StructureMetrics structure = StructureDiff.compare(scan.baselineProfile(), scan.candidateProfile(),
                settings.thresholds().structure());
        var input = new RuleInput(measured.keys(), measured.content(), measured.changedPaths(), structure,
                settings.thresholds(), settings.verdictBasis(), settings.matchedOnly());
        var verdict = verdictEvaluator.evaluate(input);
        var hints = new HintAdvisor(verdictEvaluator).advise(settings, input, verdict.rules(), scan.baselineProfile(),
                scan.candidateProfile());

        // documents per side: counted in a full scan (matched-only: derived from the counts), else from metadata
        KeyMetrics keys = measured.keys();
        long baselineDocs = !full ? preflight.baselineCount()
                : settings.matchedOnly() ? keys.matched() + keys.removed() : scan.baselineDocsRead();
        long candidateDocs = !full ? preflight.candidateCount()
                : settings.matchedOnly() ? keys.matched() + keys.added() : scan.candidateDocsRead();
        var run = new RunMetadata(startedAt, finishedAt, Duration.between(startedAt, finishedAt).toMillis(),
                baselineDocs, candidateDocs, scan.baselineDocsRead(), scan.candidateDocsRead(), settings,
                thresholdSource, decisions);
        return new ComparisonReport(ComparisonReport.SCHEMA_VERSION, UUID.randomUUID().toString(), verdict.overall(),
                verdict.rules(), measured.keys(), measured.content(), measured.changedPaths(), structure,
                new Examples(scan.changedExamples(), scan.addedExamples(), scan.removedExamples()), run,
                warnings(preflight, scan, structure, settings), hints, labels);
    }

    private record Measured(KeyMetrics keys, ContentMetrics content, List<PathChange> changedPaths) {
    }

    private static PathMatcher expected(ComparisonSettings settings) {
        return PathMatcher.of(settings.expectedChangePaths());
    }

    private static Measured exact(ScanResult scan, int topChangedPaths, PathMatcher expected) {
        long matched = scan.matched();
        var keys = new KeyMetrics(matched, scan.added(), scan.removed(),
                Rate.exact(matched, matched + scan.added() + scan.removed()),
                Rate.exact(scan.added(), scan.candidateDocsRead()),
                Rate.exact(scan.removed(), scan.baselineDocsRead()));
        return new Measured(keys, exactContent(scan), exactPaths(scan, topChangedPaths, expected));
    }

    private static ContentMetrics exactContent(ScanResult scan) {
        long matched = scan.matched();
        return new ContentMetrics(scan.unchanged(), scan.changed(), scan.changedExpectedOnly(),
                Rate.exact(scan.unchanged(), matched), Rate.exact(scan.changed(), matched),
                Rate.exact(scan.unchanged() + scan.changedExpectedOnly(), matched));
    }

    private static List<PathChange> exactPaths(ScanResult scan, int topChangedPaths, PathMatcher expected) {
        return scan.pathChanges().stream().limit(topChangedPaths)
                .map(change -> new PathChange(change.path(), change.documents(),
                        Rate.exact(change.documents(), scan.matched()), expected.matches(change.path()),
                        change.examples(), change.valueExamples()))
                .toList();
    }

    /**
     * SAMPLE mode. The baseline sample estimates the removed rate {@code r} and, among matched documents, the
     * content rates; the candidate sample estimates the added rate {@code a}. With collection sizes {@code Nb} and
     * {@code Nc}: matched = Nb(1-r), removed = Nb·r, added = Nc·a and
     * keySimilarity = Nb(1-r) / (Nb + Nc·a). keySimilarity falls with both r and a, so its interval combines the
     * worse bounds of both (lower bound from upper r and upper a), which is conservative.
     */
    private static Measured estimated(ScanResult scan, Preflight.Result preflight, int topChangedPaths,
                                      PathMatcher expected) {
        long baselineSampled = scan.matched() + scan.removed();
        long nb = preflight.baselineCount();
        long nc = preflight.candidateCount();
        Wilson.Interval removed = Wilson.interval(scan.removed(), baselineSampled);
        Wilson.Interval added = Wilson.interval(scan.added(), scan.candidateSampled());
        Wilson.Interval none = new Wilson.Interval(0, 0, 0);
        Wilson.Interval r = removed != null ? removed : none;
        Wilson.Interval a = added != null ? added : none;

        Double point = keySimilarity(nb, nc, r.point(), a.point());
        Rate keySimilarity = point == null
                ? new Rate.Estimate(null, null, null, baselineSampled + scan.candidateSampled())
                : new Rate.Estimate(point, keySimilarity(nb, nc, r.upper(), a.upper()),
                keySimilarity(nb, nc, r.lower(), a.lower()), baselineSampled + scan.candidateSampled());
        double matchedEstimate = nb * (1 - r.point());
        var keys = new KeyMetrics(Math.round(matchedEstimate), Math.round(nc * a.point()), Math.round(nb * r.point()),
                keySimilarity, Wilson.estimate(scan.added(), scan.candidateSampled()),
                Wilson.estimate(scan.removed(), baselineSampled));
        return new Measured(keys, estimatedContent(scan, matchedEstimate),
                estimatedPaths(scan, matchedEstimate, topChangedPaths, expected));
    }

    /** Content rates estimated from the matched sample, counts extrapolated to {@code matchedEstimate}. */
    private static ContentMetrics estimatedContent(ScanResult scan, double matchedEstimate) {
        Rate.Estimate unchangedRate = Wilson.estimate(scan.unchanged(), scan.matched());
        Rate.Estimate unchangedOrExpectedRate = Wilson.estimate(scan.unchanged() + scan.changedExpectedOnly(),
                scan.matched());
        long unchanged = unchangedRate.value() == null ? 0 : Math.round(matchedEstimate * unchangedRate.value());
        long expectedOnly = unchangedOrExpectedRate.value() == null ? 0
                : Math.round(matchedEstimate * unchangedOrExpectedRate.value()) - unchanged;
        return new ContentMetrics(unchanged, Math.round(matchedEstimate) - unchanged, expectedOnly,
                unchangedRate, Wilson.estimate(scan.changed(), scan.matched()), unchangedOrExpectedRate);
    }

    private static List<PathChange> estimatedPaths(ScanResult scan, double matchedEstimate, int topChangedPaths,
                                                   PathMatcher expected) {
        List<PathChange> paths = new ArrayList<>();
        for (PathChangeCount change : scan.pathChanges().stream().limit(topChangedPaths).toList()) {
            Rate.Estimate rate = Wilson.estimate(change.documents(), scan.matched());
            paths.add(new PathChange(change.path(), Math.round(matchedEstimate * rate.value()), rate,
                    expected.matches(change.path()), change.examples(), change.valueExamples()));
        }
        return paths;
    }

    /**
     * Matched-only: the smaller side was read (fully or sampled) and its keys looked up in the other. Content and
     * path rates refer to the matched documents as usual. The key metrics only describe how much of each side lies
     * outside the comparison; the size of the side that was not read comes from the collection metadata. With a
     * sample, the share {@code p} of read keys found on the other side estimates matched = N·p, where N is the read
     * side's size; every key rate is monotonic in matched, so its interval follows from the one of {@code p}.
     */
    private static Measured matchedOnly(ScanResult scan, Preflight.Result preflight, boolean full,
                                        int topChangedPaths, PathMatcher expected) {
        boolean readBaseline = MatchedOnlyComparator.readsBaseline(preflight.baselineCount(),
                preflight.candidateCount());
        long matched = scan.matched();
        if (full) {
            long nb = readBaseline ? scan.baselineDocsRead() : Math.max(preflight.baselineCount(), matched);
            long nc = readBaseline ? Math.max(preflight.candidateCount(), matched) : scan.candidateDocsRead();
            var keys = new KeyMetrics(matched, nc - matched, nb - matched, Rate.exact(matched, nb + nc - matched),
                    Rate.exact(nc - matched, nc), Rate.exact(nb - matched, nb));
            return new Measured(keys, exactContent(scan), exactPaths(scan, topChangedPaths, expected));
        }
        long nb = preflight.baselineCount();
        long nc = preflight.candidateCount();
        long read = readBaseline ? nb : nc;
        long sampled = matched + (readBaseline ? scan.removed() : scan.added());
        Wilson.Interval found = Wilson.interval(matched, sampled);
        double matchedEstimate = found == null ? 0 : read * found.point();
        KeyMetrics keys;
        if (found == null) {
            Rate.Estimate undefined = new Rate.Estimate(null, null, null, 0);
            keys = new KeyMetrics(0, nc, nb, undefined, undefined, undefined);
        } else {
            double low = read * found.lower();
            double high = read * found.upper();
            long rounded = Math.min(Math.round(matchedEstimate), Math.min(nb, nc));
            keys = new KeyMetrics(rounded, nc - rounded, nb - rounded,
                    new Rate.Estimate(share(matchedEstimate, nb + nc - matchedEstimate), share(low, nb + nc - low),
                            share(high, nb + nc - high), sampled),
                    new Rate.Estimate(share(nc - matchedEstimate, nc), share(nc - high, nc), share(nc - low, nc),
                            sampled),
                    new Rate.Estimate(share(nb - matchedEstimate, nb), share(nb - high, nb), share(nb - low, nb),
                            sampled));
        }
        return new Measured(keys, estimatedContent(scan, matchedEstimate),
                estimatedPaths(scan, matchedEstimate, topChangedPaths, expected));
    }

    /** {@code part / whole} in [0, 1], 0 for an empty whole. */
    private static double share(double part, double whole) {
        return whole <= 0 ? 0.0 : Math.clamp(part / whole, 0.0, 1.0);
    }

    /** Nb(1-r) / (Nb + Nc·a), {@code null} if both collections are empty. */
    private static @Nullable Double keySimilarity(long nb, long nc, double removedRate, double addedRate) {
        double denominator = nb + nc * addedRate;
        if (nb == 0 && nc == 0) {
            return null;
        }
        return denominator == 0 ? 0.0 : Math.clamp(nb * (1 - removedRate) / denominator, 0.0, 1.0);
    }

    private static List<String> warnings(Preflight.Result preflight, ScanResult scan, StructureMetrics structure,
                                         ComparisonSettings settings) {
        List<String> warnings = new ArrayList<>(preflight.warnings());
        if (structure.pathCapReached()) {
            warnings.add("More than " + settings.tuning().maxTrackedPaths() + " distinct paths: "
                    + structure.untrackedPathOccurrences() + " path occurrences were not profiled, so new or"
                    + " vanished paths may be misreported. Consider wildcard paths for maps with dynamic keys.");
        }
        if (scan.untrackedPathChanges() > 0) {
            warnings.add(scan.untrackedPathChanges() + " changed-path occurrences beyond the first "
                    + settings.tuning().maxTrackedPaths() + " distinct paths were not tracked");
        }
        return warnings;
    }
}
