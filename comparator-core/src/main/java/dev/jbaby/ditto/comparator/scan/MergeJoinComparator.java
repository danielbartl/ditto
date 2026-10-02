package dev.jbaby.ditto.comparator.scan;

import java.util.Objects;

import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.jspecify.annotations.Nullable;

import com.mongodb.client.MongoCursor;

import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.key.BsonKeyOrder;
import dev.jbaby.ditto.comparator.key.KeyOrderGuard;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

/**
 * FULL mode: one streaming pass over both collections, each sorted by key, advancing the cursor with the smaller key.
 * Memory use is bounded by the cursor batches and the accumulated statistics, independent of the collection size.
 */
public final class MergeJoinComparator {

    public ScanResult compare(CollectionHandle baseline, CollectionHandle candidate, ComparisonSettings settings,
                              ScanAccumulator accumulator, ProgressReporter progress) {
        String keyField = settings.keyField();
        ComparisonSettings.Tuning tuning = settings.tuning();
        try (MongoCursor<RawBsonDocument> baselineCursor =
                     baseline.sortedByKey(keyField, tuning.batchSize(), tuning.noCursorTimeout()).cursor();
             MongoCursor<RawBsonDocument> candidateCursor =
                     candidate.sortedByKey(keyField, tuning.batchSize(), tuning.noCursorTimeout()).cursor()) {
            Side left = new Side(baseline, baselineCursor, keyField);
            Side right = new Side(candidate, candidateCursor, keyField);
            left.advance();
            right.advance();
            while (left.current != null || right.current != null) {
                int order = left.current == null ? 1
                        : right.current == null ? -1
                        : BsonKeyOrder.INSTANCE.compare(left.key(), right.key());
                if (order == 0) {
                    accumulator.matched(left.key(), left.document(), right.document());
                    progress.baselineRead();
                    progress.candidateRead();
                    left.advance();
                    right.advance();
                } else if (order < 0) {
                    accumulator.removed(left.key(), left.document());
                    progress.baselineRead();
                    left.advance();
                } else {
                    accumulator.added(right.key(), right.document());
                    progress.candidateRead();
                    right.advance();
                }
            }
        }
        progress.finish();
        return accumulator.result(progress.baselineDocs(), progress.candidateDocs(), 0);
    }

    /** A cursor with its current document and key, verifying ascending key order. */
    private static final class Side {

        private final CollectionHandle handle;
        private final MongoCursor<RawBsonDocument> cursor;
        private final String keyField;
        private final KeyOrderGuard guard;
        private @Nullable RawBsonDocument current;
        private @Nullable BsonValue currentKey;

        Side(CollectionHandle handle, MongoCursor<RawBsonDocument> cursor, String keyField) {
            this.handle = handle;
            this.cursor = cursor;
            this.keyField = keyField;
            this.guard = new KeyOrderGuard(handle.toString());
        }

        void advance() {
            if (cursor.hasNext()) {
                current = cursor.next();
                currentKey = handle.keyOf(current, keyField);
                guard.accept(currentKey);
            } else {
                current = null;
                currentKey = null;
            }
        }

        RawBsonDocument document() {
            return Objects.requireNonNull(current);
        }

        BsonValue key() {
            return Objects.requireNonNull(currentKey);
        }
    }
}
