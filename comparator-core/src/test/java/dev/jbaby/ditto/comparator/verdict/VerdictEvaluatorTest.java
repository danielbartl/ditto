package dev.jbaby.ditto.comparator.verdict;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonReport.ContentMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.KeyMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathPresence;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.ComparisonReport.StructureMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShare;
import dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShift;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.Rate;
import dev.jbaby.ditto.comparator.api.Thresholds;
import dev.jbaby.ditto.comparator.api.VerdictBasis;

class VerdictEvaluatorTest {

    private final VerdictEvaluator evaluator = new VerdictEvaluator();

    @Test
    void identicalCollectionsAreGreen() {
        var verdict = evaluator.evaluate(input(keys(1000, 0, 0), content(1000, 0), List.of(), structure()));

        assertThat(verdict.overall()).isEqualTo(Level.GREEN);
        assertThat(verdict.rules()).extracting(RuleResult::rule).containsExactly("keySimilarity", "unchangedRate",
                "maxPathChangeRate", "structure.vanishedPaths", "structure.typeShifts", "structure.newPaths",
                "structure.presenceDeltas");
        assertThat(verdict.rules()).allMatch(rule -> rule.level() == Level.GREEN);
    }

    @Test
    void keySimilarityBands() {
        // 990 / 1000 = 0.99: the GREEN bound is inclusive
        assertThat(level(input(keys(990, 5, 5), content(990, 0), List.of(), structure()), "keySimilarity"))
                .isEqualTo(Level.GREEN);
        assertThat(rule(input(keys(990, 5, 5), content(990, 0), List.of(), structure()), "keySimilarity").observed())
                .isEqualTo(0.99);
        assertThat(level(input(keys(980, 10, 10), content(980, 0), List.of(), structure()), "keySimilarity"))
                .isEqualTo(Level.YELLOW);
        assertThat(level(input(keys(960, 40, 0), content(960, 0), List.of(), structure()), "keySimilarity"))
                .isEqualTo(Level.RED);
    }

    @Test
    void emptyCollectionsAndNoMatchesAreNotPenalisedTwice() {
        var bothEmpty = evaluator.evaluate(input(keys(0, 0, 0), content(0, 0), List.of(), structure()));
        assertThat(bothEmpty.overall()).isEqualTo(Level.GREEN);
        assertThat(rule(bothEmpty, "keySimilarity").reason()).isEqualTo("Both collections are empty");

        var candidateEmpty = evaluator.evaluate(input(keys(0, 0, 50), content(0, 0), List.of(), structure()));
        assertThat(rule(candidateEmpty, "keySimilarity").level()).isEqualTo(Level.RED);
        assertThat(rule(candidateEmpty, "unchangedRate").reason()).startsWith("Not applicable");
    }

    @Test
    void oneFieldChangedEverywhereIsRed() {
        var changes = List.of(new PathChange("price", 1000, Rate.exact(1000, 1000), List.of()),
                new PathChange("name", 30, Rate.exact(30, 1000), List.of()));
        var verdict = evaluator.evaluate(input(keys(1000, 0, 0), content(0, 1000), changes, structure()));

        assertThat(verdict.overall()).isEqualTo(Level.RED);
        RuleResult pathRule = rule(verdict, "maxPathChangeRate");
        assertThat(pathRule.level()).isEqualTo(Level.RED);
        assertThat(pathRule.observed()).isEqualTo(1.0);
        assertThat(pathRule.reason()).contains("'price'");
        assertThat(pathRule.details()).containsExactly("price: 1.0000 (1000 documents)");
        assertThat(rule(verdict, "unchangedRate").level()).isEqualTo(Level.RED);
    }

