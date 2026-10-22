package dev.jbaby.ditto.comparator.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.KeyRef;

class PathChangeStatsTest {

    @Test
    void sortsByFrequencyThenPathAndKeepsFirstExamples() {
        var stats = new PathChangeStats(100, 2, 1);
        stats.record(new BsonInt32(1), List.of("b", "a"));
        stats.record(new BsonInt32(2), List.of("a"));
        stats.record(new BsonInt32(3), List.of("a", "c"));

        assertThat(stats.sorted()).containsExactly(
                new PathChangeStats.PathChangeCount("a", 3, List.of(new KeyRef("INT32", "1"), new KeyRef("INT32", "2")),
                        List.of()),
                new PathChangeStats.PathChangeCount("b", 1, List.of(new KeyRef("INT32", "1")), List.of()),
                new PathChangeStats.PathChangeCount("c", 1, List.of(new KeyRef("INT32", "3")), List.of()));
    }

    @Test
    void asksForValueExamplesUntilEnoughAreCollected() {
        var stats = new PathChangeStats(100, 5, 1);
        var change = new dev.jbaby.ditto.comparator.api.ValueChange(new KeyRef("INT32", "1"), List.of("1"), List.of("2"));

        assertThat(stats.record(new BsonInt32(1), List.of("a", "b"))).containsExactly("a", "b");
        stats.addValueExample("a", change);
        assertThat(stats.record(new BsonInt32(2), List.of("a", "b"))).containsExactly("b");
        assertThat(stats.sorted().getFirst().valueExamples()).containsExactly(change);
    }

    @Test
    void capsTrackedPaths() {
        var stats = new PathChangeStats(1, 0, 0);
        stats.record(new BsonInt32(1), List.of("a", "b"));
        stats.record(new BsonInt32(2), List.of("a", "c"));

        assertThat(stats.sorted()).extracting(PathChangeStats.PathChangeCount::path).containsExactly("a");
        assertThat(stats.untrackedOccurrences()).isEqualTo(2);
    }
}
