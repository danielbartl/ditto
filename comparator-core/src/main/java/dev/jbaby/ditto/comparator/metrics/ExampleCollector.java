package dev.jbaby.ditto.comparator.metrics;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.api.KeyRef;

/**
 * Keeps the first {@code max} keys offered, in key order of the scan. Not thread-safe.
 */
public final class ExampleCollector {

    private final int max;
    private final List<KeyRef> keys = new ArrayList<>();

    public ExampleCollector(int max) {
        this.max = max;
    }

    public void offer(BsonValue key) {
        if (keys.size() < max) {
            keys.add(KeyRef.of(key));
        }
    }

    public List<KeyRef> keys() {
        return List.copyOf(keys);
    }
}
