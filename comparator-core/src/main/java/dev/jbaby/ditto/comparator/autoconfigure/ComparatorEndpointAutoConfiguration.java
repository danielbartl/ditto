package dev.jbaby.ditto.comparator.autoconfigure;

import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import dev.jbaby.ditto.comparator.observability.ComparisonsEndpoint;
import dev.jbaby.ditto.comparator.report.ReportRepository;

/**
 * Actuator endpoint {@code comparisons} listing stored reports, if Actuator is on the classpath and persistence is
 * enabled.
 */
@AutoConfiguration(after = ComparatorAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
@ConditionalOnBean(ReportRepository.class)
public class ComparatorEndpointAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    public ComparisonsEndpoint comparisonsEndpoint(ReportRepository repository) {
        return new ComparisonsEndpoint(repository);
    }
}
