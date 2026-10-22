package dev.jbaby.ditto.comparator.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.micrometer.core.instrument.MeterRegistry;

import dev.jbaby.ditto.comparator.DocumentComparator;
import dev.jbaby.ditto.comparator.observability.ComparisonMetrics;

/**
 * Micrometer metrics for comparisons, if Micrometer is on the classpath.
 */
@AutoConfiguration(afterName = {ComparatorEngineNames.MONGODB, ComparatorEngineNames.OPENSEARCH})
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(DocumentComparator.class)
public class ComparatorMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ComparisonMetrics comparisonMetrics(ObjectProvider<MeterRegistry> registry) {
        return new ComparisonMetrics(registry);
    }
}
