package dev.jbaby.ditto.comparator.flatten;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Normalizer;
import dev.jbaby.ditto.comparator.path.PathRules;

class DocumentDiffTest {

    private static final PathRules RULES = PathRules.compile(
            List.of("meta.syncedAt"), List.of("steps"), List.of("attributes.*"));

    @Test
    void identicalContentHasNoChangedPaths() {
        assertThat(changed("{a: 1, tags: ['x', 'y'], meta: {syncedAt: 1}}", "{tags: ['y', 'x'], a: 1.0, meta: {syncedAt: 2}}"))
                .isEmpty();
    }

    @Test
    void reportsChangedAddedAndRemovedLeaves() {
        assertThat(changed("{a: 1, b: {c: 1, d: 2}, gone: {x: 1, y: [1]}}", "{a: 2, b: {c: 1, d: 2, e: 3}}"))
                .containsExactly("a", "b.e", "gone.x", "gone.y[]");
    }

    @Test
    void attributesChangeInsideReorderedArrayToTheChangedField() {
        assertThat(changed(
                "{items: [{sku: 1, price: 10, tags: ['a']}, {sku: 2, price: 20, tags: ['b']}, {sku: 3, price: 30, tags: []}]}",
                "{items: [{sku: 3, price: 30, tags: []}, {sku: 2, price: 20, tags: ['b']}, {sku: 1, price: 11, tags: ['a']}]}"))
                .containsExactly("items[].price");
    }

    @Test
    void reportsTheArrayWhenValuesMoveBetweenElements() {
        assertThat(changed("{pairs: [{a: 1, b: 2}, {a: 2, b: 1}]}", "{pairs: [{a: 1, b: 1}, {a: 2, b: 2}]}"))
                .containsExactly("pairs[]");
    }

    @Test
    void handlesDuplicateElements() {
        assertThat(changed("{t: ['x']}", "{t: ['x', 'x']}")).containsExactly("t[]");
        assertThat(changed("{t: ['x', 'x', 'y']}", "{t: ['x', 'y', 'y']}")).containsExactly("t[]");
    }

    @Test
    void comparesOrderSensitiveArraysByIndex() {
        assertThat(changed("{steps: [{n: 'a'}, {n: 'b'}]}", "{steps: [{n: 'b'}, {n: 'a'}]}")).containsExactly("steps[].n");
        assertThat(changed("{steps: ['a']}", "{steps: ['a', 'b']}")).containsExactly("steps[]");
    }

    @Test
    void emptyAndNonEmptyArrays() {
        // only the leaves of the non-empty side are reported
        assertThat(changed("{t: []}", "{t: ['a']}")).containsExactly("t[]");
        assertThat(changed("{items: [{sku: 1}]}", "{items: []}")).containsExactly("items[].sku");
    }

    @Test
    void collapsesWildcardMapKeys() {
        assertThat(changed("{attributes: {color: 'red', size: 1}}", "{attributes: {color: 'blue', size: 1, weight: 3}}"))
                .containsExactly("attributes.*");
    }

    @Test
    void typeChangeBetweenScalarAndDocument() {
        assertThat(changed("{a: 5}", "{a: {x: 1}}")).containsExactly("a", "a.x");
        assertThat(changed("{a: null}", "{}")).containsExactly("a");
        assertThat(changed("{a: 1}", "{a: '1'}")).containsExactly("a");
    }

    private static List<String> changed(String baseline, String candidate) {
        var normalizer = new Normalizer(RULES, "_id", false, new CanonicalEncoder());
        var diff = new DocumentDiff(RULES, new Flattener(RULES, false));
        return List.copyOf(diff.changedPaths(
                normalizer.normalize(BsonDocument.parse(baseline)),
                normalizer.normalize(BsonDocument.parse(candidate))));
    }
}
