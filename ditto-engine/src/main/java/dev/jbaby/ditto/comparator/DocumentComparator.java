package dev.jbaby.ditto.comparator;

import dev.jbaby.ditto.comparator.api.ComparisonCompletedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonFailedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ProgressListener;

/**
 * Compares two sets of documents in one store and judges how similar they are: {@code CollectionComparator}
 * (ditto-core) for MongoDB collections, {@code IndexComparator} (ditto-opensearch) for OpenSearch indices. Names are collection
 * or index names.
 * <p>
 * Blocking; runs on the calling thread and honours thread interruption. Thread-safe: concurrent comparisons are
 * independent. Publishes a {@link ComparisonCompletedEvent} or {@link ComparisonFailedEvent} for every comparison.
 */
public interface DocumentComparator {

    /**
     * Compares {@code name + backup-suffix} (default {@code _backup}) as baseline with {@code name} as candidate,
     * with all defaults.
     */
    default ComparisonReport compareWithBackup(String name) {
        return compare(backupRequest(name).build());
    }

    /** Compares two collections or indices with all defaults. */
    default ComparisonReport compare(String baseline, String candidate) {
        return compare(ComparisonRequest.of(baseline, candidate));
    }

    /** A request builder for {@code name + backup-suffix} vs. {@code name}, to add options to. */
    ComparisonRequest.Builder backupRequest(String name);

    default ComparisonReport compare(ComparisonRequest request) {
        return compare(request, ProgressListener.NONE);
    }

    /**
     * @throws ComparisonException if the comparison cannot be carried out (missing collection or index, unusable
     *                             keys, interrupted)
     */
    ComparisonReport compare(ComparisonRequest request, ProgressListener listener);
}
