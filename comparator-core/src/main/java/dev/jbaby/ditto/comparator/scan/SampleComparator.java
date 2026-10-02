package dev.jbaby.ditto.comparator.scan;

import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

/**
 * SAMPLE mode: random keys from both collections, matched against the other side by key lookups.
 */
public final class SampleComparator {

    public ScanResult compare(CollectionHandle baseline, CollectionHandle candidate, ComparisonSettings settings,
                              int sampleSize, ScanAccumulator accumulator, ProgressReporter progress) {
        throw new UnsupportedOperationException("SAMPLE mode is not implemented yet");
    }
}
