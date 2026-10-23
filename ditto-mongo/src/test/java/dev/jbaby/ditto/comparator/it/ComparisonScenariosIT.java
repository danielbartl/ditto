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
import dev.jbaby.ditto.comparator.api.Hint;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.MixedKeyPolicy;
import dev.jbaby.ditto.comparator.api.Rate;
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
        // AUTO is the default: small collections are scanned fully
        assertThat(report.run().settings().mode()).isEqualTo(dev.jbaby.ditto.comparator.api.ComparisonMode.full());
        assertThat(report.run().decisions()).containsExactly(
                "Mode AUTO chose FULL: 200 and 200 documents, full-scan limit 5,000,000");
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

    /** A test environment holds only a part of the data: compare just the documents present on both sides. */
    @Nested
    class MatchedOnly {

        @Test
        void comparesOnlySharedDocumentsReadingTheSmallerSide() {
            createSubset();

            ComparisonReport plain = compare("full_set", "sub_set");
            assertThat(plain.verdict()).isEqualTo(Level.RED);
            assertThat(plain.structure().missingPaths()).extracting(PathPresence::path).containsExactly("archived");
            assertThat(plain.hints()).anySatisfy(hint -> {
                assertThat(hint.kind()).isEqualTo(Hint.Kind.MATCHED_ONLY);
                assertThat(hint.cliOption()).isEqualTo("--matched-only");
            });

            ComparisonReport report = comparator.compare(ComparisonRequest.builder("full_set", "sub_set")
                    .matchedOnly().build());

            assertThat(report.verdict()).isEqualTo(Level.GREEN);
            assertThat(report.keys().matched()).isEqualTo(100);
            assertThat(report.keys().removed()).isEqualTo(900);
            assertThat(report.keys().added()).isEqualTo(3);
            assertThat(report.keys().keySimilarity().value()).isEqualTo(100.0 / 1003);
            RuleResult keySimilarity = rule(report, "keySimilarity");
            assertThat(keySimilarity.level()).isEqualTo(Level.GREEN);
            assertThat(keySimilarity.observed()).isNull();
            assertThat(report.content().changed()).isEqualTo(2);
            assertThat(report.topChangedPaths()).extracting(PathChange::path).containsExactly("price");
            assertThat(report.topChangedPaths().getFirst().changeRate().value()).isEqualTo(0.02);
            // only matched documents are profiled: neither archived nor extra shows up
            assertThat(report.structure().missingPaths()).isEmpty();
            assertThat(report.structure().newPaths()).isEmpty();
            assertThat(report.examples().added()).extracting(KeyRef::value).containsExactly("2000", "2001", "2002");
            assertThat(report.examples().removed()).isEmpty();
            // the candidate is smaller, so only it is read; the baseline is looked up
            assertThat(report.run().baselineCount()).isEqualTo(1000);
            assertThat(report.run().candidateCount()).isEqualTo(103);
            assertThat(report.run().baselineDocsRead()).isEqualTo(100);
            assertThat(report.run().candidateDocsRead()).isEqualTo(103);
            assertThat(report.run().settings().matchedOnly()).isTrue();
            assertThat(report.run().decisions()).contains(
                    "Mode AUTO chose FULL: matched-only reads the smaller side, 103 documents, full-scan limit"
                            + " 5,000,000",
                    "Matched-only: compared only documents whose key exists on both sides, by reading all of the"
                            + " candidate (103 documents) and looking up its keys in the baseline; documents on one"
                            + " side only are counted but not compared, and keySimilarity is not judged");
            assertThat(report.hints()).noneMatch(hint -> hint.kind() == Hint.Kind.MATCHED_ONLY);
        }

        @Test
        void readsTheBaselineWhenItIsSmaller() {
            createSubset();

            ComparisonReport report = comparator.compare(ComparisonRequest.builder("sub_set", "full_set")
                    .matchedOnly().build());

            assertThat(report.verdict()).isEqualTo(Level.GREEN);
            assertThat(report.keys().matched()).isEqualTo(100);
            assertThat(report.keys().removed()).isEqualTo(3);
            assertThat(report.keys().added()).isEqualTo(900);
            assertThat(report.examples().removed()).extracting(KeyRef::value).containsExactly("2000", "2001", "2002");
            assertThat(report.run().baselineDocsRead()).isEqualTo(103);
            assertThat(report.run().candidateDocsRead()).isEqualTo(100);
        }

        @Test
        void sampleOfTheSmallerSide() {
            createSubset();

            ComparisonReport report = comparator.compare(ComparisonRequest.builder("full_set", "sub_set")
                    .matchedOnly().sample(50).build());

            assertThat(report.run().candidateDocsRead()).isEqualTo(50);
            assertThat(report.keys().keySimilarity()).isInstanceOf(Rate.Estimate.class);
            assertThat(report.keys().matched()).isBetween(80L, 103L);
            assertThat(report.content().unchangedRate()).isInstanceOf(Rate.Estimate.class);
            assertThat(rule(report, "keySimilarity").level()).isEqualTo(Level.GREEN);
            assertThat(report.structure().missingPaths()).isEmpty();
            assertThat(report.run().decisions()).anyMatch(decision -> decision.startsWith(
                    "Matched-only: compared only documents whose key exists on both sides, by reading a sample of 50"
                            + " keys of the candidate (103 documents)"));
        }

        @Test
        void noSharedKeyIsRed() {
            Collections.create(DB, "disjoint_a", documents(10, i -> "{_id: " + i + "}"));
            Collections.create(DB, "disjoint_b", documents(10, i -> "{_id: " + (i + 100) + "}"));

            ComparisonReport report = comparator.compare(ComparisonRequest.builder("disjoint_a", "disjoint_b")
                    .matchedOnly().build());

            assertThat(report.verdict()).isEqualTo(Level.RED);
            assertThat(rule(report, "keySimilarity").reason()).startsWith("No key exists on both sides");
        }

        /** 1000 documents, 900 of them archived; the subset holds 100 unarchived ones (2 repriced) plus 3 new. */
        private void createSubset() {
            Collections.create(DB, "full_set", documents(1000, i -> "{_id: " + i + ", name: 'n" + i + "', price: " + i
                    + (i >= 100 ? ", archived: true" : "") + "}"));
            List<BsonDocument> subset = new ArrayList<>(documents(100, i -> "{_id: " + i + ", name: 'n" + i
                    + "', price: " + (i < 2 ? i + 1 : i) + "}"));
            subset.addAll(documents(3, i -> "{_id: " + (2000 + i) + ", name: 'new', price: 1, extra: true}"));
            Collections.create(DB, "sub_set", subset);
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

        // without configuration the map is detected; the technical fields still show up as changes
        ComparisonReport plain = compare("wild_a", "wild_b");
        assertThat(plain.run().settings().wildcardPaths()).containsExactly("attributes.*");
        assertThat(plain.run().decisions()).anyMatch(decision -> decision.startsWith(
                "Treated attributes.* as a map with dynamic keys"));
        assertThat(plain.topChangedPaths()).extracting(PathChange::path)
                .containsExactly("attributes.*", "items[].etag", "syncedAt");

        ComparisonReport configured = comparator.compare(ComparisonRequest.builder("wild_a", "wild_b")
                .ignoredPaths("syncedAt", "items[].etag").wildcardPaths("attributes.*").build());
        assertThat(configured.topChangedPaths()).extracting(PathChange::path).containsExactly("attributes.*");
        assertThat(configured.topChangedPaths().getFirst().changedDocs()).isEqualTo(100);
        assertThat(configured.structure().baselinePaths()).isEqualTo(6); // _id, attributes, attributes.*, items, items[], items[].p
        assertThat(configured.run().decisions()).noneMatch(decision -> decision.startsWith("Treated"));
    }

    @Test
    void expectedChangePathsKeepChurnFromTurningRed() {
        // prices and stock change in most documents every run; one name change is a real finding
        Collections.create(DB, "churn_a", documents(100, i -> "{_id: " + i + ", name: 'n" + i
                + "', price: " + i + ", stock: {qty: " + i + ", at: 1}}"));
        Collections.create(DB, "churn_b", documents(100, i -> "{_id: " + i + ", name: '" + (i == 5 ? "renamed" : "n" + i)
                + "', price: " + (i % 4 == 0 ? i : i + 1) + ", stock: {qty: " + (i + 2) + ", at: 2}}"));

        ComparisonReport plain = compare("churn_a", "churn_b");
        assertThat(plain.verdict()).isEqualTo(Level.RED);

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("churn_a", "churn_b")
                .expectedChangePaths("price", "stock").build());

        assertThat(report.verdict()).isEqualTo(Level.GREEN);
        assertThat(report.content().changed()).isEqualTo(100);
        assertThat(report.content().changedExpectedOnly()).isEqualTo(99);
        assertThat(report.content().unchangedOrExpectedRate().value()).isEqualTo(0.99);
        assertThat(report.topChangedPaths()).extracting(PathChange::path, PathChange::expected).containsExactly(
                org.assertj.core.groups.Tuple.tuple("stock.at", true),
                org.assertj.core.groups.Tuple.tuple("stock.qty", true),
                org.assertj.core.groups.Tuple.tuple("price", true),
                org.assertj.core.groups.Tuple.tuple("name", false));
        assertThat(rule(report, "maxPathChangeRate").observed()).isEqualTo(0.01);
    }

    @Test
    void valueExamplesShowBeforeAndAfter() {
        Collections.create(DB, "values_a", documents(10, i -> "{_id: " + i + ", price: {$numberDecimal: '" + i
                + ".50'}, customer: {email: 'c" + i + "@example.com'}}"));
        Collections.create(DB, "values_b", documents(10, i -> "{_id: " + i + ", price: 0, customer: {email: 'x" + i
                + "@example.com'}}"));

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("values_a", "values_b")
                .redactedPaths("customer").build());

        PathChange price = report.topChangedPaths().stream().filter(c -> c.path().equals("price")).findFirst()
                .orElseThrow();
        assertThat(price.valueExamples()).hasSize(3).first().satisfies(change -> {
            assertThat(change.key()).isEqualTo(new KeyRef("INT32", "0"));
            assertThat(change.baseline()).containsExactly("0.5");
            assertThat(change.candidate()).containsExactly("0");
        });
        PathChange email = report.topChangedPaths().stream().filter(c -> c.path().equals("customer.email"))
                .findFirst().orElseThrow();
        assertThat(email.valueExamples()).allSatisfy(change -> {
            assertThat(change.baseline()).containsExactly("***");
            assertThat(change.candidate()).containsExactly("***");
        });
    }

    @Test
    void hintsTellAFirstTimeUserWhatToConfigure() {
        // a sync timestamp changes everywhere, prices churn in 30%, names were re-formatted in every document
        Collections.create(DB, "hint_a", documents(200, i -> "{_id: " + i + ", name: 'item " + i + "', price: " + i
                + ", syncedAt: {$date: '2026-01-01T00:00:00Z'}}"));
        Collections.create(DB, "hint_b", documents(200, i -> "{_id: " + i + ", name: 'ITEM " + i + "', price: "
                + (i % 10 < 3 ? i + 1 : i) + ", syncedAt: {$date: '2026-01-02T00:00:00Z'}}"));

        ComparisonReport report = compare("hint_a", "hint_b");

        assertThat(report.verdict()).isEqualTo(Level.RED);
        assertThat(report.hints()).extracting(Hint::kind, Hint::path, Hint::cliOption).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Hint.Kind.INVESTIGATE, "name", null),
                org.assertj.core.groups.Tuple.tuple(Hint.Kind.IGNORE_TECHNICAL_FIELD, "syncedAt", "--ignore=syncedAt"),
                org.assertj.core.groups.Tuple.tuple(Hint.Kind.EXPECTED_CHANGE, "price", "--expected=price"));
        assertThat(report.hints().get(1).property()).isEqualTo("ditto.ignored-paths=syncedAt");

        // following the configuration hints leaves only the real finding
        ComparisonReport configured = comparator.compare(ComparisonRequest.builder("hint_a", "hint_b")
                .ignoredPaths("syncedAt").expectedChangePaths("price").build());
        assertThat(configured.hints()).extracting(Hint::kind).containsExactly(Hint.Kind.INVESTIGATE);
    }

    @Test
    void hintsAboutReplacedKeys() {
        Collections.create(DB, "rekey_a", documents(100, i -> "{_id: " + i + ", v: 1}"));
        Collections.create(DB, "rekey_b", documents(100, i -> "{_id: '" + i + "', v: 1}"));

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("rekey_a", "rekey_b")
                .mixedKeyPolicy(MixedKeyPolicy.COMPARE).build());

        assertThat(report.hints()).singleElement().satisfies(hint -> {
            assertThat(hint.kind()).isEqualTo(Hint.Kind.INVESTIGATE);
            assertThat(hint.message()).startsWith("Most keys were replaced");
        });
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
