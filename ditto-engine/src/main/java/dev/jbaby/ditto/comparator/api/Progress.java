package dev.jbaby.ditto.comparator.api;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

/**
 * A progress snapshot of a running comparison.
 *
 * @param baselineDocs   baseline documents read so far
 * @param candidateDocs  candidate documents read so far
 * @param expectedDocs   estimated total documents to read (both sides), {@code null} if unknown
 * @param elapsed        time since the comparison started
 * @param docsPerSecond  average read rate since the start
 */
public record Progress(long baselineDocs, long candidateDocs, @Nullable Long expectedDocs, Duration elapsed,
                       double docsPerSecond) {

    public long processedDocs() {
        return baselineDocs + candidateDocs;
    }

    /** Fraction done in [0, 1], {@code null} if the total is unknown. */
    public @Nullable Double fractionDone() {
        if (expectedDocs == null || expectedDocs == 0) {
            return null;
        }
        return Math.min(1.0, (double) processedDocs() / expectedDocs);
    }
}
