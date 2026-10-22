package dev.jbaby.ditto.comparator.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.ThresholdSource;
import dev.jbaby.ditto.comparator.api.Thresholds;

/**
 * Derives thresholds from previous comparisons of the same pair of collections, so the verdict answers "is this run
 * unusual compared with the normal churn of this collection?" instead of comparing against fixed numbers.
 * <p>
 * For the key similarity, unchanged rate and maximum path change rate of the last {@code historySize} non-RED runs:
 * GREEN starts {@code greenSigma} standard deviations from the mean, YELLOW {@code yellowSigma}, in the direction
 * that is worse for the metric. The standard deviation is at least {@code minSpread}, so a perfectly stable history
 * does not turn the smallest deviation RED. Structure thresholds stay as configured. With fewer than
 * {@code minHistory} runs the configured thresholds apply.
 * <p>
 * RED runs are left out, so a broken run does not lower the bar for the next one; a slow drift across many GREEN or
 * YELLOW runs is still learned. Thread-safe.
 */
public final class ThresholdAdvisor {

    /**
     * @param historySize  previous runs considered at most
     * @param minHistory   runs needed before history is used
     * @param greenSigma   distance of the GREEN bound from the mean, in standard deviations
     * @param yellowSigma  distance of the YELLOW bound from the mean, in standard deviations
     * @param minSpread    lower limit for the standard deviation
     */
    public record Settings(int historySize, int minHistory, double greenSigma, double yellowSigma, double minSpread) {

        public Settings {
            if (historySize < 1 || minHistory < 1 || minHistory > historySize) {
                throw new IllegalArgumentException("need 1 <= minHistory <= historySize");
            }
            if (!(greenSigma >= 0 && yellowSigma >= greenSigma)) {
                throw new IllegalArgumentException("need 0 <= greenSigma <= yellowSigma");
            }
            if (!(minSpread >= 0 && minSpread < 1)) {
                throw new IllegalArgumentException("minSpread must be in [0, 1)");
            }
        }
    }

    /**
     * @param thresholds the thresholds to use
     * @param source     where they came from
     */
    public record Advice(Thresholds thresholds, ThresholdSource source) {
    }

    private final ReportHistory history;
    private final Settings settings;

    public ThresholdAdvisor(ReportHistory history, Settings settings) {
        this.history = history;
        this.settings = settings;
    }

    public Advice advise(ComparisonSettings comparison) {
        Thresholds configured = comparison.thresholds();
        List<ComparisonReport> history = this.history.previousRuns(comparison, settings.historySize());
        if (history.size() < settings.minHistory()) {
            return new Advice(configured, new ThresholdSource(ThresholdSource.Kind.CONFIGURED, 0, List.of(
                    "History not used: " + history.size() + " of " + settings.minHistory()
                            + " required previous non-RED runs")));
        }
        List<String> notes = new ArrayList<>();
        Thresholds.@Nullable AtLeast keySimilarity = atLeast("keySimilarity", observed(history, "keySimilarity"), notes);
        Thresholds.@Nullable AtLeast unchangedRate = atLeast("unchangedRate", observed(history, "unchangedRate"), notes);
        Thresholds.@Nullable Below maxPathChangeRate =
                below("maxPathChangeRate", observed(history, "maxPathChangeRate"), notes);
        Thresholds derived = configured
                .withKeySimilarity(keySimilarity != null ? keySimilarity : configured.keySimilarity())
                .withUnchangedRate(unchangedRate != null ? unchangedRate : configured.unchangedRate())
                .withMaxPathChangeRate(maxPathChangeRate != null ? maxPathChangeRate : configured.maxPathChangeRate());
        return new Advice(derived, new ThresholdSource(ThresholdSource.Kind.HISTORY, history.size(), notes));
    }

    private static List<Double> observed(List<ComparisonReport> history, String rule) {
        return history.stream()
                .flatMap(report -> report.rules().stream())
                .filter(result -> result.rule().equals(rule))
                .map(RuleResult::observed)
                .filter(Objects::nonNull)
                .toList();
    }

    private Thresholds.@Nullable AtLeast atLeast(String metric, List<Double> values, List<String> notes) {
        if (values.size() < settings.minHistory()) {
            notes.add(metric + ": configured thresholds kept, only " + values.size() + " observed values");
            return null;
        }
        Stats stats = Stats.of(values, settings.minSpread());
        double green = clamp(stats.mean() - settings.greenSigma() * stats.spread());
        double yellow = Math.min(green, clamp(stats.mean() - settings.yellowSigma() * stats.spread()));
        var band = new Thresholds.AtLeast(green, yellow);
        notes.add(describe(metric, stats, values.size(), band.toString()));
        return band;
    }

    private Thresholds.@Nullable Below below(String metric, List<Double> values, List<String> notes) {
        if (values.size() < settings.minHistory()) {
            notes.add(metric + ": configured thresholds kept, only " + values.size() + " observed values");
            return null;
        }
        Stats stats = Stats.of(values, settings.minSpread());
        double green = clamp(stats.mean() + settings.greenSigma() * stats.spread());
        double yellow = Math.max(green, clamp(stats.mean() + settings.yellowSigma() * stats.spread()));
        var band = new Thresholds.Below(green, yellow);
        notes.add(describe(metric, stats, values.size(), band.toString()));
        return band;
    }

    private static String describe(String metric, Stats stats, int runs, String band) {
        return String.format(Locale.ROOT, "%s: mean %.4f, standard deviation %.4f over %d runs -> %s", metric,
                stats.mean(), stats.deviation(), runs, band);
    }

    private static double clamp(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }

    /**
     * @param mean      mean of the values
     * @param deviation sample standard deviation
     * @param spread    {@code max(deviation, minSpread)}
     */
    private record Stats(double mean, double deviation, double spread) {

        static Stats of(List<Double> values, double minSpread) {
            double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double squares = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
            double deviation = values.size() < 2 ? 0.0 : Math.sqrt(squares / (values.size() - 1));
            return new Stats(mean, deviation, Math.max(deviation, minSpread));
        }
    }
}
