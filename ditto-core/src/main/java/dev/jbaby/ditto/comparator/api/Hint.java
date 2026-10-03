package dev.jbaby.ditto.comparator.api;

import org.jspecify.annotations.Nullable;

/**
 * A suggestion derived from the result: what to configure (or look at) so the next comparison is more meaningful.
 * ditto never applies hints by itself, e.g. it does not ignore a field just because it changed everywhere.
 *
 * @param kind      what kind of suggestion
 * @param path      the path concerned, if any
 * @param message   explanation
 * @param property  ready-to-use configuration, e.g. {@code ditto.ignored-paths=meta.syncedAt}; {@code null} if
 *                  there is nothing to configure
 * @param cliOption the same for the CLI, e.g. {@code --ignore=meta.syncedAt}
 */
public record Hint(Kind kind, @Nullable String path, String message, @Nullable String property,
                   @Nullable String cliOption) {

    public enum Kind {
        /** A technical field (e.g. a sync timestamp) that changes in every run: ignore it. */
        IGNORE_TECHNICAL_FIELD,
        /** A field with broad but partial churn: declare it as expected if the churn is intended. */
        EXPECTED_CHANGE,
        /** An object with very many distinct paths: treat it as a map. */
        WILDCARD,
        /** The sample is too small to confirm a better verdict. */
        LARGER_SAMPLE,
        /** Something worth a look, nothing to configure. */
        INVESTIGATE
    }
}
