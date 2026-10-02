package dev.jbaby.ditto.comparator.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.Progress;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.key.KeyInspector;
import dev.jbaby.ditto.comparator.metrics.PathChangeStats.PathChangeCount;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;
import dev.jbaby.ditto.comparator.support.Collections;

class MergeJoinIT {

    private static final MongoDatabase DB = Collections.database("merge_join");

    @Test
    void classifiesKeysAndContent() {
        Collections.create(DB, "base",
                "{_id: 1, name: 'a', price: 10}",
                "{_id: 2, name: 'b', price: 20}",
                "{_id: 3, name: 'c', price: 30, tags: ['x', 'y']}",
                "{_id: 5, name: 'e', price: 50}");
        Collections.create(DB, "cand",
                "{_id: 0, name: 'z', price: 0}",
                "{price: 10, name: 'a', _id: 1}",
                "{_id: 2, name: 'B', price: 20}",
                "{_id: 3, name: 'c', price: 30.0, tags: ['y', 'x']}",
                "{_id: 4, name: 'd', price: 40}");

        ScanResult result = compare(ComparisonRequest.of("base", "cand"));

        assertThat(result.matched()).isEqualTo(3);
        assertThat(result.unchanged()).isEqualTo(2);
        assertThat(result.changed()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.removed()).isEqualTo(1);
        assertThat(result.baselineDocsRead()).isEqualTo(4);
        assertThat(result.candidateDocsRead()).isEqualTo(5);
        assertThat(result.pathChanges()).containsExactly(new PathChangeCount("name", 1, List.of(key(2)),
                List.of(new dev.jbaby.ditto.comparator.api.ValueChange(key(2), List.of("\"b\""), List.of("\"B\"")))));
        assertThat(result.changedExamples()).containsExactly(key(2));
        assertThat(result.addedExamples()).containsExactly(key(0), key(4));
        assertThat(result.removedExamples()).containsExactly(key(5));
        // int 30 vs double 30.0: same content, but the type histogram differs
        assertThat(result.baselineProfile().paths().get("price").types()).doesNotContainKey(org.bson.BsonType.DOUBLE);
        assertThat(result.candidateProfile().paths().get("price").types()).containsKey(org.bson.BsonType.DOUBLE);
    }

    @Test
    void streamsLargeCollectionsInKeyOrder() {
        List<BsonDocument> base = new ArrayList<>();
        List<BsonDocument> cand = new ArrayList<>();
        IntStream.range(0, 5000).forEach(i -> {
            var doc = new BsonDocument("_id", new org.bson.BsonString(String.format("k%05d", i)))
                    .append("v", new org.bson.BsonInt32(i))
                    .append("items", org.bson.BsonArray.parse("[{\"n\": 1}, {\"n\": 2}]"));
            if (i % 10 != 0) {
                base.add(doc);
            }
            if (i % 7 != 0) {
                cand.add(i % 100 == 1 ? doc.clone().append("v", new org.bson.BsonInt32(-i)) : doc);
            }
        });
        Collections.create(DB, "big_base", base);
        Collections.create(DB, "big_cand", cand);
        List<Progress> progress = new ArrayList<>();

        ScanResult result = compare(ComparisonRequest.of("big_base", "big_cand"), progress::add);

        long both = IntStream.range(0, 5000).filter(i -> i % 10 != 0 && i % 7 != 0).count();
        long changed = IntStream.range(0, 5000).filter(i -> i % 10 != 0 && i % 7 != 0 && i % 100 == 1).count();
        assertThat(result.matched()).isEqualTo(both);
        assertThat(result.changed()).isEqualTo(changed);
        assertThat(result.removed()).isEqualTo(base.size() - both);
        assertThat(result.added()).isEqualTo(cand.size() - both);
        assertThat(result.pathChanges()).extracting(PathChangeCount::path).containsExactly("v");
        assertThat(result.changedExamples()).hasSize(20).first().isEqualTo(new KeyRef("STRING", "\"k00001\""));
        assertThat(progress).last().satisfies(last -> {
            assertThat(last.baselineDocs()).isEqualTo(base.size());
            assertThat(last.candidateDocs()).isEqualTo(cand.size());
        });
    }

    @Test
    void customKeyFieldWithDuplicatesIsRejected() {
        Collections.create(DB, "dup_base", "{sku: 'a'}", "{sku: 'b'}", "{sku: 'b'}");
        Collections.create(DB, "dup_cand", "{sku: 'a'}");

        assertThatThrownBy(() -> compare(ComparisonRequest.builder("dup_base", "dup_cand").keyField("sku").build()))
                .isInstanceOf(ComparisonException.class)
                .hasMessageContaining("Duplicate key \"b\"");
    }

