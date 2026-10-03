package dev.jbaby.ditto.comparator.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.jbaby.ditto.comparator.CollectionComparator;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.Rate;
import dev.jbaby.ditto.comparator.api.VerdictBasis;
import dev.jbaby.ditto.comparator.support.Collections;
import dev.jbaby.ditto.comparator.support.TestApplication;

/**
 * SAMPLE mode estimates against the exact FULL result on 20,000 documents with 1% removed, 0.5% added and the price
 * changed in 10% of the documents. Every signal is well inside a band (FULL: keySimilarity 0.985, unchangedRate 0.9,
 * price change rate 0.1, all YELLOW) and tolerances are several standard errors wide, so random sampling does not make
 * the test flaky.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "comparator.full-scan-limit=10000",
        "comparator.sample.size=2000"})
class SampleModeIT {

    private static final int DOCS = 20_000;

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        TestApplication.mongoProperties(registry);
    }

    @Autowired
    CollectionComparator comparator;

    @BeforeAll
    static void createCollections() {
        List<BsonDocument> baseline = new ArrayList<>();
        List<BsonDocument> candidate = new ArrayList<>();
        for (int i = 0; i < DOCS; i++) {
            var doc = new BsonDocument("_id", new BsonInt32(i))
                    .append("name", new BsonString("item " + i))
                    .append("price", new BsonInt32(i % 1000))
                    .append("rare", i % 1000 == 0 ? new BsonString("x") : new BsonInt32(0));
            baseline.add(doc);
            if (i % 100 == 7) {
                continue; // 1% removed
            }
            candidate.add(i % 10 == 3 ? doc.clone().append("price", new BsonInt32(-1)) : doc);
        }
        for (int i = 0; i < DOCS / 200; i++) {
            candidate.add(new BsonDocument("_id", new BsonInt32(DOCS + i)).append("name", new BsonString("new"))
                    .append("price", new BsonInt32(1)).append("rare", new BsonInt32(0)));
        }
        var db = Collections.database(TestApplication.DATABASE);
        Collections.create(db, "sample_base", baseline);
        Collections.create(db, "sample_cand", candidate);
    }

    @Test
    void estimatesMatchTheFullScan() {
        ComparisonReport full = comparator.compare(ComparisonRequest.builder("sample_base", "sample_cand").fullScan()
                .build());
        ComparisonReport sample = comparator.compare(ComparisonRequest.builder("sample_base", "sample_cand")
                .sample(3000).verdictBasis(VerdictBasis.POINT).build());

        assertThat(full.keys().removed()).isEqualTo(200);
        assertThat(full.keys().added()).isEqualTo(100);
        assertThat(full.verdict()).isEqualTo(Level.YELLOW);
        // $sample may return a document twice; duplicates are dropped
        assertThat(sample.run().baselineDocsRead()).isBetween(2900L, 3000L);

        assertThat(sample.keys().keySimilarity()).isInstanceOf(Rate.Estimate.class);
        assertClose(sample.keys().keySimilarity(), full.keys().keySimilarity().value(), 0.02);
        assertClose(sample.keys().removedRate(), full.keys().removedRate().value(), 0.02);
        assertClose(sample.keys().addedRate(), full.keys().addedRate().value(), 0.015);
        assertClose(sample.content().unchangedRate(), full.content().unchangedRate().value(), 0.04);
        assertThat(sample.topChangedPaths()).first().satisfies(path -> {
            assertThat(path.path()).isEqualTo("price");
            assertClose(path.changeRate(), full.topChangedPaths().getFirst().changeRate().value(), 0.04);
        });
        // extrapolated counts are in the right order of magnitude
        assertThat(sample.keys().removed()).isBetween(80L, 350L);
        assertThat(sample.run().baselineCount()).isEqualTo(DOCS);

        // paired structure profiling: the rare string variant never looks vanished or shifted
        assertThat(sample.structure().missingPaths()).isEmpty();
        assertThat(sample.verdict()).isEqualTo(full.verdict());
    }

    @Test
    void conservativeBasisIsNeverMoreOptimisticThanThePointEstimate() {
        var request = ComparisonRequest.builder("sample_base", "sample_cand").sample(1000);
        ComparisonReport point = comparator.compare(request.verdictBasis(VerdictBasis.POINT).build());
        ComparisonReport conservative = comparator.compare(request.verdictBasis(VerdictBasis.CONSERVATIVE).build());

        // the conservative verdict evaluates the worse bound, so it is never better than the point verdict
        assertThat(conservative.verdict()).isGreaterThanOrEqualTo(point.verdict());
        if (conservative.verdict().compareTo(point.verdict()) > 0) {
            assertThat(conservative.hints()).anyMatch(hint -> hint.kind() == dev.jbaby.ditto.comparator.api.Hint.Kind.LARGER_SAMPLE
                    && "--sample-size=4000".equals(hint.cliOption()));
        }
        var estimate = (Rate.Estimate) conservative.keys().keySimilarity();
        assertThat(conservative.rules().getFirst().observed()).isEqualTo(estimate.lower());
        assertThat(estimate.lower()).isLessThan(estimate.value());
    }

    @Test
    void autoModeSamplesAboveTheFullScanLimit() {
        ComparisonReport report = comparator.compare(ComparisonRequest.of("sample_base", "sample_cand"));

        assertThat(report.run().settings().mode()).isEqualTo(dev.jbaby.ditto.comparator.api.ComparisonMode.sample(2000));
        assertThat(report.run().decisions()).singleElement().asString()
                .startsWith("Mode AUTO chose SAMPLE of 2,000 keys per side: 20,000 documents exceed");
        assertThat(report.keys().keySimilarity()).isInstanceOf(Rate.Estimate.class);
    }

    @Test
    void sampleLargerThanTheCollectionCoversEverything() {
        var db = Collections.database(TestApplication.DATABASE);
        Collections.create(db, "tiny_base", "{_id: 1, a: 1}", "{_id: 2, a: 2}", "{_id: 3, a: 3}");
        Collections.create(db, "tiny_cand", "{_id: 1, a: 1}", "{_id: 2, a: 20}", "{_id: 4, a: 4}");

        ComparisonReport report = comparator.compare(ComparisonRequest.builder("tiny_base", "tiny_cand").sample(100)
                .build());

        assertThat(report.keys().matched()).isEqualTo(2);
        assertThat(report.keys().removed()).isEqualTo(1);
        assertThat(report.keys().added()).isEqualTo(1);
        assertThat(report.content().changed()).isEqualTo(1);
        assertThat(report.run().baselineDocsRead()).isEqualTo(3);
        assertThat(report.examples().added()).extracting(k -> k.value()).containsExactly("4");
    }

    private static void assertClose(Rate rate, Double expected, double tolerance) {
        assertThat(rate).isInstanceOf(Rate.Estimate.class);
        var estimate = (Rate.Estimate) rate;
        assertThat(estimate.value()).isCloseTo(expected, within(tolerance));
        assertThat(estimate.lower()).isLessThanOrEqualTo(estimate.value());
        assertThat(estimate.upper()).isGreaterThanOrEqualTo(estimate.value());
    }
}
