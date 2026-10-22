package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

/**
 * SAMPLE mode:
 * <ol>
 * <li>sample keys from the baseline and look up the candidate documents with the same keys in batches: matched
 * (unchanged/changed) or removed</li>
 * <li>sample keys from the candidate and check which exist in the baseline: added</li>
 * </ol>
 * Structure is profiled on the baseline sample and its candidate counterparts (plus added documents), so rare paths
 * do not show up as vanished just because a different random subset was drawn on each side.
 */
public final class SampleComparator {

    public ScanResult compare(DocumentSource baseline, DocumentSource candidate, ComparisonSettings settings,
                              int sampleSize, ScanAccumulator accumulator, ProgressReporter progress) {
        String keyField = settings.keyField();
        int batchSize = settings.tuning().sampleLookupBatchSize();

        NavigableMap<BsonValue, SourceDocument> baselineSample = baseline.randomByKey(keyField, sampleSize);
        for (List<Map.Entry<BsonValue, SourceDocument>> batch : batches(baselineSample, batchSize)) {
            Map<BsonValue, SourceDocument> counterparts = candidate.findByKeys(keyField, keys(batch), false);
            for (Map.Entry<BsonValue, SourceDocument> entry : batch) {
                SourceDocument match = counterparts.get(entry.getKey());
                progress.baselineRead();
                if (match != null) {
                    progress.candidateRead();
                    accumulator.matched(entry.getKey(), entry.getValue(), match);
                } else {
                    accumulator.removed(entry.getKey(), entry.getValue());
                }
            }
        }

        NavigableMap<BsonValue, SourceDocument> candidateSample = candidate.randomByKey(keyField, sampleSize);
        for (List<Map.Entry<BsonValue, SourceDocument>> batch : batches(candidateSample, batchSize)) {
            Map<BsonValue, SourceDocument> existing = baseline.findByKeys(keyField, keys(batch), true);
            for (Map.Entry<BsonValue, SourceDocument> entry : batch) {
                progress.candidateRead();
                if (!existing.containsKey(entry.getKey())) {
                    accumulator.added(entry.getKey(), entry.getValue());
                }
            }
        }
        progress.finish();
        return accumulator.result(progress.baselineDocs(), progress.candidateDocs(), candidateSample.size());
    }

    private static List<List<Map.Entry<BsonValue, SourceDocument>>> batches(
            NavigableMap<BsonValue, SourceDocument> sample, int batchSize) {
        List<List<Map.Entry<BsonValue, SourceDocument>>> batches = new ArrayList<>();
        List<Map.Entry<BsonValue, SourceDocument>> current = new ArrayList<>(batchSize);
        for (Map.Entry<BsonValue, SourceDocument> entry : sample.entrySet()) {
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

    private static List<BsonValue> keys(List<Map.Entry<BsonValue, SourceDocument>> batch) {
        return batch.stream().map(Map.Entry::getKey).toList();
    }
}
