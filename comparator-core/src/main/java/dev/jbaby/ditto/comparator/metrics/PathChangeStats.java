package dev.jbaby.ditto.comparator.metrics;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.api.KeyRef;

/**
 * Counts, per changed path, the documents in which it changed, with example keys. At most {@code maxTrackedPaths}
 * distinct paths are tracked. Not thread-safe.
 */
public final class PathChangeStats {

    private final int maxTrackedPaths;
    private final int maxExamples;
    private final Map<String, Counter> counters = new HashMap<>();
    private long untrackedOccurrences;

    public PathChangeStats(int maxTrackedPaths, int maxExamples) {
        this.maxTrackedPaths = maxTrackedPaths;
        this.maxExamples = maxExamples;
    }

    /** Records one changed document with its changed paths. */
    public void record(BsonValue key, Collection<String> changedPaths) {
        for (String path : changedPaths) {
            Counter counter = counters.get(path);
            if (counter == null) {
                if (counters.size() >= maxTrackedPaths) {
                    untrackedOccurrences++;
                    continue;
                }
                counter = new Counter(new ExampleCollector(maxExamples));
                counters.put(path, counter);
            }
            counter.documents++;
            counter.examples.offer(key);
        }
    }

    /** All tracked paths, most frequently changed first. */
    public List<PathChangeCount> sorted() {
        return counters.entrySet().stream()
                .map(entry -> new PathChangeCount(entry.getKey(), entry.getValue().documents,
                        entry.getValue().examples.keys()))
                .sorted(Comparator.comparingLong(PathChangeCount::documents).reversed()
                        .thenComparing(PathChangeCount::path))
                .toList();
    }

    public long untrackedOccurrences() {
        return untrackedOccurrences;
    }

    /**
     * @param path      changed path
     * @param documents documents in which it changed
     * @param examples  keys of some of these documents
     */
    public record PathChangeCount(String path, long documents, List<KeyRef> examples) {
    }

    private static final class Counter {
        final ExampleCollector examples;
        long documents;

        Counter(ExampleCollector examples) {
            this.examples = examples;
        }
    }
}
