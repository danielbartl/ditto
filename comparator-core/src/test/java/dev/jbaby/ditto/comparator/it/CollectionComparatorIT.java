package dev.jbaby.ditto.comparator.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.jbaby.ditto.comparator.CollectionComparator;
import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.Rate;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.report.ReportRepository;
import dev.jbaby.ditto.comparator.support.Collections;
import dev.jbaby.ditto.comparator.support.TestApplication;

@SpringBootTest(classes = TestApplication.class, properties = "comparator.persistence.enabled=true")
class CollectionComparatorIT {

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        TestApplication.mongoProperties(registry);
    }

    @Autowired
    CollectionComparator comparator;

    @Autowired
    ReportRepository repository;

    @Autowired
    ReportJson json;

    @Test
    void comparesPersistsAndSerializesReports() {
        var db = Collections.database(TestApplication.DATABASE);
        Collections.create(db, "persist_base", "{_id: 1, a: 1}", "{_id: 2, a: 2}");
        Collections.create(db, "persist_cand", "{_id: 1, a: 1}", "{_id: 2, a: 2}");

        ComparisonReport report = comparator.compare(ComparisonRequest.of("persist_base", "persist_cand"));

        assertThat(report.verdict()).isEqualTo(Level.GREEN);
        assertThat(report.keys().keySimilarity()).isEqualTo(Rate.exact(2, 2));
        assertThat(report.run().settings().baseline()).isEqualTo(CollectionRef.of(TestApplication.DATABASE, "persist_base"));
        assertThat(json.read(json.writePretty(report))).isEqualTo(report);
        assertThat(repository.findById(report.id())).contains(report);
        assertThat(repository.findRecent(TestApplication.DATABASE + ".persist_cand", 5)).first().isEqualTo(report);
    }

    @Test
    void comparesAcrossDatabases() {
        Collections.create(Collections.database("archive_db"), "products", "{_id: 1, a: 1}", "{_id: 2, a: 2}");
        Collections.create(Collections.database(TestApplication.DATABASE), "products", "{_id: 1, a: 1}", "{_id: 3, a: 3}");

        var report = comparator.compare(ComparisonRequest.builder(
                CollectionRef.of("archive_db", "products"), CollectionRef.of("products")).build());

        assertThat(report.keys().matched()).isEqualTo(1);
        assertThat(report.keys().added()).isEqualTo(1);
        assertThat(report.keys().removed()).isEqualTo(1);
        assertThat(report.verdict()).isEqualTo(Level.RED);
    }

    @Test
    void reportsInterruptionAsComparisonException() {
        var db = Collections.database(TestApplication.DATABASE);
        Collections.create(db, "intr_base", "{_id: 1}");
        Collections.create(db, "intr_cand", "{_id: 1}");

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> comparator.compare(ComparisonRequest.of("intr_base", "intr_cand")))
                    .isInstanceOf(ComparisonException.class)
                    .hasMessageContaining("interrupted");
        } finally {
            Thread.interrupted();
        }
    }
}
