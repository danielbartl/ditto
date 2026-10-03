package dev.jbaby.ditto.comparator.autoconfigure;

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

import dev.jbaby.ditto.comparator.CollectionComparator;
import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.history.ReportHistory;
import dev.jbaby.ditto.comparator.history.ThresholdAdvisor;
import dev.jbaby.ditto.comparator.key.KeyInspector;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.report.ReportRepository;
import dev.jbaby.ditto.comparator.scan.MergeJoinComparator;
import dev.jbaby.ditto.comparator.scan.Preflight;
import dev.jbaby.ditto.comparator.scan.SampleComparator;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;

/**
 * Registers a {@link CollectionComparator} for the application's MongoDB connection. Every bean backs off if the
 * application defines its own.
 */
@AutoConfiguration(after = DataMongoAutoConfiguration.class)
@ConditionalOnClass(MongoDatabaseFactory.class)
@ConditionalOnBean(MongoDatabaseFactory.class)
@EnableConfigurationProperties(ComparatorProperties.class)
public class ComparatorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Hasher comparatorHasher() {
        return new Hasher(new CanonicalEncoder());
    }

    @Bean
    @ConditionalOnMissingBean
    public VerdictEvaluator comparatorVerdictEvaluator() {
        return new VerdictEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportJson comparatorReportJson() {
        return new ReportJson();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "comparator.persistence", name = "enabled", havingValue = "true")
    public ReportRepository comparatorReportRepository(MongoDatabaseFactory databaseFactory,
                                                       ComparatorProperties properties, ReportJson reportJson) {
        var persistence = properties.getPersistence();
        return new ReportRepository(databaseFactory, persistence.getDatabase(), persistence.getCollection(),
                persistence.getRetention(), reportJson);
    }

    @Bean
    @ConditionalOnMissingBean
    // explicitly configured, or else implied by persistence
    @ConditionalOnExpression("${comparator.adaptive-thresholds.enabled:${comparator.persistence.enabled:false}}")
    public ThresholdAdvisor comparatorThresholdAdvisor(ObjectProvider<ReportRepository> repository,
                                                       ComparatorProperties properties) {
        ReportRepository reports = repository.getIfAvailable();
        if (reports == null) {
            throw new IllegalStateException("comparator.adaptive-thresholds.enabled=true needs stored reports:"
                    + " set comparator.persistence.enabled=true (or define a ReportRepository bean)");
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
        return new CollectionComparator(databaseFactory, properties, hasher, new Preflight(new KeyInspector()),
                new MergeJoinComparator(), new SampleComparator(), new ReportAssembler(verdictEvaluator),
                repository.getIfAvailable(), thresholdAdvisor.getIfAvailable(), events);
    }
}
