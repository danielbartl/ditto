package dev.jbaby.ditto.cli.generator;

import static org.assertj.core.api.Assertions.assertThat;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class MutatorTest {

    private final Mutator mutator = new Mutator(1);

    @Test
    void appliesFieldChanges() {
        assertThat(apply("modify:a.b:1", "{a: {b: 1}}")).isEqualTo(BsonDocument.parse("{a: {b: 2}}"));
        assertThat(apply("modify:s:1", "{s: 'x'}")).isEqualTo(BsonDocument.parse("{s: 'x (changed)'}"));
        assertThat(apply("modify:p:1", "{p: {$numberDecimal: '1.50'}}"))
                .isEqualTo(BsonDocument.parse("{p: {$numberDecimal: '2.50'}}"));
        assertThat(apply("drop-field:a.b:1", "{a: {b: 1, c: 2}}")).isEqualTo(BsonDocument.parse("{a: {c: 2}}"));
        assertThat(apply("add-field:x.y:1", "{}").getDocument("x").getString("y").getValue()).startsWith("added-");
        assertThat(apply("set-null:a:1", "{a: 5}")).isEqualTo(BsonDocument.parse("{a: null}"));
        assertThat(apply("int-to-double:a:1", "{a: 5}")).isEqualTo(BsonDocument.parse("{a: 5.0}"));
        assertThat(apply("int-to-double:a:1", "{a: 5}").get("a").isDouble()).isTrue();
        assertThat(apply("to-string:a:1", "{a: {$numberLong: '7'}}")).isEqualTo(BsonDocument.parse("{a: '7'}"));
    }

    @Test
    void reportsWhetherSomethingChanged() {
        assertThat(mutator.apply(ChangeSpec.parse("drop-field:missing:1"), new BsonDocument())).isFalse();
        assertThat(mutator.apply(ChangeSpec.parse("int-to-double:s:1"), BsonDocument.parse("{s: 'x'}"))).isFalse();
        assertThat(mutator.apply(ChangeSpec.parse("modify:a.b:1"), BsonDocument.parse("{a: 1}"))).isFalse();
    }

    @Test
    void shufflingKeepsTheMultiset() {
        var document = BsonDocument.parse("{t: [1, 2, 3, 4, 5, 6, 7, 8], n: {u: [{v: [1, 2, 3]}]}}");
        var shuffled = document.clone();
        mutator.apply(ChangeSpec.parse("shuffle-arrays:1"), shuffled);

        assertThat(shuffled.getArray("t")).containsExactlyInAnyOrderElementsOf(document.getArray("t"));
        assertThat(shuffled.getArray("t")).isNotEqualTo(document.getArray("t"));
    }

    private BsonDocument apply(String spec, String json) {
        var document = BsonDocument.parse(json);
        assertThat(mutator.apply(ChangeSpec.parse(spec), document)).isTrue();
        return document;
    }
}
