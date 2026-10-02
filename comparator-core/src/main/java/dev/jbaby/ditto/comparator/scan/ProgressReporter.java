package dev.jbaby.ditto.comparator.scan;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.Progress;
import dev.jbaby.ditto.comparator.api.ProgressListener;

/**
 * Counts documents read, logs progress and notifies the {@link ProgressListener} every {@code interval}, and stops
 * the scan if the thread was interrupted (e.g. a cancelled background job). Not thread-safe.
 */
public final class ProgressReporter {

    private static final Logger log = LoggerFactory.getLogger(ProgressReporter.class);
    private static final int CHECK_EVERY = 256;

    private final String label;
    private final long intervalNanos;
    private final @Nullable Long expectedDocs;
    private final ProgressListener listener;
    private final long startNanos;
    private long nextReportNanos;
    private long baselineDocs;
    private long candidateDocs;

    public ProgressReporter(String label, Duration interval, @Nullable Long expectedDocs, ProgressListener listener) {
        this.label = label;
        this.intervalNanos = interval.toNanos();
        this.expectedDocs = expectedDocs;
        this.listener = listener;
        this.startNanos = System.nanoTime();
        this.nextReportNanos = startNanos + intervalNanos;
    }

    public void baselineRead() {
        baselineDocs++;
        tick();
    }

    public void candidateRead() {
        candidateDocs++;
        tick();
    }

    public long baselineDocs() {
        return baselineDocs;
    }

    public long candidateDocs() {
        return candidateDocs;
    }

    /** Reports the final state. */
    public void finish() {
        Progress progress = snapshot(System.nanoTime());
        log.info("{}: finished, {} baseline + {} candidate documents in {} ({} docs/s)", label,
                progress.baselineDocs(), progress.candidateDocs(), format(progress.elapsed()),
                Math.round(progress.docsPerSecond()));
        listener.onProgress(progress);
    }

    private void tick() {
        if ((baselineDocs + candidateDocs) % CHECK_EVERY != 0) {
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new ComparisonException(label + ": interrupted after " + (baselineDocs + candidateDocs)
                    + " documents");
        }
        long now = System.nanoTime();
        if (now >= nextReportNanos) {
            nextReportNanos = now + intervalNanos;
            Progress progress = snapshot(now);
            Double fraction = progress.fractionDone();
            log.info("{}: {} baseline + {} candidate documents{}, {} docs/s", label, progress.baselineDocs(),
                    progress.candidateDocs(), fraction == null ? "" : " (" + Math.round(fraction * 100) + "%)",
                    Math.round(progress.docsPerSecond()));
            listener.onProgress(progress);
        }
    }

    private Progress snapshot(long now) {
        Duration elapsed = Duration.ofNanos(now - startNanos);
        double seconds = Math.max(elapsed.toNanos() / 1e9, 1e-9);
        return new Progress(baselineDocs, candidateDocs, expectedDocs, elapsed,
                (baselineDocs + candidateDocs) / seconds);
    }

    private static String format(Duration duration) {
        return duration.toMillis() < 10_000 ? duration.toMillis() + " ms" : duration.toSeconds() + " s";
    }
}
