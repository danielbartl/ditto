package dev.jbaby.ditto.comparator.flatten;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bson.BsonType.ARRAY;
import static org.bson.BsonType.DOCUMENT;
import static org.bson.BsonType.DOUBLE;
import static org.bson.BsonType.INT32;
import static org.bson.BsonType.NULL;
import static org.bson.BsonType.STRING;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Normalizer;
import dev.jbaby.ditto.comparator.path.PathRules;

class FlattenerTest {

    @Test
    void recordsEveryNodeOncePerDocument() {
        var flattener = new Flattener(PathRules.NONE, false);
        var document = BsonDocument.parse(
                "{_id: 1, name: 'x', items: [{sku: 'a', price: 1}, {sku: 'b', price: 2.5}, {sku: 'c', price: 3}],"
                        + " empty: [], meta: {}}");

        assertThat(flattener.pathTypes(document)).containsExactlyInAnyOrder(
                new PathType("_id", INT32),
                new PathType("name", STRING),
                new PathType("items", ARRAY),
                new PathType("items[]", DOCUMENT),
                new PathType("items[].sku", STRING),
                new PathType("items[].price", INT32),
                new PathType("items[].price", DOUBLE),
                new PathType("empty", ARRAY),
                new PathType("meta", DOCUMENT));
    }

    @Test
    void appliesIgnoredWildcardAndNullRules() {
        var rules = PathRules.compile(List.of("meta.syncedAt"), List.of(), List.of("attributes.*"));
        var document = BsonDocument.parse(
                "{meta: {syncedAt: 1, v: 1}, attributes: {color: 'red', size: 42}, gone: null, matrix: [[1]]}");

        assertThat(new Flattener(rules, false).pathTypes(document)).containsExactlyInAnyOrder(
                new PathType("meta", DOCUMENT),
                new PathType("meta.v", INT32),
                new PathType("attributes", DOCUMENT),
                new PathType("attributes.*", STRING),
                new PathType("attributes.*", INT32),
                new PathType("gone", NULL),
                new PathType("matrix", ARRAY),
                new PathType("matrix[]", ARRAY),
                new PathType("matrix[][]", INT32));
        assertThat(new Flattener(rules, true).pathTypes(document))
                .doesNotContain(new PathType("gone", NULL));
    }

    @Test
    void emitsLeavesOfCanonicalValues() {
        var rules = PathRules.compile(List.of(), List.of(), List.of("attributes.*"));
        var normalizer = new Normalizer(rules, "_id", false, new CanonicalEncoder());
        var canonical = normalizer.normalize(BsonDocument.parse(
                "{_id: 1, a: {b: 1, c: []}, tags: ['x', 'y'], attributes: {color: 'red'}, e: {}}"));
        List<String> leaves = new ArrayList<>();

        new Flattener(rules, false).leaves(canonical, rules.root(), "", (path, value) -> leaves.add(path));

        assertThat(leaves).containsExactly("a.b", "a.c", "attributes.*", "e", "tags[]", "tags[]");
    }
}
