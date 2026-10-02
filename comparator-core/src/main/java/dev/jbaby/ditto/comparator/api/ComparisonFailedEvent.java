package dev.jbaby.ditto.comparator.api;

/**
 * Published (as a Spring application event) when a comparison could not be carried out, before the exception is
 * thrown to the caller.
 *
 * @param settings  the effective settings of the failed comparison
 * @param exception the exception thrown to the caller
 */
public record ComparisonFailedEvent(ComparisonSettings settings, RuntimeException exception) {
}
