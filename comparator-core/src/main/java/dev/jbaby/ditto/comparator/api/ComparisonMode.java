package dev.jbaby.ditto.comparator.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * How much of the collections is read: everything, or a random sample of keys.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ComparisonMode.Full.class, name = "FULL"),
        @JsonSubTypes.Type(value = ComparisonMode.Sample.class, name = "SAMPLE")
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

    static ComparisonMode full() {
        return new Full();
    }

    static ComparisonMode sample(int size) {
        return new Sample(size);
    }
}
