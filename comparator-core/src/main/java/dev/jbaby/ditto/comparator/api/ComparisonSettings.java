package dev.jbaby.ditto.comparator.api;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;

/**
 * The effective configuration of one comparison: the request merged with the configured defaults.
 * Collections always carry a database name. Path lists are sorted, so reports are deterministic.
 */
public record ComparisonSettings(
        CollectionRef baseline,
        CollectionRef candidate,
        String keyField,
        List<String> ignoredPaths,
        List<String> orderSensitivePaths,
        List<String> wildcardPaths,
        ComparisonMode mode,
        boolean nullEqualsMissing,
        MixedKeyPolicy mixedKeyPolicy,
        VerdictBasis verdictBasis,
        Thresholds thresholds,
        Tuning tuning) {

    public ComparisonSettings {
        requireNonNull(baseline, "baseline");
        requireNonNull(candidate, "candidate");
        requireNonNull(keyField, "keyField");
        requireNonNull(mode, "mode");
        requireNonNull(mixedKeyPolicy, "mixedKeyPolicy");
        requireNonNull(verdictBasis, "verdictBasis");
        requireNonNull(thresholds, "thresholds");
        requireNonNull(tuning, "tuning");
        if (baseline.database() == null || candidate.database() == null) {
            throw new IllegalArgumentException("database names must be resolved");
        }
        if (baseline.equals(candidate)) {
            throw new IllegalArgumentException("baseline and candidate are the same collection: " + baseline);
        }
        ignoredPaths = sorted(ignoredPaths);
        orderSensitivePaths = sorted(orderSensitivePaths);
        wildcardPaths = sorted(wildcardPaths);
    }

    private static List<String> sorted(List<String> paths) {
        return paths.stream().sorted().distinct().toList();
    }

    /**
     * Technical knobs that do not change what is measured.
     *
     * @param batchSize             cursor batch size
     * @param noCursorTimeout       keep cursors open on the server while idle (one side may idle while the other
     *                              walks a long run of keys)
     * @param maxExamples           example keys kept per category and per changed path
     * @param maxTrackedPaths       distinct paths tracked per collection before further paths are only counted
     * @param topChangedPaths       changed paths listed in the report
     * @param progressInterval      how often progress is logged and reported
     * @param sampleLookupBatchSize keys per {@code $in} lookup in SAMPLE mode
     */
    public record Tuning(int batchSize, boolean noCursorTimeout, int maxExamples, int maxTrackedPaths,
                         int topChangedPaths, Duration progressInterval, int sampleLookupBatchSize) {

        public Tuning {
            requirePositive("batchSize", batchSize);
            requireNonNegative("maxExamples", maxExamples);
            requirePositive("maxTrackedPaths", maxTrackedPaths);
            requireNonNegative("topChangedPaths", topChangedPaths);
            requirePositive("sampleLookupBatchSize", sampleLookupBatchSize);
            requireNonNull(progressInterval, "progressInterval");
            if (progressInterval.isNegative() || progressInterval.isZero()) {
                throw new IllegalArgumentException("progressInterval must be positive, was " + progressInterval);
            }
        }

        private static void requirePositive(String name, int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive, was " + value);
            }
        }

        private static void requireNonNegative(String name, int value) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative, was " + value);
            }
        }
    }
}
