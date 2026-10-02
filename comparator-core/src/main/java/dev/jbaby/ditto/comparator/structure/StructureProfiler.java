package dev.jbaby.ditto.comparator.structure;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.bson.BsonDocument;
import org.bson.BsonType;

import dev.jbaby.ditto.comparator.flatten.Flattener;
import dev.jbaby.ditto.comparator.flatten.PathType;

/**
 * Builds the structure histogram of one collection: for every path the number of documents containing it, and per
 * BSON type the number of documents in which the path has that type. Each entry is counted once per document.
 * At most {@code maxTrackedPaths} distinct paths are tracked; occurrences of further paths are only counted.
 * Not thread-safe.
 */
public final class StructureProfiler {

    private final Flattener flattener;
    private final int maxTrackedPaths;
    private final Map<String, MutablePathStat> paths = new HashMap<>();
    private long documents;
    private long untrackedOccurrences;

    public StructureProfiler(Flattener flattener, int maxTrackedPaths) {
        this.flattener = flattener;
        this.maxTrackedPaths = maxTrackedPaths;
    }

    public void profile(BsonDocument document) {
        documents++;
        Set<PathType> pathTypes = flattener.pathTypes(document);
        Set<String> seenPaths = new HashSet<>();
        for (PathType pathType : pathTypes) {
            MutablePathStat stat = paths.get(pathType.path());
            if (stat == null) {
                if (paths.size() >= maxTrackedPaths) {
                    untrackedOccurrences++;
                    continue;
                }
                stat = new MutablePathStat();
                paths.put(pathType.path(), stat);
            }
            if (seenPaths.add(pathType.path())) {
                stat.documents++;
            }
            stat.types.merge(pathType.type(), 1L, Long::sum);
        }
    }

    public StructureProfile snapshot() {
        Map<String, StructureProfile.PathStat> stats = new HashMap<>(paths.size() * 2);
        paths.forEach((path, stat) -> stats.put(path, new StructureProfile.PathStat(stat.documents, stat.types)));
        return new StructureProfile(documents, stats, untrackedOccurrences > 0, untrackedOccurrences);
    }

    private static final class MutablePathStat {
        long documents;
        final Map<BsonType, Long> types = new EnumMap<>(BsonType.class);
    }
}
