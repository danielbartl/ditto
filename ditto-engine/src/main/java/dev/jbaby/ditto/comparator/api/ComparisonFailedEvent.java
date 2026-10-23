package dev.jbaby.ditto.comparator.api;

import java.util.Map;

/**
 * Published (as a Spring application event) when a comparison could not be carried out, before the exception is
 * thrown to the caller.
 *
 * @param settings  the effective settings of the failed comparison. If the store could not even name its default
 *                  database (OpenSearch: the cluster), e.g. because it is unreachable, names without a database are
 *                  qualified with the store instead, e.g. {@code opensearch.products}
 * @param exception the exception thrown to the caller
 * @param labels    the labels of the comparison, see {@link Labels}
 */
public record ComparisonFailedEvent(ComparisonSettings settings, RuntimeException exception,
                                    Map<String, String> labels) {

    public ComparisonFailedEvent {
        labels = Map.copyOf(labels);
    }
}
