package dev.jbaby.ditto.comparator.api;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What to compare and how. Every {@code null} option falls back to the configured default
 * ({@code ditto.*} properties); the resolved values end up in {@link ComparisonSettings}.
 * <p>
 * Paths use the report syntax: {@code a.b} for nested fields, {@code items[].price} for fields of array elements and
 * {@code *} for exactly one arbitrary segment, e.g. {@code attributes.*}.
 *
 * @param baseline            the reference collection, e.g. the backup taken before a batch run
 * @param candidate           the collection to judge, e.g. the freshly replicated one
 * @param keyField            top-level field identifying a document in both collections
 * @param ignoredPaths        paths removed before comparing, e.g. sync timestamps
 * @param orderSensitivePaths arrays whose element order matters (all other arrays are compared as multisets)
 * @param wildcardPaths       objects with dynamic keys (maps), collapsed to one path in path statistics
 * @param expectedChangePaths paths that are supposed to change (prices, counters): reported, but they neither count
 *                            for maxPathChangeRate nor make a document count as changed for the unchangedRate rule
 * @param redactedPaths       paths whose values are shown as {@code ***} in value examples
 * @param mode                full scan or sample
 * @param matchedOnly         compare only documents whose key exists on both sides, e.g. when one side is a small
 *                            subset like a test environment; keySimilarity is then reported but not judged
 * @param nullEqualsMissing   whether a field with value {@code null} counts as equal to a missing field
 * @param mixedKeyPolicy      what to do if key values of different BSON types are found
 * @param verdictBasis        which value of estimated rates the verdict uses (SAMPLE mode)
 * @param thresholds          verdict thresholds
 */
