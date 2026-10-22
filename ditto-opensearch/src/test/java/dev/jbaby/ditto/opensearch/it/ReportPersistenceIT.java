package dev.jbaby.ditto.opensearch.it;

import static dev.jbaby.ditto.opensearch.support.Indices.documents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ThresholdSource;
import dev.jbaby.ditto.comparator.observability.ComparisonsEndpoint;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.opensearch.IndexComparator;
import dev.jbaby.ditto.opensearch.report.OpenSearchReportRepository;
import dev.jbaby.ditto.opensearch.support.Indices;
import dev.jbaby.ditto.opensearch.support.TestApplication;

@SpringBootTest(classes = TestApplication.class, properties = {TestApplication.WITHOUT_MONGO,
        "ditto.persistence.enabled=true", "ditto.persistence.collection=it_reports",
        "ditto.adaptive-thresholds.min-history=2", "management.endpoints.web.exposure.include=comparisons"})
class ReportPersistenceIT {

    @DynamicPropertySource
    static void openSearch(DynamicPropertyRegistry registry) {
        TestApplication.openSearchProperties(registry);
    }

    @Autowired
    IndexComparator comparator;

    @Autowired
    OpenSearchReportRepository repository;

    @Autowired
    ComparisonsEndpoint endpoint;

    @Autowired
    OpenSearchClient client;

    private final ReportJson json = new ReportJson();

    @Test
    void storesReportsAndLearnsThresholdsFromThem() {
        Indices.delete("it_reports");
        Indices.create("hist_a", documents(100, i -> "{_id: '" + i + "', v: " + i + "}"));
        // GREEN: RED runs are left out of the history
        Indices.create("hist_b", documents(100, i -> "{_id: '" + i + "', v: " + i + "}"));

        ComparisonReport first = compare("1");
        ComparisonReport second = compare("2");
        ComparisonReport third = compare("3");

        assertThat(first.run().thresholdSource().kind()).isEqualTo(ThresholdSource.Kind.CONFIGURED);
        assertThat(second.run().thresholdSource().kind()).isEqualTo(ThresholdSource.Kind.CONFIGURED);
        assertThat(third.run().thresholdSource().kind()).isEqualTo(ThresholdSource.Kind.HISTORY);
        assertThat(third.run().thresholdSource().historyRuns()).isEqualTo(2);
        assertThat(repository.findById(first.id())).hasValueSatisfying(
                found -> assertThat(json.write(found)).isEqualTo(json.write(first)));
        assertThat(repository.findById("unknown")).isEmpty();
        assertThat(repository.findRecent("docker-cluster.hist_b", 10)).extracting(ComparisonReport::id)
                .containsExactly(third.id(), second.id(), first.id());
        assertThat(repository.findRecent("docker-cluster.other", 10)).isEmpty();
        assertThat(repository.findByLabels(Map.of("batchJobId", "2"), 10)).extracting(ComparisonReport::id)
                .containsExactly(second.id());
        assertThat(repository.findHistory("docker-cluster.hist_a", "docker-cluster.hist_b", false, 2))
                .extracting(ComparisonReport::id).containsExactly(third.id(), second.id());
        assertThat(endpoint.recent("batchJobId:3")).singleElement()
                .satisfies(summary -> assertThat(summary.id()).isEqualTo(third.id()));
        assertThat(endpoint.report(first.id())).isNotNull();
    }

    @Test
    void readOnlyRepositoryOnlyReads() {
        Indices.delete("ro_reports");
        Indices.create("ro_a", "{_id: '1'}");
        Indices.create("ro_b", "{_id: '1'}");
        ComparisonReport report = comparator.compare("ro_a", "ro_b");
        var readOnly = OpenSearchReportRepository.readOnly(client, "ro_reports", json);

        assertThat(readOnly.findRecent(null, 10)).isEmpty();
        assertThat(readOnly.findById("x")).isEmpty();
        assertThatThrownBy(() -> readOnly.save(report)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(exists("ro_reports")).isFalse();
    }

    @Test
    void retentionDeletesOlderReports() {
        Indices.delete("ret_reports");
        var retaining = new OpenSearchReportRepository(client, "ret_reports", Duration.ofDays(30), json);
        Indices.create("ret_a", "{_id: '1'}");
        Indices.create("ret_b", "{_id: '1'}");
        ComparisonReport report = comparator.compare("ret_a", "ret_b");
        retaining.save(report);
        Indices.send("PUT", "/ret_reports/_doc/old?refresh=true",
                "{\"createdAt\": \"2020-01-01T00:00:00Z\", \"candidate\": \"x\", \"report\": {}}", false);

        retaining.save(comparator.compare("ret_a", "ret_b"));

        assertThat(Indices.send("GET", "/ret_reports/_doc/old", null, true)).contains("\"found\":false");
        assertThat(retaining.findById(report.id())).isPresent();
    }

    private ComparisonReport compare(String batchJobId) {
        return comparator.compare(ComparisonRequest.builder("hist_a", "hist_b")
                .labels(Map.of("batchJobId", batchJobId)).build());
    }

    private static boolean exists(String index) {
        try {
            Indices.send("GET", "/" + index, null, false);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
