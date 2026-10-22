package dev.jbaby.ditto.comparator.autoconfigure;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

import dev.jbaby.ditto.comparator.observability.ComparisonsEndpoint;
import dev.jbaby.ditto.comparator.report.ReportStore;

/**
 * Actuator endpoint {@code comparisons} listing stored reports, if Actuator is on the classpath and persistence is
 * enabled (for one store; with several, define the endpoint yourself).
 */
@AutoConfiguration(afterName = {ComparatorEngineNames.MONGODB, ComparatorEngineNames.OPENSEARCH})
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@ConditionalOnSingleCandidate(ReportStore.class)
public class ComparatorEndpointAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    public ComparisonsEndpoint comparisonsEndpoint(ReportStore repository) {
        return new ComparisonsEndpoint(repository);
    }
}
