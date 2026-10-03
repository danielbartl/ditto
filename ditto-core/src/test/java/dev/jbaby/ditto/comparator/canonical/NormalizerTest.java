package dev.jbaby.ditto.comparator.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonDecimal128;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.path.PathRules;

class NormalizerTest {

    private final CanonicalEncoder encoder = new CanonicalEncoder();
    private final Hasher hasher = new Hasher(encoder);
    private final Normalizer plain = normalizer(PathRules.NONE, false);

    @Test
    void fieldOrderDoesNotMatter() {
        assertSameContent(plain, "{_id: 1, a: 1, b: {x: 1, y: 2}}", "{b: {y: 2, x: 1}, a: 1, _id: 1}");
    }

    @Test
    void arraysAreComparedAsMultisetsByDefault() {
        assertSameContent(plain, "{tags: ['b', 'a', 'c']}", "{tags: ['c', 'b', 'a']}");
        assertSameContent(plain,
                "{items: [{sku: 1, qty: 2, opts: [3, 1]}, {sku: 2, qty: 1, opts: []}]}",
                "{items: [{opts: [], qty: 1, sku: 2}, {qty: 2, opts: [1, 3], sku: 1}]}");
        assertDifferentContent(plain, "{tags: ['a', 'a', 'b']}", "{tags: ['a', 'b', 'b']}");
        assertDifferentContent(plain, "{tags: ['a', 'b']}", "{tags: ['a', 'b', 'b']}");
    }

    @Test
    void orderSensitiveArraysKeepTheirOrder() {
        var normalizer = normalizer(PathRules.compile(List.of(), List.of("steps", "matrix[]"), List.of()), false);

        assertDifferentContent(normalizer, "{steps: ['a', 'b']}", "{steps: ['b', 'a']}");
        assertSameContent(normalizer, "{other: ['a', 'b']}", "{other: ['b', 'a']}");
        // outer array unordered, inner arrays ordered
        assertSameContent(normalizer, "{matrix: [[1, 2], [3, 4]]}", "{matrix: [[3, 4], [1, 2]]}");
        assertDifferentContent(normalizer, "{matrix: [[1, 2], [3, 4]]}", "{matrix: [[2, 1], [3, 4]]}");
    }

    @Test
    void numbersAreComparedByValue() {
        var variants = List.of(
                new BsonDocument("n", new BsonInt32(42)),
                new BsonDocument("n", new BsonInt64(42)),
                new BsonDocument("n", new BsonDouble(42.0)),
                new BsonDocument("n", new BsonDecimal128(Decimal128.parse("42.000"))),
                new BsonDocument("n", new BsonDecimal128(Decimal128.parse("4.2E+1"))));
        var expected = hash(plain, variants.getFirst());
        variants.forEach(variant -> assertThat(hash(plain, variant)).as(variant.toJson()).isEqualTo(expected));

        assertSameContent(plain, "{n: 0.1}", "{n: {$numberDecimal: '0.10'}}");
        assertSameContent(plain, "{n: -0.0}", "{n: {$numberDecimal: '-0'}}");
        assertSameContent(plain, "{n: 0}", "{n: {$numberDecimal: '-0E+3'}}");
        assertSameContent(plain, "{n: NaN}", "{n: {$numberDecimal: 'NaN'}}");
        assertSameContent(plain, "{n: Infinity}", "{n: {$numberDecimal: 'Infinity'}}");
        assertDifferentContent(plain, "{n: Infinity}", "{n: -Infinity}");
        assertDifferentContent(plain, "{n: 1}", "{n: 1.0000001}");
        assertDifferentContent(plain, "{n: {$numberLong: '9007199254740993'}}", "{n: 9007199254740992.0}");
        assertDifferentContent(plain, "{n: 1}", "{n: '1'}");
    }

