package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.BsonValue;
import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.key.KeyOrderGuard;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

/**
 * Matched-only comparison: reads the smaller collection (fully, or a random sample of it) and looks up its keys in
 * the other one in batches. Only documents found on both sides are compared and profiled. Documents of
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
    public ScanResult compare(DocumentSource baseline, DocumentSource candidate, ComparisonSettings settings,
                              boolean readBaseline, @Nullable Integer sampleSize, ScanAccumulator accumulator,
                              ProgressReporter progress) {
        Lookup lookup = new Lookup(readBaseline ? candidate : baseline, settings.keyField(), readBaseline,
                settings.tuning().sampleLookupBatchSize(), accumulator, progress);
        DocumentSource read = readBaseline ? baseline : candidate;
        if (sampleSize == null) {
            ComparisonSettings.Tuning tuning = settings.tuning();
            // detects duplicate keys, which only a key-ordered scan can do on the fly
            KeyOrderGuard guard = read.keyOrdered() ? new KeyOrderGuard(read.toString()) : null;
            try (DocumentCursor cursor =
                         read.scan(settings.keyField(), tuning.batchSize(), tuning.noCursorTimeout(), false)) {
                while (cursor.hasNext()) {
                    KeyedDocument document = cursor.next();
                    if (guard != null) {
                        guard.accept(document.key());
                    }
                    lookup.add(document.key(), document.document());
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

        private final DocumentSource other;
        private final String keyField;
        private final boolean readBaseline;
        private final int batchSize;
        private final ScanAccumulator accumulator;
        private final ProgressReporter progress;
        private final List<Map.Entry<BsonValue, SourceDocument>> batch;

        Lookup(DocumentSource other, String keyField, boolean readBaseline, int batchSize,
               ScanAccumulator accumulator, ProgressReporter progress) {
            this.other = other;
            this.keyField = keyField;
            this.readBaseline = readBaseline;
            this.batchSize = batchSize;
            this.accumulator = accumulator;
            this.progress = progress;
            this.batch = new ArrayList<>(batchSize);
        }

        void add(BsonValue key, SourceDocument document) {
            batch.add(Map.entry(key, document));
            if (batch.size() == batchSize) {
                flush();
            }
        }

        void flush() {
            if (batch.isEmpty()) {
                return;
            }
            Map<BsonValue, SourceDocument> counterparts =
                    other.findByKeys(keyField, batch.stream().map(Map.Entry::getKey).toList(), false);
            for (Map.Entry<BsonValue, SourceDocument> entry : batch) {
                BsonValue key = entry.getKey();
                SourceDocument document = entry.getValue();
                SourceDocument counterpart = counterparts.get(key);
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
