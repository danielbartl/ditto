package dev.jbaby.ditto.comparator.structure;

import java.util.EnumMap;
import java.util.Map;

import org.bson.BsonType;

/**
 * Immutable structure histogram of one collection (or sample).
 *
 * @param documents            documents profiled
 * @param paths                per path: documents containing it and documents per BSON type
 * @param capReached           whether paths beyond {@code maxTrackedPaths} were dropped
 * @param untrackedOccurrences occurrences of dropped paths
 */
public record StructureProfile(long documents, Map<String, PathStat> paths, boolean capReached,
                               long untrackedOccurrences) {

    public StructureProfile {
        paths = Map.copyOf(paths);
    }

    public static StructureProfile empty() {
        return new StructureProfile(0, Map.of(), false, 0);
    }

    /** Fraction of documents containing {@code path}. */
    public double presence(String path) {
        PathStat stat = paths.get(path);
        return stat == null || documents == 0 ? 0.0 : (double) stat.documents() / documents;
    }

    /**
     * @param documents documents containing the path
     * @param types     documents per BSON type of the path (a document may count for several types, e.g. via arrays)
     */
    public record PathStat(long documents, Map<BsonType, Long> types) {

        public PathStat {
            types = types.isEmpty() ? Map.of() : new EnumMap<>(types);
        }
    }
}
