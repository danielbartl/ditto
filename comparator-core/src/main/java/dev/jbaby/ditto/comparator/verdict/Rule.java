package dev.jbaby.ditto.comparator.verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathPresence;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShift;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.Rate;
import dev.jbaby.ditto.comparator.api.Thresholds;
import dev.jbaby.ditto.comparator.api.VerdictBasis;

/**
 * One verdict rule: maps one signal of the metrics to a {@link Level} and explains why.
 */
public sealed interface Rule {

    /** Offending items listed per rule at most. */
    int MAX_DETAILS = 20;

    String name();

    RuleResult evaluate(RuleInput input);

    /** All rules, in report order. */
    static List<Rule> all() {
        return List.of(new KeySimilarity(), new UnchangedRate(), new MaxPathChangeRate(), new VanishedPaths(),
                new TypeShifts(), new NewPaths(), new PresenceDeltas());
    }

    /** {@code matched / (matched + added + removed)}; higher is better. */
    record KeySimilarity() implements Rule {

        @Override
        public String name() {
            return "keySimilarity";
        }

        @Override
        public RuleResult evaluate(RuleInput input) {
            var keys = input.keys();
            Thresholds.AtLeast band = input.thresholds().keySimilarity();
            Double value = keys.keySimilarity().valueFor(input.basis(), true);
            if (value == null) {
                return new RuleResult(name(), Level.GREEN, null, band.toString(),
                        "Both collections are empty", List.of());
            }
            Level level = band.levelOf(value);
            String counts = "matched " + keys.matched() + ", added " + keys.added() + ", removed " + keys.removed();
            return new RuleResult(name(), level, value, band.toString(),
                    describe(keys.keySimilarity(), input.basis(), true) + " " + compareTo(level, band)
                            + " (" + counts + ")",
                    List.of());
        }
    }

    /** {@code unchanged / matched}; higher is better. */
    record UnchangedRate() implements Rule {

        @Override
        public String name() {
            return "unchangedRate";
        }

        @Override
        public RuleResult evaluate(RuleInput input) {
            var content = input.content();
            Thresholds.AtLeast band = input.thresholds().unchangedRate();
            Double value = content.unchangedRate().valueFor(input.basis(), true);
            if (value == null) {
                return new RuleResult(name(), Level.GREEN, null, band.toString(),
                        "Not applicable: no matched documents", List.of());
            }
            Level level = band.levelOf(value);
            return new RuleResult(name(), level, value, band.toString(),
                    describe(content.unchangedRate(), input.basis(), true) + " " + compareTo(level, band)
                            + " (unchanged " + content.unchanged() + ", changed " + content.changed() + ")",
                    List.of());
        }
    }

    /** The highest change rate of a single path; lower is better. */
    record MaxPathChangeRate() implements Rule {

        @Override
        public String name() {
            return "maxPathChangeRate";
        }

        @Override
        public RuleResult evaluate(RuleInput input) {
            Thresholds.Below band = input.thresholds().maxPathChangeRate();
            PathChange worst = null;
            double max = 0.0;
            for (PathChange change : input.changedPaths()) {
                Double value = change.changeRate().valueFor(input.basis(), false);
                if (value != null && (worst == null || value > max)) {
                    worst = change;
                    max = value;
                }
            }
            if (worst == null) {
                return new RuleResult(name(), Level.GREEN, 0.0, band.toString(), "No path changed", List.of());
            }
            Level level = band.levelOf(max);
            List<String> details = new ArrayList<>();
            for (PathChange change : input.changedPaths()) {
                Double value = change.changeRate().valueFor(input.basis(), false);
                if (value != null && band.levelOf(value) != Level.GREEN && details.size() < MAX_DETAILS) {
                    details.add(change.path() + ": " + format(value) + " (" + change.changedDocs() + " documents)");
                }
            }
            return new RuleResult(name(), level, max, band.toString(),
                    "Path '" + worst.path() + "' changed in " + describe(worst.changeRate(), input.basis(), false)
                            + " of matched documents, " + compareTo(level, band),
                    details);
        }
    }

    /** Paths present in the baseline but in no candidate document; RED (YELLOW if rare, see vanishedMinPresence). */
    record VanishedPaths() implements Rule {

        @Override
        public String name() {
            return "structure.vanishedPaths";
        }

        @Override
        public RuleResult evaluate(RuleInput input) {
            double minPresence = input.thresholds().structure().vanishedMinPresence();
            List<PathPresence> missing = input.structure().missingPaths();
            String threshold = "RED if present in more than " + format(minPresence) + " of baseline documents,"
                    + " else YELLOW";
            if (missing.isEmpty()) {
                return new RuleResult(name(), Level.GREEN, 0.0, threshold, "No path vanished", List.of());
            }
            long significant = missing.stream().filter(p -> p.baselinePresence() > minPresence).count();
            Level level = significant > 0 ? Level.RED : Level.YELLOW;
            return new RuleResult(name(), level, (double) missing.size(), threshold,
                    missing.size() + " path(s) vanished from the candidate"
                            + (significant < missing.size() ? ", " + (missing.size() - significant) + " of them rare" : ""),
                    details(missing, p -> p.path() + " (in " + format(p.baselinePresence()) + " of baseline)"));
        }
    }