    @Test
    void structureRules() {
        var vanished = PathPresence.of("legacy", 1000, 1000, 0, 1000);
        var rare = PathPresence.of("rare", 1, 1000, 0, 1000);
        var added = PathPresence.of("extra", 0, 1000, 500, 1000);
        var shift = new TypeShift("qty", List.of(new TypeShare("INT32", 1000, 1.0)),
                List.of(new TypeShare("DOUBLE", 1000, 1.0)));

        assertThat(levels(evaluator.evaluate(input(keys(1000, 0, 0), content(1000, 0), List.of(),
                structure(List.of(added), List.of(), List.of())))))
                .containsEntry("structure.newPaths", Level.YELLOW)
                .containsEntry("structure.vanishedPaths", Level.GREEN);
        var red = evaluator.evaluate(input(keys(1000, 0, 0), content(1000, 0), List.of(),
                structure(List.of(), List.of(vanished, rare), List.of(shift))));
        assertThat(red.overall()).isEqualTo(Level.RED);
        assertThat(rule(red, "structure.vanishedPaths").details())
                .containsExactly("legacy (in 1.0000 of baseline)", "rare (in 0.0010 of baseline)");
        assertThat(rule(red, "structure.typeShifts").details()).containsExactly("qty: INT32 1.0000 -> DOUBLE 1.0000");

        var tolerant = Thresholds.DEFAULTS.withStructure(new Thresholds.StructureThresholds(0.01, 0.05, 0.01));
        var onlyRare = evaluator.evaluate(new RuleInput(keys(1000, 0, 0), content(1000, 0), List.of(),
                structure(List.of(), List.of(rare), List.of()), tolerant, VerdictBasis.POINT));
        assertThat(onlyRare.overall()).isEqualTo(Level.YELLOW);
        assertThat(rule(onlyRare, "structure.vanishedPaths").reason()).contains("1 of them rare");
    }

    @Test
    void conservativeBasisEvaluatesTheWorseBound() {
        var estimatedKeys = new KeyMetrics(9950, 25, 25, new Rate.Estimate(0.995, 0.985, 0.998, 1000),
                Rate.exact(0, 0), Rate.exact(0, 0));
        var estimatedContent = new ContentMetrics(1000, 0, new Rate.Estimate(1.0, 0.996, 1.0, 1000),
                new Rate.Estimate(0.0, 0.0, 0.004, 1000));

        var point = evaluator.evaluate(new RuleInput(estimatedKeys, estimatedContent, List.of(), structure(),
                Thresholds.DEFAULTS, VerdictBasis.POINT));
        var conservative = evaluator.evaluate(new RuleInput(estimatedKeys, estimatedContent, List.of(), structure(),
                Thresholds.DEFAULTS, VerdictBasis.CONSERVATIVE));

        assertThat(point.overall()).isEqualTo(Level.GREEN);
        assertThat(conservative.overall()).isEqualTo(Level.YELLOW);
        assertThat(rule(conservative, "keySimilarity").observed()).isEqualTo(0.985);
        assertThat(rule(conservative, "keySimilarity").reason()).contains("evaluated at lower bound");
    }

    private static RuleInput input(KeyMetrics keys, ContentMetrics content, List<PathChange> changes,
                                   StructureMetrics structure) {
        return new RuleInput(keys, content, changes, structure, Thresholds.DEFAULTS, VerdictBasis.CONSERVATIVE);
    }

    private static KeyMetrics keys(long matched, long added, long removed) {
        return new KeyMetrics(matched, added, removed, Rate.exact(matched, matched + added + removed),
                Rate.exact(added, matched + added), Rate.exact(removed, matched + removed));
    }

    private static ContentMetrics content(long unchanged, long changed) {
        return new ContentMetrics(unchanged, changed, Rate.exact(unchanged, unchanged + changed),
                Rate.exact(changed, unchanged + changed));
    }

    private static StructureMetrics structure() {
        return structure(List.of(), List.of(), List.of());
    }

    private static StructureMetrics structure(List<PathPresence> added, List<PathPresence> missing,
                                              List<TypeShift> shifts) {
        return new StructureMetrics(1000, 1000, 10, 10, added, missing, shifts, List.of(), false, 0);
    }

    private Level level(RuleInput input, String rule) {
        return rule(input, rule).level();
    }

    private RuleResult rule(RuleInput input, String rule) {
        return rule(evaluator.evaluate(input), rule);
    }

    private static RuleResult rule(VerdictEvaluator.Verdict verdict, String rule) {
        return verdict.rules().stream().filter(r -> r.rule().equals(rule)).findFirst().orElseThrow();
    }

    private static Map<String, Level> levels(VerdictEvaluator.Verdict verdict) {
        return verdict.rules().stream().collect(Collectors.toMap(RuleResult::rule, RuleResult::level));
    }
}
