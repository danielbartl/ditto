package dev.jbaby.ditto.comparator.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.MixedKeyPolicy;
import dev.jbaby.ditto.comparator.api.Thresholds;
import dev.jbaby.ditto.comparator.api.VerdictBasis;

class ComparatorPropertiesTest {

    @Test
    void defaultsMatchTheDocumentedValues() {
        ComparisonSettings settings = new ComparatorProperties()
                .settingsFor(ComparisonRequest.of("products_backup", "products"), "shop");

        assertThat(settings.baseline()).isEqualTo(CollectionRef.of("shop", "products_backup"));
        assertThat(settings.candidate()).isEqualTo(CollectionRef.of("shop", "products"));
        assertThat(settings.keyField()).isEqualTo("_id");
        assertThat(settings.ignoredPaths()).containsExactly("_class");
        assertThat(settings.mode()).isEqualTo(ComparisonMode.auto());
        assertThat(settings.nullEqualsMissing()).isFalse();
        assertThat(settings.mixedKeyPolicy()).isEqualTo(MixedKeyPolicy.REJECT);
        assertThat(settings.verdictBasis()).isEqualTo(VerdictBasis.CONSERVATIVE);
        assertThat(settings.thresholds()).isEqualTo(Thresholds.DEFAULTS);
        assertThat(settings.tuning()).isEqualTo(new ComparisonSettings.Tuning(
                1000, false, 20, 3, 10_000, 50, Duration.ofSeconds(10), 500, 5_000_000, 20_000));
        assertThat(settings.expectedChangePaths()).isEmpty();
        assertThat(settings.redactedPaths()).isEmpty();
    }

    @Test
    void requestValuesOverrideDefaults() {
        var properties = bind(Map.of("ditto.ignored-paths", "_class,meta.syncedAt"));
        var thresholds = Thresholds.DEFAULTS.withKeySimilarity(new Thresholds.AtLeast(0.999, 0.99));
        var request = ComparisonRequest.builder(CollectionRef.of("archive", "p_backup"), CollectionRef.of("p"))
                .keyField("sku")
                .ignoredPaths("syncedAt")
                .sample(100)
                .nullEqualsMissing(true)
                .thresholds(thresholds)
                .build();

        ComparisonSettings settings = properties.settingsFor(request, "shop");

        assertThat(settings.baseline()).isEqualTo(CollectionRef.of("archive", "p_backup"));
        assertThat(settings.candidate()).isEqualTo(CollectionRef.of("shop", "p"));
        assertThat(settings.keyField()).isEqualTo("sku");
        // the request replaces configured ignored paths; always-ignored paths stay
        assertThat(settings.ignoredPaths()).containsExactly("_class", "syncedAt");
        assertThat(settings.mode()).isEqualTo(ComparisonMode.sample(100));
        assertThat(settings.nullEqualsMissing()).isTrue();
        assertThat(settings.thresholds()).isEqualTo(thresholds);
    }

    @Test
    void bindsRelaxedPropertyNames() {
        var properties = bind(Map.of(
                "ditto.key-field", "sku",
                "ditto.wildcard-paths[0]", "attributes.*",
                "ditto.mode", "sample",
                "ditto.sample.size", "2500",
                "ditto.sample.verdict-basis", "point",
                "ditto.progress-interval", "30s",
                "ditto.mixed-key-types", "compare",
                "ditto.thresholds.key-similarity.green", "0.995",
                "ditto.thresholds.max-path-change-rate.yellow", "0.3",
                "ditto.thresholds.structure.vanished-min-presence", "0.001"));

        ComparisonSettings settings = properties.settingsFor(ComparisonRequest.of("a", "b"), "db");

        assertThat(settings.keyField()).isEqualTo("sku");
        assertThat(settings.wildcardPaths()).isEqualTo(List.of("attributes.*"));
        assertThat(settings.mode()).isEqualTo(ComparisonMode.sample(2500));
        assertThat(settings.verdictBasis()).isEqualTo(VerdictBasis.POINT);
        assertThat(settings.mixedKeyPolicy()).isEqualTo(MixedKeyPolicy.COMPARE);
        assertThat(settings.tuning().progressInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.thresholds().keySimilarity()).isEqualTo(new Thresholds.AtLeast(0.995, 0.97));
        assertThat(settings.thresholds().maxPathChangeRate()).isEqualTo(new Thresholds.Below(0.05, 0.3));
        assertThat(settings.thresholds().structure().vanishedMinPresence()).isEqualTo(0.001);
    }

    @Test
    void alwaysIgnoredPathsCanBeCleared() {
        var properties = bind(Map.of("ditto.always-ignored-paths", "",
                "ditto.ignored-paths", "meta.syncedAt"));

        assertThat(properties.settingsFor(ComparisonRequest.of("a", "b"), "db").ignoredPaths())
                .containsExactly("meta.syncedAt");
    }

    @Test
    void rejectsComparingACollectionWithItself() {
        var request = ComparisonRequest.builder(CollectionRef.of("shop", "p"), CollectionRef.of("p")).build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ComparatorProperties().settingsFor(request, "shop"))
                .withMessageContaining("same collection");
    }

    @Test
    void invalidThresholdPropertiesFailWhenResolved() {
        var properties = bind(Map.of("ditto.thresholds.unchanged-rate.yellow", "0.99"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.settingsFor(ComparisonRequest.of("a", "b"), "db"));
    }

    private static ComparatorProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("ditto", ComparatorProperties.class);
    }
}
