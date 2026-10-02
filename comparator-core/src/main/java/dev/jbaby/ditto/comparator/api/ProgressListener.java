package dev.jbaby.ditto.comparator.api;

/**
 * Receives periodic {@link Progress} updates, e.g. to log or display progress. Called on the comparing thread; keep
 * it fast.
 */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = progress -> {
    };

    void onProgress(Progress progress);
}
