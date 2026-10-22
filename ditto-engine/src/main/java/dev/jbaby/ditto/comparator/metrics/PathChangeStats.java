package dev.jbaby.ditto.comparator.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.ValueChange;

/**
 * Counts, per changed path, the documents in which it changed, with example keys and before/after value examples.
 * At most {@code maxTrackedPaths} distinct paths are tracked. Not thread-safe.
 */
public final class PathChangeStats {

    private final int maxTrackedPaths;
    private final int maxExamples;
    private final int maxValueExamples;
    private final Map<String, Counter> counters = new HashMap<>();
    private long untrackedOccurrences;

    public PathChangeStats(int maxTrackedPaths, int maxExamples, int maxValueExamples) {
        this.maxTrackedPaths = maxTrackedPaths;
        this.maxExamples = maxExamples;
        this.maxValueExamples = maxValueExamples;
    }

    /**
     * Records one changed document with its changed paths.
     *
     * @return the paths that still want value examples, see {@link #addValueExample}
     */
    public Set<String> record(BsonValue key, Collection<String> changedPaths) {
        Set<String> wantValues = new LinkedHashSet<>();
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
            if (counter.valueExamples.size() < maxValueExamples) {
                wantValues.add(path);
            }
        }
        return wantValues;
    }

    public void addValueExample(String path, ValueChange change) {
        Counter counter = counters.get(path);
        if (counter != null && counter.valueExamples.size() < maxValueExamples) {
            counter.valueExamples.add(change);
        }
    }

    /** All tracked paths, most frequently changed first. */
    public List<PathChangeCount> sorted() {
        return counters.entrySet().stream()
                .map(entry -> new PathChangeCount(entry.getKey(), entry.getValue().documents,
                        entry.getValue().examples.keys(), List.copyOf(entry.getValue().valueExamples)))
                .sorted(Comparator.comparingLong(PathChangeCount::documents).reversed()
                        .thenComparing(PathChangeCount::path))
                .toList();
    }

    public long untrackedOccurrences() {
        return untrackedOccurrences;
    }

    /**
     * @param path          changed path
     * @param documents     documents in which it changed
     * @param examples      keys of some of these documents
     * @param valueExamples before/after values of some of these documents
     */
    public record PathChangeCount(String path, long documents, List<KeyRef> examples,
                                  List<ValueChange> valueExamples) {
    }

    private static final class Counter {
        final ExampleCollector examples;
        final List<ValueChange> valueExamples = new ArrayList<>();
        long documents;

        Counter(ExampleCollector examples) {
            this.examples = examples;
        }
    }
}
