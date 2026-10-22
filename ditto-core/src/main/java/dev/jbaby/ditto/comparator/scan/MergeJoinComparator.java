package dev.jbaby.ditto.comparator.scan;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.key.BsonKeyOrder;
import dev.jbaby.ditto.comparator.key.KeyOrderGuard;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

/**
 * FULL mode for {@linkplain DocumentSource#keyOrdered() key-ordered} sources: one streaming pass over both sides,
 * each sorted by key, advancing the cursor with the smaller key. Memory use is bounded by the cursor batches and the
 * accumulated statistics, independent of the collection size.
 */
public final class MergeJoinComparator {

    public ScanResult compare(DocumentSource baseline, DocumentSource candidate, ComparisonSettings settings,
                              ScanAccumulator accumulator, ProgressReporter progress) {
        String keyField = settings.keyField();
        ComparisonSettings.Tuning tuning = settings.tuning();
        try (DocumentCursor baselineCursor =
                     baseline.scan(keyField, tuning.batchSize(), tuning.noCursorTimeout(), false);
             DocumentCursor candidateCursor =
                     candidate.scan(keyField, tuning.batchSize(), tuning.noCursorTimeout(), false)) {
            Side left = new Side(baseline, baselineCursor);
            Side right = new Side(candidate, candidateCursor);
            left.advance();
            right.advance();
            while (left.current != null || right.current != null) {
                int order = left.current == null ? 1
                        : right.current == null ? -1
                        : BsonKeyOrder.INSTANCE.compare(left.current().key(), right.current().key());
                if (order == 0) {
                    accumulator.matched(left.current().key(), left.current().document(), right.current().document());
                    progress.baselineRead();
                    progress.candidateRead();
                    left.advance();
                    right.advance();
                } else if (order < 0) {
                    accumulator.removed(left.current().key(), left.current().document());
                    progress.baselineRead();
                    left.advance();
                } else {
                    accumulator.added(right.current().key(), right.current().document());
                    progress.candidateRead();
                    right.advance();
                }
            }
        }
        progress.finish();
        return accumulator.result(progress.baselineDocs(), progress.candidateDocs(), 0);
    }

    /** A cursor with its current document, verifying ascending key order. */
    private static final class Side {

        private final DocumentCursor cursor;
        private final KeyOrderGuard guard;
        private @Nullable KeyedDocument current;

        Side(DocumentSource source, DocumentCursor cursor) {
            this.cursor = cursor;
            this.guard = new KeyOrderGuard(source.toString());
        }

        void advance() {
            if (cursor.hasNext()) {
                current = cursor.next();
                guard.accept(current.key());
            } else {
                current = null;
            }
        }

        KeyedDocument current() {
            return Objects.requireNonNull(current);
        }
    }
}
