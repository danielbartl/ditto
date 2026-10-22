package dev.jbaby.ditto.comparator.engine;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.Store;
import dev.jbaby.ditto.comparator.scan.DocumentSource;
import dev.jbaby.ditto.comparator.scan.PreflightResult;

/**
 * The store-specific part of a comparison: opens both sides, checks them before anything is read and translates the
 * store's exceptions.
 */
public interface DocumentStore {

    Store kind();

    /** Database (OpenSearch: cluster name) for references that don't name one. */
    String defaultDatabase();

    /**
     * Opens both sides of a comparison and checks them before anything is read.
     *
     * @throws ComparisonException if a side does not exist or the keys cannot be compared
     */
    Sides open(ComparisonSettings settings);

    /** What to throw for an exception thrown during a comparison, e.g. a translated driver exception. */
    default RuntimeException translate(RuntimeException e, ComparisonSettings settings) {
        return e;
    }

    /**
     * @param baseline  the baseline side
     * @param candidate the candidate side
     * @param preflight document counts and warnings
     */
    record Sides(DocumentSource baseline, DocumentSource candidate, PreflightResult preflight) {
    }
}
