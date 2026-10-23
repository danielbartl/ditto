package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.key.BsonKeyOrder;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

/**
 * FULL mode for sources that are not read in key order, e.g. OpenSearch, which cannot sort by {@code _id}:
 * <ol>
 * <li>scan the baseline and look up each batch of its keys in the candidate: matched (unchanged/changed) or
 * removed</li>
 * <li>scan the candidate's keys and look up each batch in the baseline, keys only; fetch the candidate documents not
 * found there: added</li>
 * </ol>
 * Memory use is bounded by the batches, independent of the collection size. A key occurring twice is detected when a
 * lookup finds it twice or a scan batch holds it twice.
 */
public final class LookupJoinComparator {

    public ScanResult compare(DocumentSource baseline, DocumentSource candidate, ComparisonSettings settings,
                              ScanAccumulator accumulator, ProgressReporter progress) {
        String keyField = settings.keyField();
        ComparisonSettings.Tuning tuning = settings.tuning();
        int lookupBatchSize = tuning.sampleLookupBatchSize();

        try (DocumentCursor cursor = baseline.scan(keyField, tuning.batchSize(), tuning.noCursorTimeout(), false)) {
            for (var batch = nextBatch(cursor, baseline, lookupBatchSize); !batch.isEmpty();
                 batch = nextBatch(cursor, baseline, lookupBatchSize)) {
                Map<BsonValue, SourceDocument> counterparts =
                        candidate.findByKeys(keyField, List.copyOf(batch.keySet()), false);
                List<BsonValue> removed = new ArrayList<>();
                batch.forEach((key, document) -> {
                    progress.baselineRead();
                    SourceDocument match = counterparts.get(key);
                    if (match != null) {
                        progress.candidateRead();
                        accumulator.matched(key, document, match);
                    } else {
                        removed.add(key);
                        accumulator.removed(key, document);
                    }
                });
                // a matched key is looked up in the baseline below, which finds its duplicates; a removed one isn't
                baseline.requireUniqueKeys(keyField, removed);
            }
        }

        try (DocumentCursor cursor = candidate.scan(keyField, tuning.batchSize(), tuning.noCursorTimeout(), true)) {
            for (var batch = nextBatch(cursor, candidate, lookupBatchSize); !batch.isEmpty();
                 batch = nextBatch(cursor, candidate, lookupBatchSize)) {
                Map<BsonValue, SourceDocument> existing =
                        baseline.findByKeys(keyField, List.copyOf(batch.keySet()), true);
                List<BsonValue> added = new ArrayList<>();
                batch.keySet().stream().filter(key -> !existing.containsKey(key)).forEach(added::add);
                if (added.isEmpty()) {
                    continue;
                }
                Map<BsonValue, SourceDocument> documents = candidate.findByKeys(keyField, added, false);
                for (BsonValue key : added) {
                    SourceDocument document = documents.get(key);
                    // null: deleted since the scan read its key
                    if (document != null) {
                        progress.candidateRead();
                        accumulator.added(key, document);
                    }
                }
            }
        }
        progress.finish();
        return accumulator.result(progress.baselineDocs(), progress.candidateDocs(), 0);
    }

    /** Up to {@code size} documents of the cursor by key, empty at its end. */
    private static NavigableMap<BsonValue, SourceDocument> nextBatch(DocumentCursor cursor, DocumentSource source,
                                                                      int size) {
        NavigableMap<BsonValue, SourceDocument> batch = new TreeMap<>(BsonKeyOrder.INSTANCE);
        while (batch.size() < size && cursor.hasNext()) {
            KeyedDocument document = cursor.next();
            if (batch.put(document.key(), document.document()) != null) {
                throw new ComparisonException("Duplicate key " + KeyRef.of(document.key()).value() + " in " + source
                        + ": the key field must be unique");
            }
        }
        return batch;
    }
}
