package dev.jbaby.ditto.comparator.api;

/**
 * Receives periodic {@link Progress} updates, e.g. to drive a JobRunr progress bar. Called on the comparing thread;
 * keep it fast.
 */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = progress -> {
    };

    void onProgress(Progress progress);
}
