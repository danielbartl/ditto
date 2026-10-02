package dev.jbaby.ditto.comparator.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.MixedKeyPolicy;
import dev.jbaby.ditto.comparator.api.Thresholds;
import dev.jbaby.ditto.comparator.api.VerdictBasis;

/**
 * Defaults for every {@link ComparisonRequest} option, plus technical settings. A request overrides an option by
 * setting it to a non-null value.
 */
@ConfigurationProperties("comparator")
public class ComparatorProperties {

    /** Top-level field identifying a document in both collections. */
    private String keyField = "_id";

    /** Paths removed before comparing, e.g. technical sync timestamps. */
    private List<String> ignoredPaths = new ArrayList<>();

    /** Arrays whose element order matters; all other arrays are compared as multisets. */
    private List<String> orderSensitivePaths = new ArrayList<>();

    /** Objects with dynamic keys (maps) collapsed to one path in path statistics, e.g. {@code attributes.*}. */
    private List<String> wildcardPaths = new ArrayList<>();

    /** Whether a field with value null counts as equal to a missing field. */
    private boolean nullEqualsMissing = false;

    /** What to do if key values of different BSON types are found. */
    private MixedKeyPolicy mixedKeyTypes = MixedKeyPolicy.REJECT;

    /** Default comparison mode. */
    private Mode mode = Mode.FULL;

    /** Cursor batch size. */
    private int batchSize = 1000;

    /** Keep server cursors open while idle; enable for very large collections with long runs of added/removed keys. */
    private boolean noCursorTimeout = false;

    /** Example keys kept per category and per changed path. */
    private int maxExamples = 20;

    /** Distinct paths tracked per collection; further paths are only counted. */
    private int maxTrackedPaths = 10_000;

    /** Changed paths listed in the report. */
    private int topChangedPaths = 50;

    /** How often progress is logged and reported. */
    private Duration progressInterval = Duration.ofSeconds(10);

    private final Sample sample = new Sample();

    private final Persistence persistence = new Persistence();

    private final ThresholdProperties thresholds = new ThresholdProperties();

    /**
     * Merges the request with these defaults.
     *
     * @param defaultDatabase database used for collections without an explicit database
     */
    public ComparisonSettings settingsFor(ComparisonRequest request, String defaultDatabase) {
        return new ComparisonSettings(
                request.baseline().withDefaultDatabase(defaultDatabase),
                request.candidate().withDefaultDatabase(defaultDatabase),
                Objects.requireNonNullElse(request.keyField(), keyField),
                orDefault(request.ignoredPaths(), ignoredPaths),
                orDefault(request.orderSensitivePaths(), orderSensitivePaths),
                orDefault(request.wildcardPaths(), wildcardPaths),
                Objects.requireNonNullElseGet(request.mode(), this::defaultMode),
                Objects.requireNonNullElse(request.nullEqualsMissing(), nullEqualsMissing),
                Objects.requireNonNullElse(request.mixedKeyPolicy(), mixedKeyTypes),
                Objects.requireNonNullElse(request.verdictBasis(), sample.verdictBasis),
                Objects.requireNonNullElseGet(request.thresholds(), thresholds::toThresholds),
                new ComparisonSettings.Tuning(batchSize, noCursorTimeout, maxExamples, maxTrackedPaths,
                        topChangedPaths, progressInterval, sample.lookupBatchSize));
    }

    private ComparisonMode defaultMode() {
        return switch (mode) {
            case FULL -> ComparisonMode.full();
            case SAMPLE -> ComparisonMode.sample(sample.size);
        };
    }

    private static List<String> orDefault(@Nullable Set<String> requested, List<String> defaults) {
        return requested != null ? List.copyOf(requested) : List.copyOf(defaults);
    }

    public enum Mode {
        FULL,
        SAMPLE
    }

    public static class Sample {

        /** Sample size used when the mode is SAMPLE and the request gives no size. */
        private int size = 10_000;

        /** Which value of estimated rates the verdict evaluates. */
        private VerdictBasis verdictBasis = VerdictBasis.CONSERVATIVE;

        /** Keys per {@code $in} lookup. */
        private int lookupBatchSize = 500;

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public VerdictBasis getVerdictBasis() {
            return verdictBasis;
        }

        public void setVerdictBasis(VerdictBasis verdictBasis) {
            this.verdictBasis = verdictBasis;
        }

        public int getLookupBatchSize() {
            return lookupBatchSize;
        }

        public void setLookupBatchSize(int lookupBatchSize) {
            this.lookupBatchSize = lookupBatchSize;
        }
    }

    public static class Persistence {

        /** Store every report in MongoDB. */
        private boolean enabled = false;

        /** Collection the reports are stored in. */
        private String collection = "comparison_reports";

        /** Database the reports are stored in; the default database if not set. */
        private @Nullable String database;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public @Nullable String getDatabase() {
            return database;
        }

        public void setDatabase(@Nullable String database) {
            this.database = database;
        }
    }

    public static class ThresholdProperties {

        private final AtLeast keySimilarity = new AtLeast(0.99, 0.97);

        private final AtLeast unchangedRate = new AtLeast(0.95, 0.85);

        private final Below maxPathChangeRate = new Below(0.05, 0.20);

        private final Structure structure = new Structure();

