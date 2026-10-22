package dev.jbaby.ditto.comparator.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.metrics.PathChangeStats.PathChangeCount;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;

class LookupJoinComparatorTest {

    @Test
    void classifiesDocumentsReadInAnyOrder() {
        var baseline = InMemorySource.of("baseline", "{_id: 'c', v: 3}", "{_id: 'a', v: 1}", "{_id: 'e', v: 5}",
                "{_id: 'b', v: 2}");
        var candidate = InMemorySource.of("candidate", "{_id: 'd', v: 4}", "{_id: 'b', v: 2}", "{_id: 'a', v: -1}",
                "{_id: 'c', v: 3}", "{_id: 'f', v: 6}");

        ScanResult result = compare(baseline, candidate, 2);

        assertThat(result.matched()).isEqualTo(3);
        assertThat(result.unchanged()).isEqualTo(2);
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.baselineDocsRead()).isEqualTo(4);
        assertThat(result.candidateDocsRead()).isEqualTo(5);
        assertThat(result.pathChanges()).extracting(PathChangeCount::path).containsExactly("v");
        assertThat(result.removedExamples()).containsExactly(key("e"));
        assertThat(result.addedExamples()).containsExactlyInAnyOrder(key("d"), key("f"));
        // added documents are profiled with their content, not just their keys
        assertThat(result.candidateProfile().documents()).isEqualTo(5);
        assertThat(result.candidateProfile().presence("v")).isEqualTo(1.0);
    }

    @Test
    void emptySidesAreFine() {
        ScanResult result = compare(InMemorySource.of("baseline"), InMemorySource.of("candidate", "{_id: 1}"), 10);

        assertThat(result.matched()).isZero();
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.removed()).isZero();
    }

    @Test
    void rejectsDuplicateKeys() {
        var duplicateInScan = InMemorySource.of("baseline", "{_id: 1}", "{_id: 1}");
        assertThatThrownBy(() -> compare(duplicateInScan, InMemorySource.of("candidate"), 10))
                .isInstanceOf(ComparisonException.class).hasMessageContaining("Duplicate key 1");

        var duplicateInLookup = InMemorySource.of("candidate", "{_id: 1}", "{_id: 1}");
        assertThatThrownBy(() -> compare(InMemorySource.of("baseline", "{_id: 1}"), duplicateInLookup, 1))
                .isInstanceOf(ComparisonException.class).hasMessageContaining("Duplicate key");
    }

    private static ScanResult compare(DocumentSource baseline, DocumentSource candidate, int lookupBatchSize) {
        var properties = new ComparatorProperties();
        properties.getSample().setLookupBatchSize(lookupBatchSize);
        ComparisonSettings settings = properties.settingsFor(ComparisonRequest.of("baseline", "candidate"), "db");
        return new LookupJoinComparator().compare(baseline, candidate, settings,
                new ScanAccumulator(settings, new Hasher(new CanonicalEncoder())),
                new ProgressReporter("test", Duration.ofSeconds(1), 0L, progress -> {
                }));
    }

    private static KeyRef key(String id) {
        return new KeyRef("STRING", "\"" + id + "\"");
    }
}
