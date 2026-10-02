package dev.jbaby.ditto.comparator.api;

/**
 * Published (as a Spring application event) after every successful comparison, whatever the verdict, e.g. to send
 * alerts on RED without wrapping the comparator.
 * <pre>{@code
 * @EventListener
 * void on(ComparisonCompletedEvent event) {
 *     if (event.report().verdict() == Level.RED) { ... }
 * }
 * }</pre>
 *
 * @param report the report
 */
public record ComparisonCompletedEvent(ComparisonReport report) {
}
