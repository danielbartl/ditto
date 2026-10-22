package dev.jbaby.ditto.comparator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.bson.BsonType;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.Rate;
import dev.jbaby.ditto.comparator.api.ValueChange;
import dev.jbaby.ditto.comparator.structure.StructureProfile;

class HintAdvisorTest {

    private static final KeyRef KEY = new KeyRef("STRING", "\"k\"");

    @Test
    void bsonDatesAreTime() {
        assertThat(HintAdvisor.isTime(profile(BsonType.DATE_TIME), change(List.of()))).isTrue();
    }

    @Test
    void isoStringsAreTimeWhenEveryExampleIsADateTime() {
        var profile = profile(BsonType.STRING);

        assertThat(HintAdvisor.isTime(profile, change(List.of(
                new ValueChange(KEY, List.of("\"2026-10-21T08:00:00Z\""), List.of("\"2026-10-22T08:00:00.123+02:00\"")),
                new ValueChange(KEY, List.of(), List.of("\"2026-10-22T08:00:00\"")))))).isTrue();
        assertThat(HintAdvisor.isTime(profile, change(List.of(
                new ValueChange(KEY, List.of("\"2026-10-21T08:00:00Z\""), List.of("\"tomorrow\"")))))).isFalse();
        assertThat(HintAdvisor.isTime(profile, change(List.of(
                new ValueChange(KEY, List.of("\"2026-10-21\""), List.of("\"2026-10-22\"")))))).as("dates only")
                .isFalse();
        assertThat(HintAdvisor.isTime(profile, change(List.of()))).as("no evidence").isFalse();
        assertThat(HintAdvisor.isTime(profile, change(List.of(new ValueChange(KEY, List.of("***"), List.of("***"))))))
                .as("redacted").isFalse();
    }

    private static StructureProfile profile(BsonType type) {
        return new StructureProfile(10, Map.of("syncedAt", new StructureProfile.PathStat(10, Map.of(type, 10L))),
                false, 0);
    }

    private static PathChange change(List<ValueChange> examples) {
        return new PathChange("syncedAt", 10, Rate.exact(10, 10), false, List.of(KEY), examples);
    }
}
