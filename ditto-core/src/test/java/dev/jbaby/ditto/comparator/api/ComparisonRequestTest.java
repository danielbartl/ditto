package dev.jbaby.ditto.comparator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
                .build();

        assertThat(request.toBuilder().build()).isEqualTo(request);
        assertThat(request.mode()).isEqualTo(new ComparisonMode.Sample(500));
    }

    @Test
    void keyFieldMustBeTopLevel() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ComparisonRequest.builder("a", "b").keyField("meta.id").build());
        assertThatIllegalArgumentException().isThrownBy(() -> ComparisonMode.sample(0));
        assertThatIllegalArgumentException().isThrownBy(() -> CollectionRef.of(" "));
    }
}
