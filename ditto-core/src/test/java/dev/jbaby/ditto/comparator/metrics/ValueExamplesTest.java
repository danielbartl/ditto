package dev.jbaby.ditto.comparator.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.ValueChange;
import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Normalizer;
import dev.jbaby.ditto.comparator.flatten.Flattener;
import dev.jbaby.ditto.comparator.path.PathMatcher;
import dev.jbaby.ditto.comparator.path.PathRules;

class ValueExamplesTest {

    private static final PathRules RULES = PathRules.compile(List.of(), List.of(), List.of("attributes.*"));
    private static final KeyRef KEY = new KeyRef("INT32", "7");

    @Test
    void showsTheValuesOnEachSide() {
        Map<String, ValueChange> changes = examples(PathMatcher.NONE,
                "{price: {$numberDecimal: '19.90'}, name: 'Desk \"Oak\"', d: {$date: '2024-01-01T00:00:00Z'}, gone: true}",
                "{price: 0, name: 'Desk', d: {$date: '2024-01-02T00:00:00Z'}, added: null}",
                "price", "name", "d", "gone", "added");

        assertThat(changes).containsExactly(
                Map.entry("price", new ValueChange(KEY, List.of("19.9"), List.of("0"))),
                Map.entry("name", new ValueChange(KEY, List.of("\"Desk \\\"Oak\\\"\""), List.of("\"Desk\""))),
                Map.entry("d", new ValueChange(KEY, List.of("2024-01-01T00:00:00Z"), List.of("2024-01-02T00:00:00Z"))),
                Map.entry("gone", new ValueChange(KEY, List.of("true"), List.of())),
                Map.entry("added", new ValueChange(KEY, List.of(), List.of("null"))));
    }

    @Test
    void listsOnlyDifferingArrayValues() {
        Map<String, ValueChange> changes = examples(PathMatcher.NONE,
                "{items: [{sku: 'a', p: 1}, {sku: 'b', p: 2}, {sku: 'c', p: 3}]}",
                "{items: [{sku: 'c', p: 3}, {sku: 'a', p: 1}, {sku: 'b', p: 5}]}",
                "items[].p");

        assertThat(changes).containsExactly(Map.entry("items[].p", new ValueChange(KEY, List.of("2"), List.of("5"))));
    }

    @Test
    void redactsAndSkipsPathsWithoutDifferingValues() {
        Map<String, ValueChange> changes = examples(PathMatcher.of(List.of("customer")),
                "{customer: {email: 'a@example.com'}, pairs: [{a: 1, b: 2}, {a: 2, b: 1}], attributes: {c: 'red'}}",
                "{customer: {email: 'b@example.com'}, pairs: [{a: 1, b: 1}, {a: 2, b: 2}], attributes: {c: 'blue'}}",
                "customer.email", "pairs[]", "attributes.*");

        assertThat(changes).containsExactly(
                Map.entry("customer.email", new ValueChange(KEY, List.of("***"), List.of("***"))),
                Map.entry("attributes.*", new ValueChange(KEY, List.of("\"red\""), List.of("\"blue\""))));
    }

    @Test
    void capsValueCountAndLength() {
        StringBuilder many = new StringBuilder("{t: [");
        for (int i = 0; i < 8; i++) {
            many.append(i).append(',');
        }
        many.append("99]}");
        Map<String, ValueChange> changes = examples(PathMatcher.NONE, "{t: [], s: '" + "x".repeat(500) + "'}",
                many.toString().replace("]}", "], s: 'y'}"), "t[]", "s");

        assertThat(changes.get("t[]").candidate()).hasSize(6).last().isEqualTo("… 4 more");
        assertThat(changes.get("s").baseline().getFirst()).hasSize(ValueExamples.MAX_LENGTH).endsWith("…");
    }

    private static Map<String, ValueChange> examples(PathMatcher redacted, String baseline, String candidate,
                                                     String... paths) {
        var normalizer = new Normalizer(RULES, "_id", false, new CanonicalEncoder());
        var examples = new ValueExamples(RULES, new Flattener(RULES, false), redacted);
        return examples.of(new BsonInt32(7), normalizer.normalize(BsonDocument.parse(baseline)),
                normalizer.normalize(BsonDocument.parse(candidate)), new java.util.LinkedHashSet<>(List.of(paths)));
    }
}
