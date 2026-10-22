package dev.jbaby.ditto.opensearch.it;

import static dev.jbaby.ditto.opensearch.support.Indices.documents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.ComparisonReport.TypeShare;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.Hint;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.Rate;
import dev.jbaby.ditto.comparator.api.Store;
import dev.jbaby.ditto.comparator.api.ValueChange;
import dev.jbaby.ditto.opensearch.IndexComparator;
import dev.jbaby.ditto.opensearch.support.Indices;
import dev.jbaby.ditto.opensearch.support.TestApplication;

/**
 * End-to-end scenarios through the auto-configured {@link IndexComparator} against a real OpenSearch.
 */
@SpringBootTest(classes = TestApplication.class, properties = TestApplication.WITHOUT_MONGO)
class IndexComparatorIT {

    @DynamicPropertySource
    static void openSearch(DynamicPropertyRegistry registry) {
        TestApplication.openSearchProperties(registry);
    }

    @Autowired
    IndexComparator comparator;

    @Test
    void identicalIndicesAreGreen() {
        var docs = documents(200, i -> "{_id: 'k" + i + "', name: 'n" + i + "', tags: ['a', 'b'], meta: {v: 1}}");
        Indices.create("same_backup", docs);
        Indices.create("same", docs);

        ComparisonReport report = comparator.compareWithBackup("same");

        assertThat(report.verdict()).isEqualTo(Level.GREEN);
        assertThat(report.keys().matched()).isEqualTo(200);
        assertThat(report.content().unchanged()).isEqualTo(200);
        assertThat(report.topChangedPaths()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.run().settings().store()).isEqualTo(Store.OPENSEARCH);
        assertThat(report.run().settings().baseline().database()).isEqualTo("docker-cluster");
        assertThat(report.run().settings().baseline()).hasToString("docker-cluster.same_backup");
        assertThat(report.run().settings().mode()).isEqualTo(ComparisonMode.full());
        assertThat(report.run().decisions()).containsExactly(
                "Mode AUTO chose FULL: 200 and 200 documents, full-scan limit 5,000,000",
                "Full comparison by key lookups: read the baseline and looked up its keys in the candidate, then read"
                        + " the candidate's keys and looked them up in the baseline (the store cannot return"
                        + " documents sorted by key)");
    }

    @Test
    void classifiesChangedAddedAndRemovedDocuments() {
        Indices.create("cls_a", "{_id: '1', name: 'a', price: 10}", "{_id: '2', name: 'b', price: 20}",
                "{_id: '3', name: 'c', price: 30, tags: ['x', 'y']}", "{_id: '5', name: 'e', price: 50}");
        Indices.create("cls_b", "{_id: '0', name: 'z', price: 0}", "{price: 10, name: 'a', _id: '1'}",
                "{_id: '2', name: 'B', price: 20}", "{_id: '3', name: 'c', price: 30.0, tags: ['y', 'x']}",
                "{_id: '4', name: 'd', price: 40}");

        ComparisonReport report = comparator.compare("cls_a", "cls_b");

        assertThat(report.keys().matched()).isEqualTo(3);
        assertThat(report.keys().added()).isEqualTo(2);
        assertThat(report.keys().removed()).isEqualTo(1);
        assertThat(report.content().unchanged()).isEqualTo(2);
        assertThat(report.content().changed()).isEqualTo(1);
        assertThat(report.topChangedPaths()).singleElement().satisfies(change -> {
            assertThat(change.path()).isEqualTo("name");
            assertThat(change.valueExamples()).containsExactly(
                    new ValueChange(key("2"), List.of("\"b\""), List.of("\"B\"")));
        });
        assertThat(report.examples().changed()).containsExactly(key("2"));
        assertThat(report.examples().added()).containsExactlyInAnyOrder(key("0"), key("4"));
        assertThat(report.examples().removed()).containsExactly(key("5"));
        assertThat(report.run().baselineCount()).isEqualTo(4);
        assertThat(report.run().candidateCount()).isEqualTo(5);
        // 30 vs 30.0: same content, but the type histogram differs
        assertThat(report.structure().typeShifts()).singleElement().satisfies(shift -> {
            assertThat(shift.path()).isEqualTo("price");
            assertThat(shift.candidate()).contains(new TypeShare("DOUBLE", 1, 0.2));
        });
    }