    /** Paths whose BSON type distribution changed; RED. */
    record TypeShifts() implements Rule {

        @Override
        public String name() {
            return "structure.typeShifts";
        }

        @Override
        public RuleResult evaluate(RuleInput input) {
            List<TypeShift> shifts = input.structure().typeShifts();
            String threshold = "RED if a type appears, disappears or its share changes by more than "
                    + format(input.thresholds().structure().typeShareDelta());
            if (shifts.isEmpty()) {
                return new RuleResult(name(), Level.GREEN, 0.0, threshold, "No type shift", List.of());
            }
            return new RuleResult(name(), Level.RED, (double) shifts.size(), threshold,
                    shifts.size() + " path(s) changed their BSON type distribution",
                    details(shifts, shift -> shift.path() + ": " + types(shift.baseline()) + " -> "
                            + types(shift.candidate())));
        }

        private static String types(List<dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShare> shares) {
            return shares.stream().map(share -> share.type() + " " + format(share.share()))
                    .reduce((a, b) -> a + ", " + b).orElse("-");
        }
    }

    /** Paths only present in the candidate; YELLOW. */
    record NewPaths() implements Rule {

        @Override
        public String name() {
            return "structure.newPaths";
        }

        @Override
        public RuleResult evaluate(RuleInput input) {
            List<PathPresence> added = input.structure().newPaths();
            if (added.isEmpty()) {
                return new RuleResult(name(), Level.GREEN, 0.0, "YELLOW if any", "No new path", List.of());
            }
            return new RuleResult(name(), Level.YELLOW, (double) added.size(), "YELLOW if any",
                    added.size() + " path(s) only present in the candidate",
                    details(added, p -> p.path() + " (in " + format(p.candidatePresence()) + " of candidate)"));
        }
    }

    /** Paths present on both sides whose presence rate changed noticeably; YELLOW. */
    record PresenceDeltas() implements Rule {

        @Override
        public String name() {
            return "structure.presenceDeltas";
        }

        @Override
        public RuleResult evaluate(RuleInput input) {
            List<PathPresence> deltas = input.structure().presenceDeltas();
            String threshold = "YELLOW if presence changes by more than "
                    + format(input.thresholds().structure().presenceDelta());
            if (deltas.isEmpty()) {
                return new RuleResult(name(), Level.GREEN, 0.0, threshold, "No significant presence change",
                        List.of());
            }
            return new RuleResult(name(), Level.YELLOW, Math.abs(deltas.getFirst().presenceDelta()), threshold,
                    deltas.size() + " path(s) changed how often they occur",
                    details(deltas, p -> p.path() + ": " + format(p.baselinePresence()) + " -> "
                            + format(p.candidatePresence())));
        }
    }

    // --- formatting helpers ---

    private static String describe(Rate rate, VerdictBasis basis, boolean higherIsBetter) {
        return switch (rate) {
            case Rate.Exact exact -> format(exact.value());
            case Rate.Estimate estimate when basis == VerdictBasis.CONSERVATIVE -> format(estimate.value())
                    + " (estimated, 95% CI [" + format(estimate.lower()) + ", " + format(estimate.upper()) + "],"
                    + " evaluated at " + (higherIsBetter ? "lower" : "upper") + " bound)";
            case Rate.Estimate estimate -> format(estimate.value()) + " (estimated, 95% CI ["
                    + format(estimate.lower()) + ", " + format(estimate.upper()) + "])";
        };
    }

    private static String compareTo(Level level, Thresholds.AtLeast band) {
        return switch (level) {
            case GREEN -> "meets GREEN (>= " + format(band.green()) + ")";
            case YELLOW -> "is below GREEN (" + format(band.green()) + ") but meets YELLOW (>= "
                    + format(band.yellow()) + ")";
            case RED -> "is below YELLOW (" + format(band.yellow()) + ")";
        };
    }

    private static String compareTo(Level level, Thresholds.Below band) {
        return switch (level) {
            case GREEN -> "below the GREEN limit " + format(band.green());
            case YELLOW -> "at or above the GREEN limit " + format(band.green()) + " but below the YELLOW limit "
                    + format(band.yellow());
            case RED -> "at or above the YELLOW limit " + format(band.yellow());
        };
    }

    private static <T> List<String> details(List<T> items, java.util.function.Function<T, String> describe) {
        List<String> details = new ArrayList<>(items.stream().limit(MAX_DETAILS).map(describe).toList());
        if (items.size() > MAX_DETAILS) {
            details.add("... and " + (items.size() - MAX_DETAILS) + " more");
        }
        return details;
    }

    private static String format(@Nullable Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
    }
}
