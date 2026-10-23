package dev.jbaby.ditto.opensearch.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonReport.PathChange;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.Hint;
import dev.jbaby.ditto.comparator.api.Store;
import dev.jbaby.ditto.mongo.CollectionComparator;
import dev.jbaby.ditto.opensearch.IndexComparator;
import dev.jbaby.ditto.opensearch.support.Indices;
import dev.jbaby.ditto.opensearch.support.TestApplication;

/**
 * The same documents in a MongoDB collection and an OpenSearch index give the same report: ditto measures content,
 * not the store. Also shows that both comparators live side by side in one application.
 */
@SpringBootTest(classes = TestApplication.class)
class MongoParityIT {

    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) {
        TestApplication.openSearchProperties(registry);
        MONGO.start();
        registry.add("spring.mongodb.uri", () -> MONGO.getConnectionString() + "/parity");
    }

    @Autowired
    CollectionComparator collections;

    @Autowired
    IndexComparator indices;

    @Test
    void sameDocumentsGiveTheSameReport() {
        List<String> baseline = new ArrayList<>();
        List<String> candidate = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String id = String.format("d%04d", i);
            String doc = "{_id: '" + id + "', name: 'item " + i + "', price: " + (i * 1.25) + ", qty: " + i
                    + ", active: " + (i % 2 == 0) + ", note: null, tags: ['t" + i % 3 + "', 'all'],"
                    + " dims: {w: " + i % 7 + ", h: 2.5}, variants: [{sku: '" + id + "-a', n: 1}, {sku: '" + id
                    + "-b', n: 2}], legacy: 'x'}";
            if (i % 50 != 0) {
                baseline.add(doc);
            }
            if (i % 40 == 1) {
                continue;
            }
            String changed = doc;
            if (i % 10 == 0) {
                changed = changed.replace("price: " + (i * 1.25), "price: " + (i * 1.25 + 1));
            }
            if (i % 25 == 0) {
                changed = changed.replace("tags: ['t" + i % 3 + "', 'all']", "tags: ['all', 'new']");
            }
            if (i % 4 == 0) {
                changed = changed.replace(", legacy: 'x'", "");
            }
            if (i % 8 == 0) {
                changed = changed.replace("qty: " + i, "qty: '" + i + "'");
            }
            candidate.add(changed.replace("active:", "extra: {flag: true}, active:"));
        }
        MongoDatabase mongo = MongoClients.create(MONGO.getConnectionString()).getDatabase("parity");
        mongo.drop();
        mongo.getCollection("items_backup", BsonDocument.class).insertMany(baseline.stream().map(BsonDocument::parse)
                .toList());
        mongo.getCollection("items", BsonDocument.class).insertMany(candidate.stream().map(BsonDocument::parse)
                .toList());
        Indices.create("items_backup", baseline);
        Indices.create("items", candidate);

        ComparisonReport fromMongo = collections.compareWithBackup("items");
        ComparisonReport fromOpenSearch = indices.compareWithBackup("items");

        assertThat(fromMongo.run().settings().store()).isEqualTo(Store.MONGODB);
        assertThat(fromOpenSearch.run().settings().store()).isEqualTo(Store.OPENSEARCH);
        assertThat(fromOpenSearch.verdict()).isEqualTo(fromMongo.verdict());
        assertThat(fromOpenSearch.rules()).extracting(RuleResult::rule, RuleResult::level, RuleResult::observed)
                .isEqualTo(fromMongo.rules().stream().map(rule -> org.assertj.core.groups.Tuple.tuple(rule.rule(),
                        rule.level(), rule.observed())).toList());
        assertThat(fromOpenSearch.keys()).isEqualTo(fromMongo.keys());
        assertThat(fromOpenSearch.content()).isEqualTo(fromMongo.content());
        assertThat(fromOpenSearch.topChangedPaths())
                .extracting(PathChange::path, PathChange::changedDocs, PathChange::changeRate)
                .isEqualTo(fromMongo.topChangedPaths().stream().map(change -> org.assertj.core.groups.Tuple.tuple(
                        change.path(), change.changedDocs(), change.changeRate())).toList());
        var structure = fromOpenSearch.structure();
        assertThat(structure.newPaths()).isEqualTo(fromMongo.structure().newPaths());
        assertThat(structure.missingPaths()).isEqualTo(fromMongo.structure().missingPaths());
        assertThat(structure.typeShifts()).isEqualTo(fromMongo.structure().typeShifts()).isNotEmpty();
        // in MongoDB _id is a field of the document, in OpenSearch metadata outside _source
        assertThat(structure.baselinePaths()).isEqualTo(fromMongo.structure().baselinePaths() - 1);
        assertThat(structure.candidatePaths()).isEqualTo(fromMongo.structure().candidatePaths() - 1);
        assertThat(new HashSet<>(fromOpenSearch.examples().added()))
                .isEqualTo(new HashSet<>(fromMongo.examples().added()));
        assertThat(new HashSet<>(fromOpenSearch.examples().removed()))
                .isEqualTo(new HashSet<>(fromMongo.examples().removed()));
        assertThat(fromOpenSearch.hints()).extracting(Hint::kind, Hint::path)
                .isEqualTo(fromMongo.hints().stream().map(hint -> org.assertj.core.groups.Tuple.tuple(hint.kind(),
                        hint.path())).toList());
        assertThat(fromMongo.topChangedPaths()).extracting(PathChange::path)
                .contains("price", "tags[]", "legacy", "qty", "extra.flag");
    }
}