    @Test
    void customKeyFieldMatchesAcrossDifferentIds() {
        Collections.create(DB, "sku_base", "{_id: 1, sku: 'a', v: 1}", "{_id: 2, sku: 'b', v: 2}");
        Collections.create(DB, "sku_cand", "{_id: 10, sku: 'b', v: 2}", "{_id: 11, sku: 'a', v: 1}");

        ScanResult result = compare(ComparisonRequest.builder("sku_base", "sku_cand").keyField("sku").build());

        assertThat(result.matched()).isEqualTo(2);
        // _id is a regular field when it is not the key
        assertThat(result.changed()).isEqualTo(2);
        assertThat(result.pathChanges()).extracting(PathChangeCount::path).containsExactly("_id");
    }

    @Test
    void preflightRejectsMissingCollectionsAndMixedKeys() {
        Collections.create(DB, "mixed_base", "{_id: 1}", "{_id: 'one'}");
        Collections.create(DB, "mixed_cand", "{_id: 1}");
        DB.getCollection("does_not_exist").drop();

        assertThatThrownBy(() -> compare(ComparisonRequest.of("mixed_base", "does_not_exist")))
                .hasMessageContaining("does not exist");
        assertThatThrownBy(() -> compare(ComparisonRequest.of("mixed_base", "mixed_cand")))
                .hasMessageContaining("mixed types [NUMBER, STRING]");
        ScanResult result = compare(ComparisonRequest.builder("mixed_base", "mixed_cand")
                .mixedKeyPolicy(dev.jbaby.ditto.comparator.api.MixedKeyPolicy.COMPARE).build());
        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.removedExamples()).containsExactly(new KeyRef("STRING", "\"one\""));
    }

    @Test
    void preflightWarnsAboutUnusableIndexes() {
        Collections.create(DB, "idx_base", "{sku: 'a'}");
        DB.getCollection("idx_cand").drop();
        DB.createCollection("idx_cand", new CreateCollectionOptions()
                .collation(com.mongodb.client.model.Collation.builder().locale("de").build()));

        var settings = settings(ComparisonRequest.builder("idx_base", "idx_cand").keyField("sku").build());
        var preflight = new Preflight(new KeyInspector()).run(handle("baseline", settings.baseline()),
                handle("candidate", settings.candidate()), settings);
        assertThat(preflight.warnings()).hasSize(2).allMatch(w -> w.contains("no simple-collation index"));

        var idSettings = settings(ComparisonRequest.of("idx_base", "idx_cand"));
        var idPreflight = new Preflight(new KeyInspector()).run(handle("baseline", idSettings.baseline()),
                handle("candidate", idSettings.candidate()), idSettings);
        assertThat(idPreflight.warnings()).singleElement().asString().contains("default collation 'de'");
    }

    @Test
    void interruptionStopsTheScan() {
        List<BsonDocument> docs = IntStream.range(0, 2000)
                .mapToObj(i -> new BsonDocument("_id", new org.bson.BsonInt32(i))).toList();
        Collections.create(DB, "int_base", docs);
        Collections.create(DB, "int_cand", docs);

        Thread.currentThread().interrupt();
        try {
            // either the driver (while waiting for I/O) or the progress check notices the interrupt
            assertThatThrownBy(() -> compare(ComparisonRequest.of("int_base", "int_cand")))
                    .isInstanceOfAny(ComparisonException.class, com.mongodb.MongoInterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }

    private static ScanResult compare(ComparisonRequest request) {
        return compare(request, _ -> {
        });
    }

    private static ScanResult compare(ComparisonRequest request, dev.jbaby.ditto.comparator.api.ProgressListener listener) {
        ComparisonSettings settings = settings(request);
        CollectionHandle baseline = handle("baseline", settings.baseline());
        CollectionHandle candidate = handle("candidate", settings.candidate());
        var preflight = new Preflight(new KeyInspector()).run(baseline, candidate, settings);
        var progress = new ProgressReporter("test", Duration.ofMillis(1),
                preflight.baselineCount() + preflight.candidateCount(), listener);
        return new MergeJoinComparator().compare(baseline, candidate, settings,
                new ScanAccumulator(settings, new Hasher(new CanonicalEncoder())), progress);
    }

    private static ComparisonSettings settings(ComparisonRequest request) {
        return new ComparatorProperties().settingsFor(request, DB.getName());
    }

    private static CollectionHandle handle(String side, CollectionRef ref) {
        return CollectionHandle.of(side, ref, DB);
    }

    private static KeyRef key(int id) {
        return new KeyRef("INT32", Integer.toString(id));
    }
}
