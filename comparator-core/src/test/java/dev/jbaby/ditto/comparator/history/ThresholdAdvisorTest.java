package dev.jbaby.ditto.comparator.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.Rate;
import dev.jbaby.ditto.comparator.api.ThresholdSource;
import dev.jbaby.ditto.comparator.api.Thresholds;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;

class ThresholdAdvisorTest {

    private static final ThresholdAdvisor.Settings SETTINGS = new ThresholdAdvisor.Settings(20, 5, 2.0, 3.0, 0.005);
    private static final ComparisonSettings COMPARISON =
            new ComparatorProperties().settingsFor(ComparisonRequest.of("p_backup", "p"), "shop");

    @Test
    void keepsConfiguredThresholdsWithoutEnoughHistory() {
        var advice = advise(List.of(report(0.99, 0.9, 0.1), report(0.99, 0.9, 0.1)));

        assertThat(advice.thresholds()).isEqualTo(Thresholds.DEFAULTS);
        assertThat(advice.source().kind()).isEqualTo(ThresholdSource.Kind.CONFIGURED);
        assertThat(advice.source().notes()).containsExactly("History not used: 2 of 5 required previous non-RED runs");
    }

    @Test
    void derivesBandsFromMeanAndSpread() {
        var advice = advise(List.of(
                report(0.995, 0.90, 0.10), report(0.996, 0.92, 0.12), report(0.994, 0.91, 0.11),
                report(0.995, 0.89, 0.10), report(0.996, 0.93, 0.12)));

        Thresholds thresholds = advice.thresholds();
        // keySimilarity: sd 0.0008 < minSpread 0.005 -> mean 0.9952 - 2 / 3 * 0.005
        assertThat(thresholds.keySimilarity().green()).isCloseTo(0.9852, within(1e-9));
        assertThat(thresholds.keySimilarity().yellow()).isCloseTo(0.9802, within(1e-9));
        // unchangedRate: mean 0.91, sd 0.0158
        assertThat(thresholds.unchangedRate().green()).isCloseTo(0.91 - 2 * 0.0158114, within(1e-6));
        assertThat(thresholds.unchangedRate().yellow()).isCloseTo(0.91 - 3 * 0.0158114, within(1e-6));
        // maxPathChangeRate (lower is better): mean 0.11, sd 0.01
        assertThat(thresholds.maxPathChangeRate().green()).isCloseTo(0.13, within(1e-6));
        assertThat(thresholds.maxPathChangeRate().yellow()).isCloseTo(0.14, within(1e-6));
        assertThat(thresholds.structure()).isEqualTo(Thresholds.DEFAULTS.structure());

        assertThat(advice.source().kind()).isEqualTo(ThresholdSource.Kind.HISTORY);
        assertThat(advice.source().historyRuns()).isEqualTo(5);
        assertThat(advice.source().notes()).hasSize(3).first().asString()
                .startsWith("keySimilarity: mean 0.9952, standard deviation 0.0008 over 5 runs -> GREEN >= 0.985");
    }

    @Test
    void clampsToValidRangeAndKeepsMetricsWithoutValues() {
        List<ComparisonReport> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            history.add(report(1.0, null, 0.0));
        }

        var thresholds = advise(history).thresholds();

        assertThat(thresholds.keySimilarity()).isEqualTo(new Thresholds.AtLeast(0.99, 0.985));
        assertThat(thresholds.unchangedRate()).isEqualTo(Thresholds.DEFAULTS.unchangedRate());
        assertThat(thresholds.maxPathChangeRate()).isEqualTo(new Thresholds.Below(0.01, 0.015));
    }

    private static ThresholdAdvisor.Advice advise(List<ComparisonReport> history) {
        return new ThresholdAdvisor((settings, limit) -> history, SETTINGS).advise(COMPARISON);
    }

    static ComparisonReport report(double keySimilarity, Double unchangedRate, double maxPathChangeRate) {
        var rules = List.of(
                new RuleResult("keySimilarity", Level.GREEN, keySimilarity, "", "", List.of()),
                new RuleResult("unchangedRate", Level.GREEN, unchangedRate, "", "", List.of()),
                new RuleResult("maxPathChangeRate", Level.GREEN, maxPathChangeRate, "", "", List.of()));
        var none = Rate.exact(0, 0);
        return new ComparisonReport("id", Level.GREEN, rules,
                new ComparisonReport.KeyMetrics(0, 0, 0, none, none, none),
                new ComparisonReport.ContentMetrics(0, 0, 0, none, none, none), List.of(),
                new ComparisonReport.StructureMetrics(0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), false, 0),
                new ComparisonReport.Examples(List.of(), List.of(), List.of()),
                new ComparisonReport.RunMetadata(Instant.EPOCH, Instant.EPOCH, 0, 0, 0, 0, 0, COMPARISON,
                        ThresholdSource.configured()),
                List.of());
    }
}
