package dev.jbaby.ditto.comparator.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.Test;

class PathRulesTest {

    @Test
    void parsesFieldsElementsAndWildcards() {
        assertThat(PathPattern.parse("matrix[][].v.*").segments()).containsExactly(
                new PathPattern.Field("matrix"), new PathPattern.Element(), new PathPattern.Element(),
                new PathPattern.Field("v"), new PathPattern.AnyField());
    }

    @Test
    void rejectsMalformedPatterns() {
        for (String invalid : new String[] {"", " ", "a..b", ".a", "a.", "[]", "a.[]", "a[b]", "a*", "a[]x"}) {
            assertThatIllegalArgumentException().as(invalid).isThrownBy(() -> PathPattern.parse(invalid));
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PathRules.compile(List.of(), List.of(), List.of("attributes")))
                .withMessageContaining("must end with '.*'");
    }

    @Test
    void tracksIgnoredAndOrderSensitiveNodes() {
        var rules = PathRules.compile(List.of("meta.syncedAt", "items[].internal"), List.of("steps", "*.history"),
                List.of());
        var root = rules.root();

        assertThat(root.field("meta").ignored()).isFalse();
        assertThat(root.field("meta").field("syncedAt").ignored()).isTrue();
        assertThat(root.field("meta").field("other").ignored()).isFalse();
        assertThat(root.field("items").element().field("internal").ignored()).isTrue();
        assertThat(root.field("items").field("internal").ignored()).as("needs the [] segment").isFalse();
        assertThat(root.field("steps").orderSensitive()).isTrue();
        assertThat(root.field("steps").element().orderSensitive()).isFalse();
        assertThat(root.field("anything").field("history").orderSensitive()).isTrue();
        assertThat(root.field("unrelated").field("deep").field("deeper").ignored()).isFalse();
    }

    @Test
    void collapsesWildcardMapsInReportedPaths() {
        var rules = PathRules.compile(List.of(), List.of(), List.of("attributes.*", "locales.*.labels.*"));
        var root = rules.root();
        var attributes = root.field("attributes");

        assertThat(Paths.field("", "attributes", root)).isEqualTo("attributes");
        assertThat(Paths.field("attributes", "color", attributes)).isEqualTo("attributes.*");
        // '*' inside a pattern only matches; only the map named by the full pattern is collapsed
        assertThat(Paths.field("locales", "de", root.field("locales"))).isEqualTo("locales.de");
        var labels = root.field("locales").field("de").field("labels");
        assertThat(Paths.field("locales.de.labels", "title", labels)).isEqualTo("locales.de.labels.*");
        assertThat(Paths.element("items")).isEqualTo("items[]");
    }
}
