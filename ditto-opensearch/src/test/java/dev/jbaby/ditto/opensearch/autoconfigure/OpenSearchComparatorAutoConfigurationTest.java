package dev.jbaby.ditto.opensearch.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.jbaby.ditto.comparator.autoconfigure.ComparatorEngineAutoConfiguration;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorMetricsAutoConfiguration;
import dev.jbaby.ditto.comparator.observability.ComparisonMetrics;
import dev.jbaby.ditto.opensearch.IndexComparator;
import dev.jbaby.ditto.opensearch.report.OpenSearchReportRepository;

class OpenSearchComparatorAutoConfigurationTest {

    // creating a client does not connect, so no server is needed here
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ComparatorEngineAutoConfiguration.class,
                    DittoOpenSearchClientAutoConfiguration.class, OpenSearchComparatorAutoConfiguration.class,
                    ComparatorMetricsAutoConfiguration.class));

    @Test
    void createsAClientFromUris() {
        runner.withPropertyValues("ditto.opensearch.uris=http://localhost:1", "ditto.opensearch.username=u",
                        "ditto.opensearch.password=p")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenSearchClient.class);
                    assertThat(context).hasSingleBean(OpenSearchTransport.class);
                    assertThat(context).hasSingleBean(IndexComparator.class);
                    assertThat(context).hasSingleBean(ComparisonMetrics.class);
                });
    }

    @Test
    void staysAwayWithoutOpenSearch() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(OpenSearchClient.class);
            assertThat(context).doesNotHaveBean(IndexComparator.class);
        });
    }

    @Test
    void usesTheApplicationsClient() {
        var client = new OpenSearchClient(ApacheHttpClient5TransportBuilder
                .builder(new HttpHost("http", "localhost", 1)).build());
        runner.withPropertyValues("ditto.opensearch.uris=http://localhost:2")
                .withBean(OpenSearchClient.class, () -> client)
                .run(context -> {
                    assertThat(context.getBean(OpenSearchClient.class)).isSameAs(client);
                    assertThat(context).doesNotHaveBean(OpenSearchTransport.class);
                    assertThat(context).hasSingleBean(IndexComparator.class);
                });
    }

    @Test
    void registersRepositoryWhenPersistenceIsEnabled() {
        var withClient = runner.withPropertyValues("ditto.opensearch.uris=http://localhost:1");

        withClient.run(context -> assertThat(context).doesNotHaveBean(OpenSearchReportRepository.class));
        withClient.withPropertyValues("ditto.persistence.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenSearchReportRepository.class);
                    assertThat(context).hasSingleBean(IndexComparator.class);
                });
    }

    @Test
    void adaptiveThresholdsNeedPersistence() {
        runner.withPropertyValues("ditto.opensearch.uris=http://localhost:1", "ditto.adaptive-thresholds.enabled=true")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("set ditto.persistence.enabled=true"));
    }

    @Test
    void rejectsAScrollKeepAliveBelowOneSecond() {
        runner.withPropertyValues("ditto.opensearch.uris=http://localhost:1", "ditto.opensearch.scroll-keep-alive=0s")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("scroll-keep-alive must be at least one second"));
    }
}
