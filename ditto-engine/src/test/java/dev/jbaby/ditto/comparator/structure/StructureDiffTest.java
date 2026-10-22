package dev.jbaby.ditto.comparator.structure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonReport.PathPresence;
import dev.jbaby.ditto.comparator.api.ComparisonReport.StructureMetrics;
import dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShare;
import dev.jbaby.ditto.comparator.api.Thresholds;
import dev.jbaby.ditto.comparator.flatten.Flattener;
import dev.jbaby.ditto.comparator.path.PathRules;

class StructureDiffTest {

    private static final Thresholds.StructureThresholds THRESHOLDS = Thresholds.DEFAULTS.structure();

    @Test
    void countsEachPathOncePerDocument() {
        var profile = profile(List.of("{items: [{p: 1}, {p: 2}, {p: 3.5}]}", "{items: []}"), 100);

        assertThat(profile.documents()).isEqualTo(2);
        assertThat(profile.paths().get("items").documents()).isEqualTo(2);
        assertThat(profile.paths().get("items[].p").documents()).isEqualTo(1);
        assertThat(profile.paths().get("items[].p").types())
                .containsEntry(org.bson.BsonType.INT32, 1L)
                .containsEntry(org.bson.BsonType.DOUBLE, 1L);
        assertThat(profile.presence("items[]")).isEqualTo(0.5);
    }

    @Test
    void capsTrackedPaths() {
        var profile = profile(List.of("{a: 1, b: 1, c: 1}", "{d: 1}"), 2);

        assertThat(profile.paths()).hasSize(2);
        assertThat(profile.capReached()).isTrue();
        assertThat(profile.untrackedOccurrences()).isEqualTo(2);
    }

    @Test
    void identicalStructureHasNoFindings() {
        var docs = List.of("{a: 1, b: 'x'}", "{a: 2, b: 'y', c: [1]}");
        StructureMetrics metrics = StructureDiff.compare(profile(docs, 100), profile(docs, 100), THRESHOLDS);

        assertThat(metrics.newPaths()).isEmpty();
        assertThat(metrics.missingPaths()).isEmpty();
        assertThat(metrics.typeShifts()).isEmpty();
        assertThat(metrics.presenceDeltas()).isEmpty();
        assertThat(metrics.baselinePaths()).isEqualTo(4);
    }

    @Test
    void detectsNewMissingShiftedAndLessPresentPaths() {
        var baseline = profile(List.of("{a: 1, old: 1, opt: 1}", "{a: 2, old: 2, opt: 1}", "{a: 3, old: 3}", "{a: 4, old: 4}"), 100);
        var candidate = profile(List.of("{a: 1.5, neu: 1}", "{a: 2, neu: 2}", "{a: 3}", "{a: 4}"), 100);

        StructureMetrics metrics = StructureDiff.compare(baseline, candidate, THRESHOLDS);

        assertThat(metrics.newPaths()).extracting(PathPresence::path).containsExactly("neu");
        assertThat(metrics.newPaths().getFirst().candidatePresence()).isEqualTo(0.5);
        assertThat(metrics.missingPaths()).extracting(PathPresence::path).containsExactly("old", "opt");
        assertThat(metrics.typeShifts()).singleElement().satisfies(shift -> {
            assertThat(shift.path()).isEqualTo("a");
            assertThat(shift.baseline()).containsExactly(new TypeShare("INT32", 4, 1.0));
            assertThat(shift.candidate()).containsExactly(new TypeShare("INT32", 3, 0.75), new TypeShare("DOUBLE", 1, 0.25));
        });
    }

    @Test
    void typeShareAndPresenceDeltasRespectThresholds() {
        var baseline = profile(repeat("{a: 1, b: 1}", 100), 100);
        var candidateDocs = new java.util.ArrayList<>(repeat("{a: 1, b: 1}", 94));
        candidateDocs.addAll(repeat("{a: 1}", 6));
        var candidate = profile(candidateDocs, 100);

        var metrics = StructureDiff.compare(baseline, candidate, THRESHOLDS);
        assertThat(metrics.presenceDeltas()).singleElement().satisfies(delta -> {
            assertThat(delta.path()).isEqualTo("b");
            assertThat(delta.presenceDelta()).isCloseTo(-0.06, org.assertj.core.data.Offset.offset(1e-9));
        });

        var lenient = new Thresholds.StructureThresholds(0.01, 0.10, 0.0);
        assertThat(StructureDiff.compare(baseline, candidate, lenient).presenceDeltas()).isEmpty();
    }

    @Test
    void emptyProfilesCompareCleanly() {
        var metrics = StructureDiff.compare(StructureProfile.empty(), profile(List.of("{a: 1}"), 10), THRESHOLDS);

        assertThat(metrics.newPaths()).extracting(PathPresence::path).containsExactly("a");
        assertThat(metrics.newPaths().getFirst().baselinePresence()).isZero();
    }

    private static StructureProfile profile(List<String> documents, int maxPaths) {
        var profiler = new StructureProfiler(new Flattener(PathRules.NONE, false), maxPaths);
        documents.forEach(json -> profiler.profile(BsonDocument.parse(json)));
        return profiler.snapshot();
    }

    private static List<String> repeat(String json, int times) {
        return java.util.Collections.nCopies(times, json);
    }
}