    @Test
    void datesAreEpochMillisAndDistinctFromNumbers() {
        assertSameContent(plain, "{d: {$date: '2024-01-01T00:00:00Z'}}", "{d: {$date: {$numberLong: '1704067200000'}}}");
        assertDifferentContent(plain, "{d: {$date: {$numberLong: '1704067200000'}}}", "{d: {$numberLong: '1704067200000'}}");
    }

    @Test
    void keyFieldAndIgnoredPathsAreRemoved() {
        var normalizer = normalizer(PathRules.compile(List.of("meta.syncedAt", "items[].etag"), List.of(), List.of()),
                false);

        assertSameContent(normalizer,
                "{_id: 1, a: 1, meta: {syncedAt: 1, v: 1}, items: [{p: 1, etag: 'x'}]}",
                "{_id: 2, a: 1, meta: {syncedAt: 2, v: 1}, items: [{p: 1, etag: 'y'}]}");
        assertDifferentContent(normalizer, "{meta: {v: 1}}", "{meta: {v: 2}}");
        // the key field is only dropped at the top level
        assertDifferentContent(normalizer, "{nested: {_id: 1}}", "{nested: {_id: 2}}");
    }

    @Test
    void nullAndMissingDifferByDefault() {
        assertDifferentContent(plain, "{a: 1, b: null}", "{a: 1}");

        var lenient = normalizer(PathRules.NONE, true);
        assertSameContent(lenient, "{a: 1, b: null, c: {d: null}}", "{a: 1, c: {}}");
        // nulls inside arrays are values, not missing fields
        assertDifferentContent(lenient, "{a: [null, 1]}", "{a: [1]}");
    }

    @Test
    void wildcardPathsDoNotAffectContent() {
        var normalizer = normalizer(PathRules.compile(List.of(), List.of(), List.of("attributes.*")), false);

        assertDifferentContent(normalizer, "{attributes: {color: 'red'}}", "{attributes: {colour: 'red'}}");
    }

    @Test
    void otherTypesAreComparedByValue() {
        assertSameContent(plain, "{r: {$regularExpression: {pattern: 'a.*', options: 'mi'}}}",
                "{r: {$regularExpression: {pattern: 'a.*', options: 'im'}}}");
        assertDifferentContent(plain, "{t: {$timestamp: {t: 1, i: 1}}}", "{t: {$timestamp: {t: 1, i: 2}}}");
        assertSameContent(plain, "{s: {$symbol: 'abc'}}", "{s: 'abc'}");
        assertSameContent(plain, "{b: {$binary: {base64: 'AQI=', subType: '00'}}}", "{b: {$binary: {base64: 'AQI=', subType: '00'}}}");
        assertDifferentContent(plain, "{b: {$binary: {base64: 'AQI=', subType: '00'}}}", "{b: {$binary: {base64: 'AQI=', subType: '05'}}}");
        assertDifferentContent(plain, "{m: {$minKey: 1}}", "{m: {$maxKey: 1}}");
    }

    private Normalizer normalizer(PathRules rules, boolean nullEqualsMissing) {
        return new Normalizer(rules, "_id", nullEqualsMissing, encoder);
    }

    private void assertSameContent(Normalizer normalizer, String a, String b) {
        var docA = BsonDocument.parse(a);
        var docB = BsonDocument.parse(b);
        assertThat(normalizer.normalize(docA)).as("%s vs %s", a, b).isEqualTo(normalizer.normalize(docB));
        assertThat(hash(normalizer, docA)).as("%s vs %s", a, b).isEqualTo(hash(normalizer, docB));
    }

    private void assertDifferentContent(Normalizer normalizer, String a, String b) {
        var docA = BsonDocument.parse(a);
        var docB = BsonDocument.parse(b);
        assertThat(normalizer.normalize(docA)).as("%s vs %s", a, b).isNotEqualTo(normalizer.normalize(docB));
        assertThat(hash(normalizer, docA)).as("%s vs %s", a, b).isNotEqualTo(hash(normalizer, docB));
    }

    private ContentHash hash(Normalizer normalizer, BsonDocument document) {
        return hasher.hash(normalizer.normalize(document));
    }
}
