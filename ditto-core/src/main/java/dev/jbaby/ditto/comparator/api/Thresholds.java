package dev.jbaby.ditto.comparator.api;

import static java.util.Objects.requireNonNull;

/**
 * Thresholds that map the measured signals to {@link Level}s.
 *
 * @param keySimilarity     matched / (matched + added + removed), higher is better
 * @param unchangedRate     unchanged / matched, higher is better
 * @param maxPathChangeRate highest change rate of a single path, lower is better
 * @param structure         structure-related thresholds
 */
public record Thresholds(AtLeast keySimilarity, AtLeast unchangedRate, Below maxPathChangeRate,
                         StructureThresholds structure) {

    public static final Thresholds DEFAULTS = new Thresholds(
            new AtLeast(0.99, 0.97),
            new AtLeast(0.95, 0.85),
            new Below(0.05, 0.20),
            new StructureThresholds(0.01, 0.05, 0.0));

    public Thresholds {
        requireNonNull(keySimilarity, "keySimilarity");
        requireNonNull(unchangedRate, "unchangedRate");
        requireNonNull(maxPathChangeRate, "maxPathChangeRate");
        requireNonNull(structure, "structure");
    }

    public Thresholds withKeySimilarity(AtLeast keySimilarity) {
        return new Thresholds(keySimilarity, unchangedRate, maxPathChangeRate, structure);
    }

    public Thresholds withUnchangedRate(AtLeast unchangedRate) {
        return new Thresholds(keySimilarity, unchangedRate, maxPathChangeRate, structure);
    }

    public Thresholds withMaxPathChangeRate(Below maxPathChangeRate) {
        return new Thresholds(keySimilarity, unchangedRate, maxPathChangeRate, structure);
    }

    public Thresholds withStructure(StructureThresholds structure) {
        return new Thresholds(keySimilarity, unchangedRate, maxPathChangeRate, structure);
    }

    /** Higher is better: GREEN if {@code value >= green}, YELLOW if {@code value >= yellow}, else RED. */
    public record AtLeast(double green, double yellow) {

        public AtLeast {
            requireFraction("green", green);
            requireFraction("yellow", yellow);
            if (yellow > green) {
                throw new IllegalArgumentException("yellow (" + yellow + ") must not exceed green (" + green + ")");
            }
        }

        public Level levelOf(double value) {
            return value >= green ? Level.GREEN : value >= yellow ? Level.YELLOW : Level.RED;
        }

        @Override
        public String toString() {
            return "GREEN >= " + green + ", YELLOW >= " + yellow;
        }
    }

    /** Lower is better: GREEN if {@code value < green}, YELLOW if {@code value < yellow}, else RED. */
    public record Below(double green, double yellow) {

        public Below {
            requireFraction("green", green);
            requireFraction("yellow", yellow);
            if (green > yellow) {
                throw new IllegalArgumentException("green (" + green + ") must not exceed yellow (" + yellow + ")");
            }
        }

        public Level levelOf(double value) {
            return value < green ? Level.GREEN : value < yellow ? Level.YELLOW : Level.RED;
        }

        @Override
        public String toString() {
            return "GREEN < " + green + ", YELLOW < " + yellow;
        }
    }

    /**
     * Thresholds of the structure rule. A vanished path or a type shift is RED, new paths or presence deltas are YELLOW.
     *
     * @param typeShareDelta      a path has a type shift if a BSON type appears or disappears on it, or if the share of
     *                            a type among the documents having the path changes by more than this
     * @param presenceDelta       report a path (YELLOW) if the fraction of documents containing it changes by more than this
     * @param vanishedMinPresence a vanished path is only RED if it was present in more than this fraction of baseline
     *                            documents, otherwise YELLOW; 0 means every vanished path is RED
     */
    public record StructureThresholds(double typeShareDelta, double presenceDelta, double vanishedMinPresence) {

        public StructureThresholds {
            requireFraction("typeShareDelta", typeShareDelta);
            requireFraction("presenceDelta", presenceDelta);
            requireFraction("vanishedMinPresence", vanishedMinPresence);
        }
    }

    private static void requireFraction(String name, double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, was " + value);
        }
    }
}