        public Thresholds toThresholds() {
            return new Thresholds(
                    new Thresholds.AtLeast(keySimilarity.green, keySimilarity.yellow),
                    new Thresholds.AtLeast(unchangedRate.green, unchangedRate.yellow),
                    new Thresholds.Below(maxPathChangeRate.green, maxPathChangeRate.yellow),
                    new Thresholds.StructureThresholds(structure.typeShareDelta, structure.presenceDelta,
                            structure.vanishedMinPresence));
        }

        public AtLeast getKeySimilarity() {
            return keySimilarity;
        }

        public AtLeast getUnchangedRate() {
            return unchangedRate;
        }

        public Below getMaxPathChangeRate() {
            return maxPathChangeRate;
        }

        public Structure getStructure() {
            return structure;
        }
    }

    /** Higher is better: GREEN if the value is at least {@code green}, YELLOW if at least {@code yellow}. */
    public static class AtLeast {

        /** Minimum value for GREEN. */
        private double green;

        /** Minimum value for YELLOW. */
        private double yellow;

        AtLeast(double green, double yellow) {
            this.green = green;
            this.yellow = yellow;
        }

        public double getGreen() {
            return green;
        }

        public void setGreen(double green) {
            this.green = green;
        }

        public double getYellow() {
            return yellow;
        }

        public void setYellow(double yellow) {
            this.yellow = yellow;
        }
    }

    /** Lower is better: GREEN if the value is below {@code green}, YELLOW if below {@code yellow}. */
    public static class Below {

        /** Upper bound (exclusive) for GREEN. */
        private double green;

        /** Upper bound (exclusive) for YELLOW. */
        private double yellow;

        Below(double green, double yellow) {
            this.green = green;
            this.yellow = yellow;
        }

        public double getGreen() {
            return green;
        }

        public void setGreen(double green) {
            this.green = green;
        }

        public double getYellow() {
            return yellow;
        }

        public void setYellow(double yellow) {
            this.yellow = yellow;
        }
    }

    public static class Structure {

        /** A type's share among documents having a path may change by this much before it is a type shift. */
        private double typeShareDelta = 0.01;

        /** Paths whose presence rate changes by more than this are reported (YELLOW). */
        private double presenceDelta = 0.05;

        /** Vanished paths present in at most this fraction of baseline documents are YELLOW instead of RED. */
        private double vanishedMinPresence = 0.0;

        public double getTypeShareDelta() {
            return typeShareDelta;
        }

        public void setTypeShareDelta(double typeShareDelta) {
            this.typeShareDelta = typeShareDelta;
        }

        public double getPresenceDelta() {
            return presenceDelta;
        }

        public void setPresenceDelta(double presenceDelta) {
            this.presenceDelta = presenceDelta;
        }

        public double getVanishedMinPresence() {
            return vanishedMinPresence;
        }

        public void setVanishedMinPresence(double vanishedMinPresence) {
            this.vanishedMinPresence = vanishedMinPresence;
        }
    }

    public String getKeyField() {
        return keyField;
    }

    public void setKeyField(String keyField) {
        this.keyField = keyField;
    }

    public List<String> getIgnoredPaths() {
        return ignoredPaths;
    }

    public void setIgnoredPaths(List<String> ignoredPaths) {
        this.ignoredPaths = ignoredPaths;
    }

    public List<String> getOrderSensitivePaths() {
        return orderSensitivePaths;
    }

    public void setOrderSensitivePaths(List<String> orderSensitivePaths) {
        this.orderSensitivePaths = orderSensitivePaths;
    }

    public List<String> getWildcardPaths() {
        return wildcardPaths;
    }

    public void setWildcardPaths(List<String> wildcardPaths) {
        this.wildcardPaths = wildcardPaths;
    }

    public boolean isNullEqualsMissing() {
        return nullEqualsMissing;
    }

    public void setNullEqualsMissing(boolean nullEqualsMissing) {
        this.nullEqualsMissing = nullEqualsMissing;
    }

    public MixedKeyPolicy getMixedKeyTypes() {
        return mixedKeyTypes;
    }

    public void setMixedKeyTypes(MixedKeyPolicy mixedKeyTypes) {
        this.mixedKeyTypes = mixedKeyTypes;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isNoCursorTimeout() {
        return noCursorTimeout;
    }

    public void setNoCursorTimeout(boolean noCursorTimeout) {
        this.noCursorTimeout = noCursorTimeout;
    }

    public int getMaxExamples() {
        return maxExamples;
    }

    public void setMaxExamples(int maxExamples) {
        this.maxExamples = maxExamples;
    }

    public int getMaxTrackedPaths() {
        return maxTrackedPaths;
    }

    public void setMaxTrackedPaths(int maxTrackedPaths) {
        this.maxTrackedPaths = maxTrackedPaths;
    }

    public int getTopChangedPaths() {
        return topChangedPaths;
    }

    public void setTopChangedPaths(int topChangedPaths) {
        this.topChangedPaths = topChangedPaths;
    }

    public Duration getProgressInterval() {
        return progressInterval;
    }

    public void setProgressInterval(Duration progressInterval) {
        this.progressInterval = progressInterval;
    }

    public Sample getSample() {
        return sample;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public ThresholdProperties getThresholds() {
        return thresholds;
    }
}