public record ComparisonRequest(
        CollectionRef baseline,
        CollectionRef candidate,
        @Nullable String keyField,
        @Nullable Set<String> ignoredPaths,
        @Nullable Set<String> orderSensitivePaths,
        @Nullable Set<String> wildcardPaths,
        @Nullable Set<String> expectedChangePaths,
        @Nullable Set<String> redactedPaths,
        @Nullable ComparisonMode mode,
        @Nullable Boolean matchedOnly,
        @Nullable Boolean nullEqualsMissing,
        @Nullable MixedKeyPolicy mixedKeyPolicy,
        @Nullable VerdictBasis verdictBasis,
        @Nullable Thresholds thresholds) {

    public ComparisonRequest {
        requireNonNull(baseline, "baseline");
        requireNonNull(candidate, "candidate");
        if (keyField != null && (keyField.isBlank() || keyField.contains("."))) {
            throw new IllegalArgumentException("keyField must be a top-level field name, was '" + keyField + "'");
        }
        ignoredPaths = ignoredPaths == null ? null : Set.copyOf(ignoredPaths);
        orderSensitivePaths = orderSensitivePaths == null ? null : Set.copyOf(orderSensitivePaths);
        wildcardPaths = wildcardPaths == null ? null : Set.copyOf(wildcardPaths);
        expectedChangePaths = expectedChangePaths == null ? null : Set.copyOf(expectedChangePaths);
        redactedPaths = redactedPaths == null ? null : Set.copyOf(redactedPaths);
    }

    /** Request comparing two collections of the default database with all defaults. */
    public static ComparisonRequest of(String baselineCollection, String candidateCollection) {
        return builder(baselineCollection, candidateCollection).build();
    }

    public static Builder builder(String baselineCollection, String candidateCollection) {
        return new Builder(CollectionRef.of(baselineCollection), CollectionRef.of(candidateCollection));
    }

    public static Builder builder(CollectionRef baseline, CollectionRef candidate) {
        return new Builder(baseline, candidate);
    }

    public Builder toBuilder() {
        return new Builder(baseline, candidate)
                .keyField(keyField)
                .ignoredPaths(ignoredPaths)
                .orderSensitivePaths(orderSensitivePaths)
                .wildcardPaths(wildcardPaths)
                .expectedChangePaths(expectedChangePaths)
                .redactedPaths(redactedPaths)
                .mode(mode)
                .matchedOnly(matchedOnly)
                .nullEqualsMissing(nullEqualsMissing)
                .mixedKeyPolicy(mixedKeyPolicy)
                .verdictBasis(verdictBasis)
                .thresholds(thresholds);
    }

    public static final class Builder {

        private final CollectionRef baseline;
        private final CollectionRef candidate;
        private @Nullable String keyField;
        private @Nullable Set<String> ignoredPaths;
        private @Nullable Set<String> orderSensitivePaths;
        private @Nullable Set<String> wildcardPaths;
        private @Nullable Set<String> expectedChangePaths;
        private @Nullable Set<String> redactedPaths;
        private @Nullable ComparisonMode mode;
        private @Nullable Boolean matchedOnly;
        private @Nullable Boolean nullEqualsMissing;
        private @Nullable MixedKeyPolicy mixedKeyPolicy;
        private @Nullable VerdictBasis verdictBasis;
        private @Nullable Thresholds thresholds;

        private Builder(CollectionRef baseline, CollectionRef candidate) {
            this.baseline = baseline;
            this.candidate = candidate;
        }

        public Builder keyField(@Nullable String keyField) {
            this.keyField = keyField;
            return this;
        }

        public Builder ignoredPaths(@Nullable Collection<String> paths) {
            this.ignoredPaths = paths == null ? null : Set.copyOf(paths);
            return this;
        }

        public Builder ignoredPaths(String... paths) {
            return ignoredPaths(Arrays.asList(paths));
        }

        public Builder orderSensitivePaths(@Nullable Collection<String> paths) {
            this.orderSensitivePaths = paths == null ? null : Set.copyOf(paths);
            return this;
        }

        public Builder orderSensitivePaths(String... paths) {
            return orderSensitivePaths(Arrays.asList(paths));
        }

        public Builder wildcardPaths(@Nullable Collection<String> paths) {
            this.wildcardPaths = paths == null ? null : Set.copyOf(paths);
            return this;
        }

        public Builder wildcardPaths(String... paths) {
            return wildcardPaths(Arrays.asList(paths));
        }

        public Builder expectedChangePaths(@Nullable Collection<String> paths) {
            this.expectedChangePaths = paths == null ? null : Set.copyOf(paths);
            return this;
        }

        public Builder expectedChangePaths(String... paths) {
            return expectedChangePaths(Arrays.asList(paths));
        }

        public Builder redactedPaths(@Nullable Collection<String> paths) {
            this.redactedPaths = paths == null ? null : Set.copyOf(paths);
            return this;
        }

        public Builder redactedPaths(String... paths) {
            return redactedPaths(Arrays.asList(paths));
        }

        public Builder mode(@Nullable ComparisonMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder fullScan() {
            return mode(ComparisonMode.full());
        }

        public Builder sample(int size) {
            return mode(ComparisonMode.sample(size));
        }

        public Builder matchedOnly(@Nullable Boolean matchedOnly) {
            this.matchedOnly = matchedOnly;
            return this;
        }

        /** Compares only documents whose key exists on both sides. */
        public Builder matchedOnly() {
            return matchedOnly(true);
        }

        public Builder nullEqualsMissing(@Nullable Boolean nullEqualsMissing) {
            this.nullEqualsMissing = nullEqualsMissing;
            return this;
        }

        public Builder mixedKeyPolicy(@Nullable MixedKeyPolicy mixedKeyPolicy) {
            this.mixedKeyPolicy = mixedKeyPolicy;
            return this;
        }

        public Builder verdictBasis(@Nullable VerdictBasis verdictBasis) {
            this.verdictBasis = verdictBasis;
            return this;
        }

        public Builder thresholds(@Nullable Thresholds thresholds) {
            this.thresholds = thresholds;
            return this;
        }

        public ComparisonRequest build() {
            return new ComparisonRequest(baseline, candidate, keyField, ignoredPaths, orderSensitivePaths,
                    wildcardPaths, expectedChangePaths, redactedPaths, mode, matchedOnly, nullEqualsMissing,
                    mixedKeyPolicy, verdictBasis, thresholds);
        }
    }
}
