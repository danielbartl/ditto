package dev.jbaby.ditto.opensearch.autoconfigure;

import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import dev.jbaby.ditto.comparator.autoconfigure.ComparatorEngineAutoConfiguration;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;
import dev.jbaby.ditto.opensearch.IndexComparator;

/**
 * Registers an {@link IndexComparator} for the application's {@link OpenSearchClient}. Every bean backs off if the
 * application defines its own.
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
    public IndexComparator indexComparator(OpenSearchClient client, ComparatorProperties properties,
                                           OpenSearchComparatorProperties openSearch, Hasher hasher,
                                           VerdictEvaluator verdictEvaluator, ApplicationEventPublisher events) {
        return new IndexComparator(client, openSearch.getScrollKeepAlive(), properties, hasher,
                new ReportAssembler(verdictEvaluator), null, null, events);
    }
}
