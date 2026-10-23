package dev.jbaby.ditto.opensearch.autoconfigure;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import dev.jbaby.ditto.comparator.autoconfigure.ComparatorEngineAutoConfiguration;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.history.ReportHistory;
import dev.jbaby.ditto.comparator.history.ThresholdAdvisor;
import dev.jbaby.ditto.comparator.report.FileReportStore;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.report.ReportStore;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;
import dev.jbaby.ditto.opensearch.IndexComparator;
import dev.jbaby.ditto.opensearch.report.OpenSearchReportRepository;

/**
 * Registers an {@link IndexComparator} for the application's {@link OpenSearchClient}. With
 * {@code ditto.persistence.enabled=true} it stores reports in the {@link FileReportStore}, or with
 * {@code ditto.persistence.storage=database} in an {@link OpenSearchReportRepository} in the same cluster. Every bean
 * backs off if the application defines its own.
 */
@AutoConfiguration(after = {ComparatorEngineAutoConfiguration.class, DittoOpenSearchClientAutoConfiguration.class},
        afterName = {"org.opensearch.spring.boot.autoconfigure.OpenSearchClientAutoConfiguration",
                "org.opensearch.spring.boot.autoconfigure.OpenSearchRestClientAutoConfiguration"})
@ConditionalOnClass(OpenSearchClient.class)
@ConditionalOnSingleCandidate(OpenSearchClient.class)
@EnableConfigurationProperties({ComparatorProperties.class, OpenSearchComparatorProperties.class})
public class OpenSearchComparatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ditto.persistence", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "ditto.persistence", name = "storage", havingValue = "database")
    public OpenSearchReportRepository openSearchReportRepository(OpenSearchClient client,
                                                                 ComparatorProperties properties,
                                                                 ReportJson reportJson) {
        var persistence = properties.getPersistence();
        return new OpenSearchReportRepository(client, persistence.getCollection(), persistence.getRetention(),
                reportJson);
    }

    /**
     * Thresholds come from the stored history if adaptive thresholds are switched on, explicitly or implied by
     * persistence.
     */
    @Bean
    @ConditionalOnMissingBean
    public IndexComparator indexComparator(OpenSearchClient client, ComparatorProperties properties,
                                           OpenSearchComparatorProperties openSearch, Hasher hasher,
                                           VerdictEvaluator verdictEvaluator,
                                           ObjectProvider<OpenSearchReportRepository> repository,
                                           ObjectProvider<FileReportStore> files,
                                           ApplicationEventPublisher events) {
        OpenSearchReportRepository openSearchReports = repository.getIfAvailable();
        ReportStore reports = openSearchReports != null ? openSearchReports : files.getIfAvailable();
        Boolean adaptive = properties.getAdaptiveThresholds().getEnabled();
        ThresholdAdvisor advisor = null;
        if (adaptive != null ? adaptive : properties.getPersistence().isEnabled()) {
            if (reports == null) {
                throw new IllegalStateException("ditto.adaptive-thresholds.enabled=true needs stored reports:"
                        + " set ditto.persistence.enabled=true (or define a FileReportStore or"
                        + " OpenSearchReportRepository bean)");
            }
            advisor = new ThresholdAdvisor(ReportHistory.of(reports), properties.getAdaptiveThresholds().toSettings());
        }
        return new IndexComparator(client, openSearch.getScrollKeepAlive(), properties, hasher,
                new ReportAssembler(verdictEvaluator), reports, advisor, events);
    }
}
