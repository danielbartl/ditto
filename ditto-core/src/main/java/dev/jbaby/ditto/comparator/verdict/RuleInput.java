package dev.jbaby.ditto.comparator.verdict;

import java.util.List;

import dev.jbaby.ditto.comparator.api.ComparisonReport.ContentMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.KeyMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.ComparisonReport.StructureMetrics;
import dev.jbaby.ditto.comparator.api.Thresholds;
import dev.jbaby.ditto.comparator.api.VerdictBasis;

/**
 * Everything the verdict rules look at.
 *
 * @param keys         key metrics
 * @param content      content metrics
 * @param changedPaths changed paths, most frequent first
 * @param structure    structure metrics
 * @param thresholds   thresholds to apply
 * @param basis        which value of estimated rates to evaluate
 */
public record RuleInput(KeyMetrics keys, ContentMetrics content, List<PathChange> changedPaths,
                        StructureMetrics structure, Thresholds thresholds, VerdictBasis basis) {

    public RuleInput {
        changedPaths = List.copyOf(changedPaths);
    }
}