    @Test
    void reorderedArraysAndFieldsAreUnchangedUnlessOrderSensitive() {
        Indices.create("order_a", documents(50,
                i -> "{_id: '" + i + "', steps: ['a', 'b'], items: [{sku: 1, q: 2}, {sku: 2, q: 1}], m: {a: 1, b: 2}}"));
        Indices.create("order_b", documents(50,
                i -> "{m: {b: 2, a: 1}, items: [{q: 1, sku: 2}, {q: 2, sku: 1}], steps: ['b', 'a'], _id: '" + i + "'}"));

        assertThat(comparator.compare("order_a", "order_b").content().unchanged()).isEqualTo(50);

        ComparisonReport ordered = comparator.compare(ComparisonRequest.builder("order_a", "order_b")
                .orderSensitivePaths("steps").build());
        assertThat(ordered.content().changed()).isEqualTo(50);
        assertThat(ordered.topChangedPaths()).extracting(PathChange::path).containsExactly("steps[]");
    }

    @Test
    void ignoredAndExpectedPathsAndRedaction() {
        Indices.create("opt_a", documents(100, i -> "{_id: '" + i + "', price: " + i + ", secret: 'old', at: 1}"));
        Indices.create("opt_b", documents(100, i -> "{_id: '" + i + "', price: " + (i % 10 == 0 ? i + 1 : i)
                + ", secret: 'new', at: 2}"));

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("opt_a", "opt_b")
                .ignoredPaths("at").expectedChangePaths("price").redactedPaths("secret").build());

