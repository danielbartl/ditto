package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.bson.BsonValue;
import org.bson.RawBsonDocument;

import dev.jbaby.ditto.comparator.api.ComparisonSettings;
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

        NavigableMap<BsonValue, RawBsonDocument> baselineSample = baseline.randomByKey(keyField, sampleSize);
        for (List<Map.Entry<BsonValue, RawBsonDocument>> batch : batches(baselineSample, batchSize)) {
            Map<BsonValue, RawBsonDocument> counterparts = candidate.findByKeys(keyField, keys(batch), false);
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

        NavigableMap<BsonValue, RawBsonDocument> candidateSample = candidate.randomByKey(keyField, sampleSize);
        for (List<Map.Entry<BsonValue, RawBsonDocument>> batch : batches(candidateSample, batchSize)) {
            Map<BsonValue, RawBsonDocument> existing = baseline.findByKeys(keyField, keys(batch), true);
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
