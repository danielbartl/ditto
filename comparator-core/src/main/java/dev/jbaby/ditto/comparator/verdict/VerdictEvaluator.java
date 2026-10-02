package dev.jbaby.ditto.comparator.verdict;

import java.util.List;

import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.Level;

/**
 * Evaluates every {@link Rule}; the overall verdict is the worst level of all rules. Thread-safe.
 */
public final class VerdictEvaluator {

    private final List<Rule> rules;

    public VerdictEvaluator() {
        this(Rule.all());
    }

    VerdictEvaluator(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * @param overall the worst level of all rules
     * @param rules   the result of every rule
     */
    public record Verdict(Level overall, List<RuleResult> rules) {

        public Verdict {
            rules = List.copyOf(rules);
        }
    }

    public Verdict evaluate(RuleInput input) {
        List<RuleResult> results = rules.stream().map(rule -> rule.evaluate(input)).toList();
        return new Verdict(Level.worstOf(results.stream().map(RuleResult::level).toList()), results);
    }
}
