package dev.jbaby.ditto.comparator.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.jbaby.ditto.comparator.CollectionComparator;
import dev.jbaby.ditto.comparator.history.ThresholdAdvisor;
import dev.jbaby.ditto.comparator.observability.ComparisonMetrics;
import dev.jbaby.ditto.comparator.observability.ComparisonsEndpoint;
import dev.jbaby.ditto.comparator.report.ReportRepository;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;

class ComparatorAutoConfigurationTest {

    // creating a MongoClient does not connect, so no server is needed here
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class, DataMongoAutoConfiguration.class,
                    ComparatorAutoConfiguration.class))
            .withPropertyValues("spring.mongodb.uri=mongodb://localhost:1/app");

    @Test
    void registersComparatorWithoutPersistenceByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CollectionComparator.class);
            assertThat(context).hasSingleBean(ComparatorProperties.class);
            assertThat(context).doesNotHaveBean(ReportRepository.class);
        });
    }

    @Test
    void registersRepositoryWhenPersistenceIsEnabled() {
        runner.withPropertyValues("ditto.persistence.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ReportRepository.class));
    }

    @Test
    void rejectsRetentionBelowOneSecond() {
        runner.withPropertyValues("ditto.persistence.enabled=true", "ditto.persistence.retention=365d")
                .run(context -> assertThat(context).hasSingleBean(ReportRepository.class));
        runner.withPropertyValues("ditto.persistence.enabled=true", "ditto.persistence.retention=0s")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("retention must be at least one second"));
    }

    @Test
    void backsOffForUserBeans() {
        var custom = new VerdictEvaluator();
        runner.withBean(VerdictEvaluator.class, () -> custom)
                .run(context -> assertThat(context.getBean(VerdictEvaluator.class)).isSameAs(custom));
    }

    @Test
    void registersMetricsAndEndpointWhenAvailable() {
        var withAll = runner.withConfiguration(AutoConfigurations.of(ComparatorMetricsAutoConfiguration.class,
                ComparatorEndpointAutoConfiguration.class));

        withAll.run(context -> {
            assertThat(context).hasSingleBean(ComparisonMetrics.class);
            assertThat(context).doesNotHaveBean(ComparisonsEndpoint.class); // needs persistence
        });
        withAll.withPropertyValues("ditto.persistence.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ComparisonsEndpoint.class)); // not exposed
        withAll.withPropertyValues("ditto.persistence.enabled=true",
                        "management.endpoints.web.exposure.include=comparisons")
                .run(context -> assertThat(context).hasSingleBean(ComparisonsEndpoint.class));
    }

    @Test
    void adaptiveThresholdsNeedPersistence() {
        runner.withPropertyValues("ditto.adaptive-thresholds.enabled=true")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("set ditto.persistence.enabled=true"));
        runner.withPropertyValues("ditto.adaptive-thresholds.enabled=true", "ditto.persistence.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ThresholdAdvisor.class));
    }

    @Test
    void persistenceImpliesAdaptiveThresholdsUnlessSwitchedOff() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ThresholdAdvisor.class));
        runner.withPropertyValues("ditto.persistence.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ThresholdAdvisor.class));
        runner.withPropertyValues("ditto.persistence.enabled=true", "ditto.adaptive-thresholds.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ThresholdAdvisor.class));
    }

    @Test
    void staysAwayWithoutMongo() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ComparatorAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CollectionComparator.class));
    }
}
