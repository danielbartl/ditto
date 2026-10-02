package dev.jbaby.ditto.comparator.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mongodb.client.MongoDatabase;

import dev.jbaby.ditto.comparator.CollectionComparator;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathPresence;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShare;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.MixedKeyPolicy;
import dev.jbaby.ditto.comparator.support.Collections;
import dev.jbaby.ditto.comparator.support.TestApplication;

/**
 * End-to-end scenarios through the auto-configured {@link CollectionComparator}, one per behaviour the tool promises.
 */
@SpringBootTest(classes = TestApplication.class)
class ComparisonScenariosIT {

    private static final MongoDatabase DB = Collections.database(TestApplication.DATABASE);

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        TestApplication.mongoProperties(registry);
    }

    @Autowired
    CollectionComparator comparator;

    @Test
    void identicalCollectionsAreGreen() {
        var docs = documents(200, i -> "{_id: " + i + ", name: 'n" + i + "', tags: ['a', 'b'], meta: {v: 1}}");
        Collections.create(DB, "same_a", docs);
        Collections.create(DB, "same_b", docs);

        ComparisonReport report = compare("same_a", "same_b");

        assertThat(report.verdict()).isEqualTo(Level.GREEN);
        assertThat(report.rules()).allMatch(rule -> rule.level() == Level.GREEN);
        assertThat(report.keys().matched()).isEqualTo(200);
        assertThat(report.content().unchanged()).isEqualTo(200);
        assertThat(report.topChangedPaths()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void reorderedArraysAndFieldsAreUnchanged() {
        Collections.create(DB, "reorder_a", documents(100,
                i -> "{_id: " + i + ", tags: ['x', 'y', 'z'], items: [{sku: 1, q: 2}, {sku: 2, q: 1}], m: {a: 1, b: 2}}"));
        Collections.create(DB, "reorder_b", documents(100,
                i -> "{m: {b: 2, a: 1}, items: [{q: 1, sku: 2}, {q: 2, sku: 1}], tags: ['z', 'x', 'y'], _id: " + i + "}"));

        ComparisonReport report = compare("reorder_a", "reorder_b");

        assertThat(report.content().unchanged()).isEqualTo(100);
        assertThat(report.verdict()).isEqualTo(Level.GREEN);
    }

    @Test
    void orderSensitiveArraysDetectReordering() {
        Collections.create(DB, "steps_a", documents(100, i -> "{_id: " + i + ", steps: ['a', 'b'], tags: ['a', 'b']}"));
        Collections.create(DB, "steps_b", documents(100, i -> "{_id: " + i + ", steps: ['b', 'a'], tags: ['b', 'a']}"));

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("steps_a", "steps_b")
                .orderSensitivePaths("steps").build());

        assertThat(report.content().changed()).isEqualTo(100);
        assertThat(report.topChangedPaths()).extracting(PathChange::path).containsExactly("steps[]");
    }

    @Test
    void intVersusDoubleIsUnchangedContentButATypeShift() {
        Collections.create(DB, "num_a", documents(100, i -> "{_id: " + i + ", qty: " + i + "}"));
        Collections.create(DB, "num_b", documents(100, i -> "{_id: " + i + ", qty: " + i + ".0}"));

        ComparisonReport report = compare("num_a", "num_b");

        assertThat(report.content().unchanged()).isEqualTo(100);
        assertThat(report.topChangedPaths()).isEmpty();
        assertThat(report.structure().typeShifts()).singleElement().satisfies(shift -> {
            assertThat(shift.path()).isEqualTo("qty");
            assertThat(shift.baseline()).containsExactly(new TypeShare("INT32", 100, 1.0));
            assertThat(shift.candidate()).containsExactly(new TypeShare("DOUBLE", 100, 1.0));
        });
        assertThat(rules(report)).containsEntry("structure.typeShifts", Level.RED)
                .containsEntry("unchangedRate", Level.GREEN);
        assertThat(report.verdict()).isEqualTo(Level.RED);
    }

    @Test
    void vanishedFieldIsRed() {
        Collections.create(DB, "gone_a", documents(100, i -> "{_id: " + i + ", keep: 1, legacy: {code: 'x'}}"));
        Collections.create(DB, "gone_b", documents(100, i -> "{_id: " + i + ", keep: 1}"));

        ComparisonReport report = compare("gone_a", "gone_b");

        assertThat(report.verdict()).isEqualTo(Level.RED);
        assertThat(report.structure().missingPaths()).extracting(PathPresence::path)
                .containsExactly("legacy", "legacy.code");
        assertThat(report.topChangedPaths()).extracting(PathChange::path).containsExactly("legacy.code");
        RuleResult vanished = rule(report, "structure.vanishedPaths");
        assertThat(vanished.level()).isEqualTo(Level.RED);
        assertThat(vanished.details()).contains("legacy (in 1.0000 of baseline)");
    }

    @Test
    void oneFieldChangedInAllDocumentsIsRed() {
        Collections.create(DB, "price_a", documents(100, i -> "{_id: " + i + ", price: " + i + ", name: 'n'}"));
        Collections.create(DB, "price_b", documents(100, i -> "{_id: " + i + ", price: " + (i + 1) + ", name: 'n'}"));

        ComparisonReport report = compare("price_a", "price_b");

        assertThat(report.verdict()).isEqualTo(Level.RED);
        assertThat(report.content().changed()).isEqualTo(100);
        assertThat(report.topChangedPaths()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("price");
            assertThat(change.changeRate().value()).isEqualTo(1.0);
            assertThat(change.examples()).hasSize(20).first().isEqualTo(new KeyRef("INT32", "0"));
        });
        assertThat(rules(report)).containsEntry("maxPathChangeRate", Level.RED)
                .containsEntry("unchangedRate", Level.RED)
                .containsEntry("keySimilarity", Level.GREEN);
    }

    @Test
    void addedAndRemovedDocumentsAreCounted() {
        Collections.create(DB, "keys_a", documents(1000, i -> "{_id: " + i + "}"));
        Collections.create(DB, "keys_b", documents(1000, i -> "{_id: " + (i + 5) + "}"));

        ComparisonReport report = compare("keys_a", "keys_b");

        assertThat(report.keys().matched()).isEqualTo(995);
        assertThat(report.keys().added()).isEqualTo(5);
        assertThat(report.keys().removed()).isEqualTo(5);
        assertThat(report.keys().keySimilarity().value()).isEqualTo(995.0 / 1005);
        assertThat(report.examples().added()).extracting(KeyRef::value).containsExactly("1000", "1001", "1002", "1003", "1004");
        assertThat(report.examples().removed()).extracting(KeyRef::value).containsExactly("0", "1", "2", "3", "4");
        assertThat(rule(report, "keySimilarity").level()).isEqualTo(Level.GREEN); // 0.990 >= 0.99
    }

    @Test
    void nestedArraysOfObjects() {
        String order = "{_id: %d, lines: [{sku: 'a', qty: 1, opts: [{k: 'c', v: 1}, {k: 's', v: 2}]},"
                + " {sku: 'b', qty: %d, opts: []}]}";
        Collections.create(DB, "nested_a", documents(100, i -> order.formatted(i, 1)));
        Collections.create(DB, "nested_b", documents(100, i -> i % 10 == 0
                // reordered at every level plus one changed quantity
                ? "{_id: %d, lines: [{opts: [], qty: 2, sku: 'b'}, {opts: [{v: 2, k: 's'}, {v: 1, k: 'c'}], sku: 'a', qty: 1}]}".formatted(i)
                : "{_id: %d, lines: [{sku: 'b', qty: 1, opts: []}, {sku: 'a', qty: 1, opts: [{k: 's', v: 2}, {k: 'c', v: 1}]}]}".formatted(i)));

        ComparisonReport report = compare("nested_a", "nested_b");

        assertThat(report.content().changed()).isEqualTo(10);
        assertThat(report.topChangedPaths()).extracting(PathChange::path).containsExactly("lines[].qty");
        assertThat(report.structure().typeShifts()).isEmpty();
        assertThat(report.structure().missingPaths()).isEmpty();
    }

    @Nested
    class NullVersusMissing {

        @Test
        void differentByDefault() {
            createNullCollections();

            ComparisonReport report = compare("null_a", "null_b");

            assertThat(report.content().changed()).isEqualTo(50);
            assertThat(report.topChangedPaths()).extracting(PathChange::path).containsExactly("note");
            assertThat(report.structure().presenceDeltas()).extracting(PathPresence::path).containsExactly("note");
        }

        @Test
        void equalWhenConfigured() {
            createNullCollections();

            ComparisonReport report = comparator.compare(ComparisonRequest.builder("null_a", "null_b")
                    .nullEqualsMissing(true).build());

            assertThat(report.content().unchanged()).isEqualTo(100);
            assertThat(report.structure().presenceDeltas()).isEmpty();
            assertThat(report.verdict()).isEqualTo(Level.GREEN);
        }

        private void createNullCollections() {
            Collections.create(DB, "null_a", documents(100, i -> i % 2 == 0
                    ? "{_id: " + i + ", note: null}" : "{_id: " + i + ", note: 'x'}"));
            Collections.create(DB, "null_b", documents(100, i -> i % 2 == 0
                    ? "{_id: " + i + "}" : "{_id: " + i + ", note: 'x'}"));
        }
    }

    @Test
    void emptyCollections() {
        Collections.create(DB, "empty_a", List.of());
        Collections.create(DB, "empty_b", List.of());
        Collections.create(DB, "full_c", documents(10, i -> "{_id: " + i + "}"));

        ComparisonReport bothEmpty = compare("empty_a", "empty_b");
        assertThat(bothEmpty.verdict()).isEqualTo(Level.GREEN);
        assertThat(bothEmpty.keys().keySimilarity().value()).isNull();
        assertThat(rule(bothEmpty, "keySimilarity").reason()).isEqualTo("Both collections are empty");

        ComparisonReport candidateEmpty = compare("full_c", "empty_b");
        assertThat(candidateEmpty.verdict()).isEqualTo(Level.RED);
        assertThat(candidateEmpty.keys().removed()).isEqualTo(10);
        assertThat(candidateEmpty.keys().keySimilarity().value()).isZero();

        ComparisonReport baselineEmpty = compare("empty_a", "full_c");
        assertThat(baselineEmpty.verdict()).isEqualTo(Level.RED);
        assertThat(baselineEmpty.keys().added()).isEqualTo(10);
    }

    @Test
    void mixedTypeKeys() {
        Collections.create(DB, "mixed_a", "{_id: 1, v: 1}", "{_id: 2, v: 2}", "{_id: 'b', v: 3}",
                "{_id: {$oid: '65f0a1b2c3d4e5f601234567'}, v: 4}");
        Collections.create(DB, "mixed_b", "{_id: 1.0, v: 1}", "{_id: 'b', v: 3}", "{_id: 'c', v: 5}",
                "{_id: {$oid: '65f0a1b2c3d4e5f601234567'}, v: 4}");

        assertThatThrownBy(() -> compare("mixed_a", "mixed_b"))
                .isInstanceOf(ComparisonException.class)
                .hasMessageContaining("mixed types [NUMBER, OBJECT_ID]");

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("mixed_a", "mixed_b")
                .mixedKeyPolicy(MixedKeyPolicy.COMPARE).build());
        assertThat(report.keys().matched()).isEqualTo(3);  // 1 == 1.0, 'b', ObjectId
        assertThat(report.keys().removed()).isEqualTo(1);  // 2
        assertThat(report.keys().added()).isEqualTo(1);    // 'c'
        assertThat(report.content().unchanged()).isEqualTo(3);
        assertThat(report.warnings()).singleElement().asString().contains("compared using MongoDB's cross-type order");
    }

    @Test
    void ignoredAndWildcardPaths() {
        Collections.create(DB, "wild_a", documents(100, i -> "{_id: " + i + ", syncedAt: 1, attributes: {c" + i
                + ": 'x', common: 1}, items: [{p: 1, etag: 'a'}]}"));
        Collections.create(DB, "wild_b", documents(100, i -> "{_id: " + i + ", syncedAt: 2, attributes: {c" + i
                + ": 'y', common: 1}, items: [{p: 1, etag: 'b'}]}"));

        ComparisonReport plain = compare("wild_a", "wild_b");
        assertThat(plain.structure().baselinePaths()).isGreaterThan(100);
        assertThat(plain.topChangedPaths()).hasSize(50); // capped by topChangedPaths

        ComparisonReport configured = comparator.compare(ComparisonRequest.builder("wild_a", "wild_b")
                .ignoredPaths("syncedAt", "items[].etag").wildcardPaths("attributes.*").build());
        assertThat(configured.topChangedPaths()).extracting(PathChange::path).containsExactly("attributes.*");
        assertThat(configured.topChangedPaths().getFirst().changedDocs()).isEqualTo(100);
        assertThat(configured.structure().baselinePaths()).isEqualTo(6); // _id, attributes, attributes.*, items, items[], items[].p
    }

    private ComparisonReport compare(String baseline, String candidate) {
        return comparator.compare(ComparisonRequest.of(baseline, candidate));
    }

    private static List<BsonDocument> documents(int count, java.util.function.IntFunction<String> json) {
        return IntStream.range(0, count).mapToObj(json).map(BsonDocument::parse)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static RuleResult rule(ComparisonReport report, String name) {
        return report.rules().stream().filter(rule -> rule.rule().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, Level> rules(ComparisonReport report) {
        return report.rules().stream().collect(Collectors.toMap(RuleResult::rule, RuleResult::level));
    }
}
