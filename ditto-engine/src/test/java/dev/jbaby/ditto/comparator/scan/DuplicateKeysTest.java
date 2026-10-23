package dev.jbaby.ditto.comparator.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;

/**
 * Duplicate keys in sources that are not read in key order, where no scan notices them on the way: each algorithm
 * must still reject them, also when the key exists on one side only.
 */
class DuplicateKeysTest {

    private final ComparisonSettings settings = settings();

    @Test
    void theLookupJoinRejectsADuplicateThatOnlyTheBaselineHas() {
        // in different lookup batches, and missing in the candidate: counted as removed twice before
        var baseline = InMemorySource.of("baseline", "{_id: 1}", "{_id: 2}", "{_id: 1}");
        var candidate = InMemorySource.of("candidate", "{_id: 2}");

        assertDuplicate(() -> new LookupJoinComparator().compare(baseline, candidate, settings, accumulator(),
                progress()));
    }

    @Test
    void sampleModeRejectsADuplicateOnTheSampledSide() {
        var duplicated = InMemorySource.of("baseline", "{_id: 1, v: 1}", "{_id: 1, v: 2}");

        assertDuplicate(() -> new SampleComparator().compare(duplicated, InMemorySource.of("candidate"), settings,
                10, accumulator(), progress()));
        assertDuplicate(() -> new SampleComparator().compare(InMemorySource.of("baseline"),
                InMemorySource.of("candidate", "{_id: 1, v: 1}", "{_id: 1, v: 2}"), settings, 10, accumulator(),
                progress()));
    }

    @Test
    void matchedOnlyRejectsADuplicateOnTheReadSide() {
        var baseline = InMemorySource.of("baseline", "{_id: 1}", "{_id: 2}", "{_id: 1}");
        var candidate = InMemorySource.of("candidate", "{_id: 2}", "{_id: 3}", "{_id: 4}");

        assertDuplicate(() -> new MatchedOnlyComparator().compare(baseline, candidate, settings, true, null,
                accumulator(), progress()));
        assertDuplicate(() -> new MatchedOnlyComparator().compare(baseline, candidate, settings, true, 10,
                accumulator(), progress()));
    }

    @Test
    void uniqueKeysPass() {
        var baseline = InMemorySource.of("baseline", "{_id: 1}", "{_id: 2}", "{_id: 3}");
        var candidate = InMemorySource.of("candidate", "{_id: 2}", "{_id: 4}");

        assertThat(new LookupJoinComparator().compare(baseline, candidate, settings, accumulator(), progress())
                .removed()).isEqualTo(2);
        assertThat(new SampleComparator().compare(baseline, candidate, settings, 10, accumulator(), progress())
                .matched()).isEqualTo(1);
        assertThat(new MatchedOnlyComparator().compare(baseline, candidate, settings, true, null, accumulator(),
                progress()).matched()).isEqualTo(1);
    }

    private static void assertDuplicate(ThrowingCallable comparison) {
        assertThatThrownBy(comparison).isInstanceOf(ComparisonException.class).hasMessageContaining("Duplicate key");
    }

    private static ComparisonSettings settings() {
        var properties = new ComparatorProperties();
        properties.getSample().setLookupBatchSize(1);
        return properties.settingsFor(ComparisonRequest.of("baseline", "candidate"), "db");
    }

    private ScanAccumulator accumulator() {
        return new ScanAccumulator(settings, new Hasher(new CanonicalEncoder()));
    }

    private static ProgressReporter progress() {
        return new ProgressReporter("test", Duration.ofSeconds(1), 0L, progress -> {
        });
    }
}
