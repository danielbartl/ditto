package dev.jbaby.ditto.comparator.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * How much of the collections is read: everything, a random sample of keys, or (the default) whichever fits the
 * collection size.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ComparisonMode.Full.class, name = "FULL"),
        @JsonSubTypes.Type(value = ComparisonMode.Sample.class, name = "SAMPLE"),
        @JsonSubTypes.Type(value = ComparisonMode.Auto.class, name = "AUTO")
})
public sealed interface ComparisonMode {

    /** One streaming merge-join pass over both collections. */
    record Full() implements ComparisonMode {
    }

    /**
     * {@code size} random keys from the baseline (to measure matched, changed and removed documents) and
     * {@code size} random keys from the candidate (to measure added documents).
     */
    record Sample(int size) implements ComparisonMode {

        public Sample {
            if (size <= 0) {
                throw new IllegalArgumentException("sample size must be positive, was " + size);
            }
        }
    }

    /**
     * FULL if neither collection has more than {@code ditto.full-scan-limit} documents (default 5,000,000),
     * otherwise SAMPLE with {@code ditto.sample.size} keys. The choice is recorded in the report
     * ({@code run.decisions}), and the report's settings show the mode that ran.
     */
    record Auto() implements ComparisonMode {
    }

    static ComparisonMode auto() {
        return new Auto();
    }

    static ComparisonMode full() {
        return new Full();
    }

    static ComparisonMode sample(int size) {
        return new Sample(size);
    }
}
