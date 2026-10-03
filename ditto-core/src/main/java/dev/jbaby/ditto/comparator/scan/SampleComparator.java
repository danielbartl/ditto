package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.bson.BsonValue;
import org.bson.RawBsonDocument;

import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.key.BsonKeyOrder;
import dev.jbaby.ditto.comparator.key.KeyInspector;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

/**
 * SAMPLE mode:
 * <ol>
 * <li>{@code $sample} keys from the baseline and look up the candidate documents with the same keys in batches
 * ({@code $in}): matched (unchanged/changed) or removed</li>
 * <li>{@code $sample} keys from the candidate and check which exist in the baseline: added</li>
 * </ol>
 * Structure is profiled on the baseline sample and its candidate counterparts (plus added documents), so rare paths
 * do not show up as vanished just because a different random subset was drawn on each side.
 */
public final class SampleComparator {

    public ScanResult compare(CollectionHandle baseline, CollectionHandle candidate, ComparisonSettings settings,
                              int sampleSize, ScanAccumulator accumulator, ProgressReporter progress) {
        String keyField = settings.keyField();
        int batchSize = settings.tuning().sampleLookupBatchSize();

        NavigableMap<BsonValue, RawBsonDocument> baselineSample = sample(baseline, keyField, sampleSize);
        for (List<Map.Entry<BsonValue, RawBsonDocument>> batch : batches(baselineSample, batchSize)) {
            Map<BsonValue, RawBsonDocument> counterparts = lookup(candidate, keyField, keys(batch), false);
            for (Map.Entry<BsonValue, RawBsonDocument> entry : batch) {
                RawBsonDocument match = counterparts.get(entry.getKey());
                progress.baselineRead();
                if (match != null) {
                    progress.candidateRead();
                    accumulator.matched(entry.getKey(), entry.getValue(), match);
                } else {
                    accumulator.removed(entry.getKey(), entry.getValue());
                }
            }
        }

        NavigableMap<BsonValue, RawBsonDocument> candidateSample = sample(candidate, keyField, sampleSize);
        for (List<Map.Entry<BsonValue, RawBsonDocument>> batch : batches(candidateSample, batchSize)) {
            Map<BsonValue, RawBsonDocument> existing = lookup(baseline, keyField, keys(batch), true);
            for (Map.Entry<BsonValue, RawBsonDocument> entry : batch) {
                progress.candidateRead();
                if (!existing.containsKey(entry.getKey())) {
                    accumulator.added(entry.getKey(), entry.getValue());
                }
            }
        }
        progress.finish();
        return accumulator.result(progress.baselineDocs(), progress.candidateDocs(), candidateSample.size());
    }

    /** Random documents by key, sorted by key and without duplicates ({@code $sample} may repeat documents). */
    private static NavigableMap<BsonValue, RawBsonDocument> sample(CollectionHandle handle, String keyField,
                                                                   int size) {
        NavigableMap<BsonValue, RawBsonDocument> sample = new TreeMap<>(BsonKeyOrder.INSTANCE);
        handle.collection().aggregate(List.of(Aggregates.sample(size))).allowDiskUse(true)
                .forEach(document -> sample.put(handle.keyOf(document, keyField), document));
        return sample;
    }

    /**
     * Documents of {@code handle} with the given keys, by key.
     *
     * @param keysOnly fetch only the key field
     */
    private static Map<BsonValue, RawBsonDocument> lookup(CollectionHandle handle, String keyField,
                                                          List<BsonValue> keys, boolean keysOnly) {
        Map<BsonValue, RawBsonDocument> found = new TreeMap<>(BsonKeyOrder.INSTANCE);
        var find = handle.collection().find(Filters.in(keyField, keys)).collation(KeyInspector.SIMPLE);
        if (keysOnly) {
            find = find.projection(Projections.include(keyField));
        }
        find.forEach(document -> {
            BsonValue key = handle.keyOf(document, keyField);
            if (found.put(key, document) != null) {
                throw new ComparisonException("Duplicate key " + KeyRef.of(key).value() + " in " + handle
                        + ": the key field must be unique");
            }
        });
        return found;
    }

    private static List<List<Map.Entry<BsonValue, RawBsonDocument>>> batches(
            NavigableMap<BsonValue, RawBsonDocument> sample, int batchSize) {
        List<List<Map.Entry<BsonValue, RawBsonDocument>>> batches = new ArrayList<>();
        List<Map.Entry<BsonValue, RawBsonDocument>> current = new ArrayList<>(batchSize);
        for (Map.Entry<BsonValue, RawBsonDocument> entry : sample.entrySet()) {
            current.add(entry);
            if (current.size() == batchSize) {
                batches.add(current);
                current = new ArrayList<>(batchSize);
            }
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private static List<BsonValue> keys(List<Map.Entry<BsonValue, RawBsonDocument>> batch) {
        return batch.stream().map(Map.Entry::getKey).toList();
    }
}
