package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.jspecify.annotations.Nullable;

import com.mongodb.client.MongoCursor;

import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.key.KeyOrderGuard;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

/**
 * Matched-only comparison: reads the smaller collection (fully, or a {@code $sample} of it) and looks up its keys in
 * the other one in batches ({@code $in}). Only documents found on both sides are compared and profiled. Documents of
 * the read side without counterpart are counted as removed (baseline read) or added (candidate read); the other
 * side's unmatched documents are never read. Cost depends on the smaller collection only, so a small test
 * collection is cheap to check against a large one.
 */
public final class MatchedOnlyComparator {

    /** Whether the baseline is the side that is read; the candidate is read if it is smaller. */
    public static boolean readsBaseline(long baselineCount, long candidateCount) {
        return baselineCount <= candidateCount;
    }

    /**
     * @param readBaseline read the baseline and look up in the candidate, or the other way round
     * @param sampleSize   documents sampled from the read side, {@code null} to read it fully
     */
    public ScanResult compare(CollectionHandle baseline, CollectionHandle candidate, ComparisonSettings settings,
                              boolean readBaseline, @Nullable Integer sampleSize, ScanAccumulator accumulator,
                              ProgressReporter progress) {
        Lookup lookup = new Lookup(readBaseline ? candidate : baseline, settings.keyField(), readBaseline,
                settings.tuning().sampleLookupBatchSize(), accumulator, progress);
        CollectionHandle read = readBaseline ? baseline : candidate;
        if (sampleSize == null) {
            ComparisonSettings.Tuning tuning = settings.tuning();
            KeyOrderGuard guard = new KeyOrderGuard(read.toString());
            try (MongoCursor<RawBsonDocument> cursor =
                         read.sortedByKey(settings.keyField(), tuning.batchSize(), tuning.noCursorTimeout()).cursor()) {
                while (cursor.hasNext()) {
                    RawBsonDocument document = cursor.next();
                    BsonValue key = read.keyOf(document, settings.keyField());
                    guard.accept(key);
                    lookup.add(key, document);
                }
            }
        } else {
            read.randomByKey(settings.keyField(), sampleSize).forEach(lookup::add);
        }
        lookup.flush();
        progress.finish();
        return accumulator.result(progress.baselineDocs(), progress.candidateDocs(), 0);
    }

    /** Collects documents of the read side and compares them batch by batch with their counterparts. */
    private static final class Lookup {

        private final CollectionHandle other;
        private final String keyField;
        private final boolean readBaseline;
        private final int batchSize;
        private final ScanAccumulator accumulator;
        private final ProgressReporter progress;
        private final List<Map.Entry<BsonValue, RawBsonDocument>> batch;

        Lookup(CollectionHandle other, String keyField, boolean readBaseline, int batchSize,
               ScanAccumulator accumulator, ProgressReporter progress) {
            this.other = other;
            this.keyField = keyField;
            this.readBaseline = readBaseline;
            this.batchSize = batchSize;
            this.accumulator = accumulator;
            this.progress = progress;
            this.batch = new ArrayList<>(batchSize);
        }

        void add(BsonValue key, RawBsonDocument document) {
            batch.add(Map.entry(key, document));
            if (batch.size() == batchSize) {
                flush();
            }
        }

        void flush() {
            if (batch.isEmpty()) {
                return;
            }
            Map<BsonValue, RawBsonDocument> counterparts =
                    other.findByKeys(keyField, batch.stream().map(Map.Entry::getKey).toList(), false);
            for (Map.Entry<BsonValue, RawBsonDocument> entry : batch) {
                BsonValue key = entry.getKey();
                RawBsonDocument document = entry.getValue();
                RawBsonDocument counterpart = counterparts.get(key);
                read(readBaseline);
                if (counterpart != null) {
                    read(!readBaseline);
                    accumulator.matched(key, readBaseline ? document : counterpart,
                            readBaseline ? counterpart : document);
                } else if (readBaseline) {
                    accumulator.removed(key, document);
                } else {
                    accumulator.added(key, document);
                }
            }
            batch.clear();
        }

        private void read(boolean baselineSide) {
            if (baselineSide) {
                progress.baselineRead();
            } else {
                progress.candidateRead();
            }
        }
    }
}