        assertThat(report.topChangedPaths()).extracting(PathChange::path).containsExactly("secret", "price");
        assertThat(report.topChangedPaths().getFirst().valueExamples()).first()
                .satisfies(example -> assertThat(example.baseline()).containsExactly("***"));
        assertThat(report.content().changedExpectedOnly()).isZero();
    }

    @Test
    void mapsWithDynamicKeysAreDetected() {
        Indices.create("map_a", documents(60, i -> "{_id: '" + i + "', stock: {s" + i + ": 1, t" + i + ": 2}}"));
        Indices.create("map_b", documents(60, i -> "{_id: '" + i + "', stock: {s" + i + ": 1, t" + i + ": 3}}"));

        ComparisonReport report = comparator.compare("map_a", "map_b");

        assertThat(report.run().settings().wildcardPaths()).containsExactly("stock.*");
        assertThat(report.topChangedPaths()).extracting(PathChange::path).containsExactly("stock.*");
    }

    @Test
    void isoTimestampsChangedEverywhereGetAnIgnoreHint() {
        Indices.create("ts_a", documents(100, i -> "{_id: '" + i + "', v: 1, syncedAt: '2026-10-21T08:00:0"
                + i % 10 + "Z'}"));
        Indices.create("ts_b", documents(100, i -> "{_id: '" + i + "', v: 1, syncedAt: '2026-10-22T08:00:0"
                + i % 10 + "Z'}"));

        ComparisonReport report = comparator.compare("ts_a", "ts_b");

        assertThat(report.hints()).extracting(Hint::kind).contains(Hint.Kind.IGNORE_TECHNICAL_FIELD);
        assertThat(report.hints()).filteredOn(hint -> hint.kind() == Hint.Kind.IGNORE_TECHNICAL_FIELD)
                .singleElement().satisfies(hint -> assertThat(hint.property()).isEqualTo("ditto.ignored-paths=syncedAt"));
    }

    @Test
    void sampleModeEstimates() {
        Indices.create("smp_a", documents(3000, i -> "{_id: '" + i + "', v: " + i + "}"));
        Indices.create("smp_b", documents(3000, i -> "{_id: '" + (i + 100) + "', v: " + (i % 10 == 0 ? -i : i)
                + "}"));

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("smp_a", "smp_b")
                .mode(ComparisonMode.sample(600)).build());

        assertThat(report.keys().keySimilarity()).isInstanceOf(Rate.Estimate.class);
        assertThat(report.run().baselineDocsRead()).isEqualTo(600);
        // 100 of 3000 keys removed and added: about 3%; changed v in about 10% of matched documents... of the
        // shifted keys nearly every v differs
        assertThat(report.keys().removedRate().value()).isBetween(0.005, 0.08);
        assertThat(report.keys().addedRate().value()).isBetween(0.005, 0.08);
    }

    @Test
    void matchedOnlyReadsTheSmallerSide() {
        Indices.create("mo_big", documents(500, i -> "{_id: '" + i + "', v: " + i + "}"));
        Indices.create("mo_small", documents(20, i -> "{_id: '" + i * 10 + "', v: " + (i == 0 ? -1 : i * 10)
                + "}"));

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("mo_big", "mo_small")
                .matchedOnly(true).build());

        assertThat(report.keys().matched()).isEqualTo(20);
        assertThat(report.content().changed()).isEqualTo(1);
        assertThat(report.run().candidateDocsRead()).isEqualTo(20);
        assertThat(report.run().baselineDocsRead()).isEqualTo(20);
    }

    @Test
    void customKeyFields() {
        String mapping = "{properties: {sku: {type: 'keyword'}, code: {type: 'text', fields: {raw: {type: 'keyword'}}},"
                + " nr: {type: 'long'}, title: {type: 'text'}}}";
        Indices.create("key_a", mapping, 2, documents(30, i -> "{sku: 'S" + i + "', code: 'C " + i + "', nr: " + i
                + ", title: 't', v: " + i + "}"));
        Indices.create("key_b", mapping, 3, documents(30, i -> "{sku: 'S" + i + "', code: 'C " + i + "', nr: " + i
                + ", title: 't', v: " + i + "}"));

        for (String key : List.of("sku", "code", "nr")) {
            ComparisonReport report = comparator.compare(ComparisonRequest.builder("key_a", "key_b").keyField(key)
                    .build());
            assertThat(report.keys().matched()).as(key).isEqualTo(30);
            assertThat(report.content().unchanged()).as(key).isEqualTo(30);
        }
        assertThat(comparator.compare(ComparisonRequest.builder("key_a", "key_b").keyField("nr").build())
                .examples().changed()).isEmpty();
        assertThatThrownBy(() -> comparator.compare(ComparisonRequest.builder("key_a", "key_b").keyField("title")
                .build())).isInstanceOf(ComparisonException.class)
                .hasMessageContaining("mapped as text").hasMessageContaining("keyword subfield");
        assertThatThrownBy(() -> comparator.compare(ComparisonRequest.builder("key_a", "key_b").keyField("nope")
                .build())).isInstanceOf(ComparisonException.class).hasMessageContaining("is not mapped");
    }

    @Test
    void missingAndDuplicateKeysAreRejected() {
        String mapping = "{properties: {sku: {type: 'keyword'}}}";
        Indices.create("dup_a", mapping, 1, List.of("{sku: 'a'}", "{sku: 'b'}", "{sku: 'b'}"));
        Indices.create("dup_b", mapping, 1, List.of("{sku: 'a'}", "{sku: 'b'}"));
        Indices.create("nokey_b", mapping, 1, List.of("{sku: 'a'}", "{other: 1}"));

        assertThatThrownBy(() -> comparator.compare(ComparisonRequest.builder("dup_a", "dup_b").keyField("sku")
                .build())).isInstanceOf(ComparisonException.class).hasMessageContaining("Duplicate key \"b\"");
        assertThatThrownBy(() -> comparator.compare(ComparisonRequest.builder("dup_b", "nokey_b").keyField("sku")
                .build())).isInstanceOf(ComparisonException.class)
                .hasMessageContaining("missing in 1 documents of the candidate index nokey_b");
    }

    @Test
    void sameIdWithDifferentRoutingIsADuplicate() {
        Indices.create("route_a", "{}", 5, documents(5, i -> "{_id: 'same', _routing: 'r" + i + "', v: " + i + "}"));
        Indices.create("route_b", "{_id: 'same', v: 1}");

        assertThatThrownBy(() -> comparator.compare("route_a", "route_b")).isInstanceOf(ComparisonException.class)
                .hasMessageContaining("must be unique");
    }

    @Test
    void indicesMustExistAndBeSingle() {
        Indices.delete("nothing_here");
        Indices.create("one_a", "{_id: '1'}");
        Indices.create("one_b", "{_id: '1'}");
        Indices.alias("two", "one_a", "one_b");
        Indices.alias("just_one", "one_a");

        assertThatThrownBy(() -> comparator.compare("nothing_here", "one_b")).isInstanceOf(ComparisonException.class)
                .hasMessage("The baseline index nothing_here does not exist");
        assertThatThrownBy(() -> comparator.compare("two", "one_b")).isInstanceOf(ComparisonException.class)
                .hasMessageContaining("points to 2 indices");
        assertThatThrownBy(() -> comparator.compare("one_*", "one_b")).isInstanceOf(ComparisonException.class)
                .hasMessageContaining("is not the name of an index or alias");
        assertThat(comparator.compare("just_one", "one_b").verdict()).isEqualTo(Level.GREEN);
    }

    @Test
    void indicesWithoutSourceCannotBeCompared() {
        Indices.create("nosrc_a", "{_source: {enabled: false}}", 1, List.of("{_id: '1', v: 1}"));
        Indices.create("nosrc_b", "{_id: '1', v: 1}");

        assertThatThrownBy(() -> comparator.compare("nosrc_a", "nosrc_b")).isInstanceOf(ComparisonException.class)
                .hasMessageContaining("does not store _source");
    }

    @Test
    void switchedOffRefreshIsAWarning() {
        Indices.create("norefresh_a", "{_id: '1', v: 1}");
        Indices.create("norefresh_b", "{_id: '1', v: 1}");
        Indices.settings("norefresh_b", "{index: {refresh_interval: '-1'}}");

        ComparisonReport report = comparator.compare("norefresh_a", "norefresh_b");

        assertThat(report.warnings()).singleElement().asString().contains("refresh_interval=-1");
    }

    @Test
    void scrollsThroughManyPages() {
        List<String> base = new ArrayList<>();
        List<String> cand = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            String doc = "{_id: 'k" + i + "', v: " + i + ", items: [{n: 1}, {n: 2}]}";
            if (i % 10 != 0) {
                base.add(doc);
            }
            if (i % 7 != 0) {
                cand.add(i % 100 == 1 ? doc.replace("v: " + i, "v: " + -i) : doc);
            }
        }
        Indices.create("big_a", base);
        Indices.create("big_b", cand);

        ComparisonReport report = comparator.compare("big_a", "big_b");

        long both = java.util.stream.IntStream.range(0, 2500).filter(i -> i % 10 != 0 && i % 7 != 0).count();
        long changed = java.util.stream.IntStream.range(0, 2500)
                .filter(i -> i % 10 != 0 && i % 7 != 0 && i % 100 == 1).count();
        assertThat(report.keys().matched()).isEqualTo(both);
        assertThat(report.content().changed()).isEqualTo(changed);
        assertThat(report.keys().removed()).isEqualTo(base.size() - both);
        assertThat(report.keys().added()).isEqualTo(cand.size() - both);
        assertThat(report.run().baselineDocsRead()).isEqualTo(base.size());
        assertThat(report.run().candidateDocsRead()).isEqualTo(cand.size());
        Map<String, Long> scrolls = openScrolls();
        assertThat(scrolls.values()).as("scrolls are cleared").allMatch(open -> open == 0);
    }

    private static Map<String, Long> openScrolls() {
        var stats = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(Indices.send("GET", "/_nodes/stats/indices/search", null, false));
        return stats.path("nodes").properties().stream().collect(Collectors.toMap(Map.Entry::getKey,
                node -> node.getValue().path("indices").path("search").path("scroll_current").asLong()));
    }

    private static KeyRef key(String id) {
        return new KeyRef("STRING", "\"" + id + "\"");
    }
}
