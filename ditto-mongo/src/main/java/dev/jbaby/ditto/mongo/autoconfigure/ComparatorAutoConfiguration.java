package dev.jbaby.ditto.mongo.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import dev.jbaby.ditto.comparator.autoconfigure.ComparatorEngineAutoConfiguration;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.history.ReportHistory;
import dev.jbaby.ditto.comparator.history.ThresholdAdvisor;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;
import dev.jbaby.ditto.mongo.CollectionComparator;
import dev.jbaby.ditto.mongo.report.ReportRepository;

/**
 * Registers a {@link CollectionComparator} for the application's MongoDB connection, and with
 * {@code ditto.persistence.enabled=true} a {@link ReportRepository}. Every bean backs off if the application defines
 * its own.
 */
@AutoConfiguration(after = {DataMongoAutoConfiguration.class, ComparatorEngineAutoConfiguration.class})
@ConditionalOnClass(MongoDatabaseFactory.class)
@ConditionalOnBean(MongoDatabaseFactory.class)
@EnableConfigurationProperties(ComparatorProperties.class)
public class ComparatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ditto.persistence", name = "enabled", havingValue = "true")
    public ReportRepository comparatorReportRepository(MongoDatabaseFactory databaseFactory,
                                                       ComparatorProperties properties, ReportJson reportJson) {
        var persistence = properties.getPersistence();
        return new ReportRepository(databaseFactory, persistence.getDatabase(), persistence.getCollection(),
                persistence.getRetention(), reportJson);
    }

    @Bean
    @ConditionalOnMissingBean
    // explicitly configured, or else implied by persistence
    @ConditionalOnExpression("${ditto.adaptive-thresholds.enabled:${ditto.persistence.enabled:false}}")
    public ThresholdAdvisor comparatorThresholdAdvisor(ObjectProvider<ReportRepository> repository,
                                                       ComparatorProperties properties) {
        ReportRepository reports = repository.getIfAvailable();
        if (reports == null) {
            throw new IllegalStateException("ditto.adaptive-thresholds.enabled=true needs stored reports:"
                    + " set ditto.persistence.enabled=true (or define a ReportRepository bean)");
        }
        return new ThresholdAdvisor(ReportHistory.of(reports), properties.getAdaptiveThresholds().toSettings());
    }

    @Bean
    @ConditionalOnMissingBean
    public CollectionComparator collectionComparator(MongoDatabaseFactory databaseFactory,
                                                     ComparatorProperties properties, Hasher hasher,
                                                     VerdictEvaluator verdictEvaluator,
                                                     ObjectProvider<ReportRepository> repository,
                                                     ObjectProvider<ThresholdAdvisor> thresholdAdvisor,
                                                     ApplicationEventPublisher events) {
        return new CollectionComparator(databaseFactory, properties, hasher, new ReportAssembler(verdictEvaluator),
                repository.getIfAvailable(), thresholdAdvisor.getIfAvailable(), events);
    }
}
