package dev.jbaby.ditto.comparator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ComparisonRequestTest {

    @Test
    void unsetOptionsStayNullSoDefaultsApply() {
        var request = ComparisonRequest.of("products_backup", "products");

        assertThat(request.baseline()).isEqualTo(CollectionRef.of("products_backup"));
        assertThat(request.keyField()).isNull();
        assertThat(request.ignoredPaths()).isNull();
        assertThat(request.mode()).isNull();
        assertThat(request.matchedOnly()).isNull();
        assertThat(request.labels()).isNull();
        assertThat(request.thresholds()).isNull();
    }

    @Test
    void builderAndToBuilderRoundTrip() {
        var request = ComparisonRequest.builder(CollectionRef.of("old", "products"), CollectionRef.of("products"))
                .keyField("sku")
                .ignoredPaths("meta.syncedAt")
                .orderSensitivePaths("steps")
                .wildcardPaths("attributes.*")
                .sample(500)
                .matchedOnly()
                .nullEqualsMissing(true)
                .mixedKeyPolicy(MixedKeyPolicy.COMPARE)
                .verdictBasis(VerdictBasis.POINT)
                .thresholds(Thresholds.DEFAULTS)
                .label("batchJobId", "4711")
                .build();

        assertThat(request.toBuilder().build()).isEqualTo(request);
        assertThat(request.labels()).isEqualTo(Map.of("batchJobId", "4711"));
        assertThat(request.mode()).isEqualTo(new ComparisonMode.Sample(500));
    }

    @Test
    void labelKeysMustBeUsableAsFieldNames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ComparisonRequest.builder("a", "b").label("job.id", "1").build())
                .withMessageContaining("Invalid label key 'job.id'");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ComparisonRequest.builder("a", "b").label("$where", "1").build());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ComparisonRequest.builder("a", "b").label("job", "x".repeat(513)).build());
    }

    @Test
    void parsesLabelPairs() {
        assertThat(Labels.parse(" env=test, batchJobId:4711 ,at=2026-10-05T10:00"))
                .containsExactly(Map.entry("at", "2026-10-05T10:00"), Map.entry("batchJobId", "4711"),
                        Map.entry("env", "test"));
        assertThatIllegalArgumentException().isThrownBy(() -> Labels.parse("novalue"))
                .withMessageContaining("expected key=value");
    }

    @Test
    void keyFieldMustBeTopLevel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ComparisonRequest.builder("a", "b").keyField("meta.id").build());
        assertThatIllegalArgumentException().isThrownBy(() -> ComparisonMode.sample(0));
        assertThatIllegalArgumentException().isThrownBy(() -> CollectionRef.of(" "));
    }
}
