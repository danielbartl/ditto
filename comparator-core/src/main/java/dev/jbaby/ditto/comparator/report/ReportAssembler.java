package dev.jbaby.ditto.comparator.report;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    public ComparisonReport assemble(ComparisonSettings settings, ThresholdSource thresholdSource,
                                     Preflight.Result preflight, ScanResult scan, Instant startedAt,
                                     Instant finishedAt) {
        Measured measured = switch (settings.mode()) {
            case ComparisonMode.Full _ -> exact(scan, settings.tuning().topChangedPaths(), expected(settings));
            case ComparisonMode.Sample _ -> estimated(scan, preflight, settings.tuning().topChangedPaths(),
                    expected(settings));
        };
        StructureMetrics structure = StructureDiff.compare(scan.baselineProfile(), scan.candidateProfile(),
                settings.thresholds().structure());
        var verdict = verdictEvaluator.evaluate(new RuleInput(measured.keys(), measured.content(),
                measured.changedPaths(), structure, settings.thresholds(), settings.verdictBasis()));

        boolean full = settings.mode() instanceof ComparisonMode.Full;
        var run = new RunMetadata(startedAt, finishedAt, Duration.between(startedAt, finishedAt).toMillis(),
                full ? scan.baselineDocsRead() : preflight.baselineCount(),
                full ? scan.candidateDocsRead() : preflight.candidateCount(),
                scan.baselineDocsRead(), scan.candidateDocsRead(), settings, thresholdSource);
        return new ComparisonReport(UUID.randomUUID().toString(), verdict.overall(), verdict.rules(),
                measured.keys(), measured.content(), measured.changedPaths(), structure,
                new Examples(scan.changedExamples(), scan.addedExamples(), scan.removedExamples()), run,
                warnings(preflight, scan, structure, settings));
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
        var content = new ContentMetrics(scan.unchanged(), scan.changed(), scan.changedExpectedOnly(),
                Rate.exact(scan.unchanged(), matched), Rate.exact(scan.changed(), matched),
                Rate.exact(scan.unchanged() + scan.changedExpectedOnly(), matched));
        List<PathChange> paths = scan.pathChanges().stream().limit(topChangedPaths)
                .map(change -> new PathChange(change.path(), change.documents(),
                        Rate.exact(change.documents(), matched), expected.matches(change.path()), change.examples(),
                        change.valueExamples()))
                .toList();
        return new Measured(keys, content, paths);
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

        Rate.Estimate unchangedRate = Wilson.estimate(scan.unchanged(), scan.matched());
        Rate.Estimate unchangedOrExpectedRate = Wilson.estimate(scan.unchanged() + scan.changedExpectedOnly(),
                scan.matched());
        long unchanged = unchangedRate.value() == null ? 0 : Math.round(matchedEstimate * unchangedRate.value());
        long expectedOnly = unchangedOrExpectedRate.value() == null ? 0
                : Math.round(matchedEstimate * unchangedOrExpectedRate.value()) - unchanged;
        var content = new ContentMetrics(unchanged, Math.round(matchedEstimate) - unchanged, expectedOnly,
                unchangedRate, Wilson.estimate(scan.changed(), scan.matched()), unchangedOrExpectedRate);

        List<PathChange> paths = new ArrayList<>();
        for (PathChangeCount change : scan.pathChanges().stream().limit(topChangedPaths).toList()) {
            Rate.Estimate rate = Wilson.estimate(change.documents(), scan.matched());
            paths.add(new PathChange(change.path(), Math.round(matchedEstimate * rate.value()), rate,
                    expected.matches(change.path()), change.examples(), change.valueExamples()));
        }
        return new Measured(keys, content, paths);
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
