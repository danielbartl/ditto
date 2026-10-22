package dev.jbaby.ditto.comparator.structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.bson.BsonType;

import dev.jbaby.ditto.comparator.api.ComparisonReport.PathPresence;
import dev.jbaby.ditto.comparator.api.ComparisonReport.StructureMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShare;
import dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShift;
import dev.jbaby.ditto.comparator.api.Thresholds.StructureThresholds;

/**
 * Compares the structure profiles of baseline and candidate: new and vanished paths, type shifts and presence deltas.
 */
public final class StructureDiff {

    private StructureDiff() {
    }

    public static StructureMetrics compare(StructureProfile baseline, StructureProfile candidate,
                                           StructureThresholds thresholds) {
        List<PathPresence> newPaths = new ArrayList<>();
        List<PathPresence> missingPaths = new ArrayList<>();
        List<TypeShift> typeShifts = new ArrayList<>();
        List<PathPresence> presenceDeltas = new ArrayList<>();

        Set<String> allPaths = new TreeSet<>(baseline.paths().keySet());
        allPaths.addAll(candidate.paths().keySet());
        for (String path : allPaths) {
            StructureProfile.PathStat inBaseline = baseline.paths().get(path);
            StructureProfile.PathStat inCandidate = candidate.paths().get(path);
            PathPresence presence = PathPresence.of(path,
                    inBaseline == null ? 0 : inBaseline.documents(), baseline.documents(),
                    inCandidate == null ? 0 : inCandidate.documents(), candidate.documents());
            if (inBaseline == null) {
                newPaths.add(presence);
            } else if (inCandidate == null) {
                missingPaths.add(presence);
            } else {
                if (Math.abs(presence.presenceDelta()) > thresholds.presenceDelta()) {
                    presenceDeltas.add(presence);
                }
                if (isTypeShift(inBaseline, inCandidate, thresholds.typeShareDelta())) {
                    typeShifts.add(new TypeShift(path, shares(inBaseline), shares(inCandidate)));
                }
            }
        }
        presenceDeltas.sort(Comparator.comparingDouble((PathPresence p) -> -Math.abs(p.presenceDelta()))
                .thenComparing(PathPresence::path));

        return new StructureMetrics(baseline.documents(), candidate.documents(), baseline.paths().size(),
                candidate.paths().size(), newPaths, missingPaths, typeShifts, presenceDeltas,
                baseline.capReached() || candidate.capReached(),
                baseline.untrackedOccurrences() + candidate.untrackedOccurrences());
    }

    /** A type appeared or disappeared, or a type's share among the documents having the path moved too much. */
    static boolean isTypeShift(StructureProfile.PathStat baseline, StructureProfile.PathStat candidate,
                               double typeShareDelta) {
        if (!baseline.types().keySet().equals(candidate.types().keySet())) {
            return true;
        }
        Set<BsonType> types = EnumSet.noneOf(BsonType.class);
        types.addAll(baseline.types().keySet());
        for (BsonType type : types) {
            double delta = share(candidate, type) - share(baseline, type);
            if (Math.abs(delta) > typeShareDelta) {
                return true;
            }
        }
        return false;
    }

    private static double share(StructureProfile.PathStat stat, BsonType type) {
        return stat.documents() == 0 ? 0.0 : (double) stat.types().getOrDefault(type, 0L) / stat.documents();
    }

    private static List<TypeShare> shares(StructureProfile.PathStat stat) {
        return stat.types().entrySet().stream()
                .sorted(Map.Entry.<BsonType, Long>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().name()))
                .map(entry -> new TypeShare(entry.getKey().name(), entry.getValue(), share(stat, entry.getKey())))
                .toList();
    }
}
