package dev.jbaby.ditto.comparator.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.KeyRef;

class PathChangeStatsTest {

    @Test
    void sortsByFrequencyThenPathAndKeepsFirstExamples() {
        var stats = new PathChangeStats(100, 2);
        stats.record(new BsonInt32(1), List.of("b", "a"));
        stats.record(new BsonInt32(2), List.of("a"));
        stats.record(new BsonInt32(3), List.of("a", "c"));

        assertThat(stats.sorted()).containsExactly(
                new PathChangeStats.PathChangeCount("a", 3, List.of(new KeyRef("INT32", "1"), new KeyRef("INT32", "2"))),
                new PathChangeStats.PathChangeCount("b", 1, List.of(new KeyRef("INT32", "1"))),
                new PathChangeStats.PathChangeCount("c", 1, List.of(new KeyRef("INT32", "3"))));
    }

    @Test
    void capsTrackedPaths() {
        var stats = new PathChangeStats(1, 0);
        stats.record(new BsonInt32(1), List.of("a", "b"));
        stats.record(new BsonInt32(2), List.of("a", "c"));

        assertThat(stats.sorted()).extracting(PathChangeStats.PathChangeCount::path).containsExactly("a");
        assertThat(stats.untrackedOccurrences()).isEqualTo(2);
    }
}
