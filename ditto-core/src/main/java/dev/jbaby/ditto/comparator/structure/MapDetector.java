package dev.jbaby.ditto.comparator.structure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.path.PathPattern;
import dev.jbaby.ditto.comparator.path.PathRules;
import dev.jbaby.ditto.comparator.path.Paths;

/**
 * Finds objects used as maps with dynamic keys (e.g. {@code attributes.color}, {@code attributes.size}, ...) in a
 * sample of documents, so they can be treated as wildcard paths without configuration.
 * <p>
 * An object is a map if, across the sample, it has at least {@code minDistinctKeys} different field names and at
 * least {@link #SPREAD} times as many different names as it has fields per document on average. A record-like object
 * with optional fields has about as many distinct names as fields per document; a map has far more. Maps inside maps
 * are found in further rounds once their parent is collapsed. Thread-safe.
 */
public final class MapDetector {

    /** Distinct field names must exceed the average number of fields per document by this factor. */
    static final double SPREAD = 3.0;
    private static final int MAX_ROUNDS = 3;
    private static final int MAX_NAMES = 10_000;

    private final int minDistinctKeys;

    public MapDetector(int minDistinctKeys) {
        this.minDistinctKeys = minDistinctKeys;
    }

    /**
     * A detected map.
     *
     * @param pattern         wildcard pattern, e.g. {@code attributes.*}
     * @param distinctKeys    different field names seen
     * @param averageKeys     fields per occurrence of the object on average
     * @param documents       sampled documents containing the object
     */
    public record DetectedMap(String pattern, int distinctKeys, double averageKeys, long documents) {
    }

    /**
     * @param documents         sample to inspect
     * @param ignored           ignored path patterns
     * @param orderSensitive    order-sensitive path patterns
     * @param knownWildcards    wildcard patterns already configured
     */
    public List<DetectedMap> detect(Collection<BsonDocument> documents, List<String> ignored,
                                    List<String> orderSensitive, List<String> knownWildcards) {
        List<DetectedMap> detected = new ArrayList<>();
        Set<String> wildcards = new LinkedHashSet<>(knownWildcards);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            PathRules rules = PathRules.compile(ignored, orderSensitive, List.copyOf(wildcards));
            Map<String, ObjectStats> objects = new HashMap<>();
            for (BsonDocument document : documents) {
                Set<String> seen = new HashSet<>();
                walkObject(document, rules.root(), Paths.ROOT, objects, seen);
            }
            List<DetectedMap> found = objects.entrySet().stream()
                    .filter(entry -> isMap(entry.getValue()))
                    .map(entry -> entry.getValue().toDetected(entry.getKey() + "." + Paths.WILDCARD))
                    .filter(map -> isAddressable(map.pattern()) && !wildcards.contains(map.pattern()))
                    .sorted(Comparator.comparing(DetectedMap::pattern))
                    .toList();
            if (found.isEmpty()) {
                break;
            }
            found.forEach(map -> wildcards.add(map.pattern()));
            detected.addAll(found);
        }
        return detected;
    }

    private boolean isMap(ObjectStats stats) {
        int distinct = stats.names.size();
        return distinct >= minDistinctKeys && distinct >= SPREAD * stats.averageKeys();
    }

    private void walkObject(BsonDocument object, PathRules.State state, String path, Map<String, ObjectStats> objects,
                            Set<String> seen) {
        if (!path.isEmpty() && !state.collapsesChildren()) {
            ObjectStats stats = objects.computeIfAbsent(path, key -> new ObjectStats());
            if (seen.add(path)) {
                stats.documents++;
            }
            stats.occurrences++;
            stats.keys += object.size();
            for (String name : object.keySet()) {
                if (stats.names.size() < MAX_NAMES) {
                    stats.names.add(name);
                }
            }
        }
        for (Map.Entry<String, BsonValue> entry : object.entrySet()) {
            PathRules.State child = state.field(entry.getKey());
            if (!child.ignored()) {
                walkValue(entry.getValue(), child, Paths.field(path, entry.getKey(), state), objects, seen);
            }
        }
    }

    private void walkValue(BsonValue value, PathRules.State state, String path, Map<String, ObjectStats> objects,
                           Set<String> seen) {
        if (value.isDocument()) {
            walkObject(value.asDocument(), state, path, objects, seen);
        } else if (value.isArray()) {
            PathRules.State element = state.element();
            if (!element.ignored()) {
                for (BsonValue item : value.asArray()) {
                    walkValue(item, element, Paths.element(path), objects, seen);
                }
            }
        }
    }

    private static boolean isAddressable(String pattern) {
        try {
            PathPattern.parse(pattern);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final class ObjectStats {
        final Set<String> names = new HashSet<>();
        long documents;
        long occurrences;
        long keys;

        /** Fields per occurrence; an object inside an array occurs once per element. */
        double averageKeys() {
            return occurrences == 0 ? 0 : (double) keys / occurrences;
        }

        DetectedMap toDetected(String pattern) {
            return new DetectedMap(pattern, names.size(), averageKeys(), documents);
        }
    }
}
