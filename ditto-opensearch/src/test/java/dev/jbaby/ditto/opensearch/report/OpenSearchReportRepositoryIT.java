package dev.jbaby.ditto.opensearch.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.opensearch.support.Indices;
import dev.jbaby.ditto.opensearch.support.OpenSearchContainer;

class OpenSearchReportRepositoryIT {

    private static final ReportJson JSON = new ReportJson();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static OpenSearchClient client;
    private static String fixture;

    @BeforeAll
    static void connect() throws IOException {
        URI uri = URI.create(OpenSearchContainer.uri());
        client = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                .builder(new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort())).build());
        try (var in = OpenSearchReportRepositoryIT.class.getResourceAsStream("/reports/report-v0.1.0.json")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterAll
    static void disconnect() throws IOException {
        client._transport().close();
    }

    @Test
    void forEachScrollsThroughEveryReport() {
        Indices.delete("all_reports");
        var repository = new OpenSearchReportRepository(client, "all_reports", null, JSON);
        List<ComparisonReport> saved = IntStream.range(0, 5).mapToObj(i -> report("r" + i)).toList();
        saved.forEach(repository::save);
        Indices.send("PUT", "/all_reports/_doc/broken?refresh=true",
                "{\"createdAt\": \"2020-01-01T00:00:00Z\", \"report\": {\"id\": 1}}", false);

        List<ComparisonReport> seen = new ArrayList<>();
        repository.forEach(seen::add, 2);

        assertThat(seen).extracting(JSON::write)
                .containsExactlyInAnyOrderElementsOf(saved.stream().map(JSON::write).toList());
        assertThat(openScrolls()).as("the scroll is cleared").isZero();
    }

    @Test
    void forEachFindsNothingWithoutTheIndex() {
        Indices.delete("no_reports");
        List<ComparisonReport> seen = new ArrayList<>();

        OpenSearchReportRepository.readOnly(client, "no_reports", JSON).forEach(seen::add);

        assertThat(seen).isEmpty();
    }

    /** The fixture report with another id. */
    private static ComparisonReport report(String id) {
        ObjectNode tree = (ObjectNode) MAPPER.readTree(fixture);
        tree.put("id", id);
        return JSON.read(MAPPER.writeValueAsString(tree));
    }

    private static long openScrolls() {
        var stats = MAPPER.readTree(Indices.send("GET", "/_nodes/stats/indices/search", null, false));
        return stats.path("nodes").valueStream()
                .mapToLong(node -> node.path("indices").path("search").path("scroll_current").asLong()).sum();
    }
}
