package dev.jbaby.ditto.mongo.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.jbaby.ditto.comparator.autoconfigure.ComparatorEndpointAutoConfiguration;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorEngineAutoConfiguration;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorMetricsAutoConfiguration;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.history.ThresholdAdvisor;
import dev.jbaby.ditto.comparator.observability.ComparisonMetrics;
import dev.jbaby.ditto.comparator.observability.ComparisonsEndpoint;
import dev.jbaby.ditto.comparator.report.FileReportStore;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;
import dev.jbaby.ditto.mongo.CollectionComparator;
import dev.jbaby.ditto.mongo.report.MongoReportRepository;

class MongoComparatorAutoConfigurationTest {

    // creating a MongoClient does not connect, so no server is needed here
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class, DataMongoAutoConfiguration.class,
                    ComparatorEngineAutoConfiguration.class, MongoComparatorAutoConfiguration.class))
            .withPropertyValues("spring.mongodb.uri=mongodb://localhost:1/app");

    @Test
    void registersComparatorWithoutPersistenceByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CollectionComparator.class);
            assertThat(context).hasSingleBean(ComparatorProperties.class);
            assertThat(context).doesNotHaveBean(MongoReportRepository.class);
            assertThat(context).doesNotHaveBean(FileReportStore.class);
        });
    }

    @Test
    void storesReportFilesWhenPersistenceIsEnabled() {
        runner.withPropertyValues("ditto.persistence.enabled=true")
                .run(context -> {
                    assertThat(context.getBean(FileReportStore.class).directory())
                            .isEqualTo(Path.of("ditto-reports").toAbsolutePath());
                    assertThat(context).doesNotHaveBean(MongoReportRepository.class);
                });
        runner.withPropertyValues("ditto.persistence.enabled=true", "ditto.persistence.directory=/var/lib/ditto")
                .run(context -> assertThat(context.getBean(FileReportStore.class).directory())
                        .isEqualTo(Path.of("/var/lib/ditto")));
    }

    @Test
    void storesReportsInMongoWithDatabaseStorage() {
        for (String storage : new String[] {"database", "DATABASE"}) {
            runner.withPropertyValues("ditto.persistence.enabled=true", "ditto.persistence.storage=" + storage)
                    .run(context -> {
                        assertThat(context).hasSingleBean(MongoReportRepository.class);
                        assertThat(context).doesNotHaveBean(FileReportStore.class);
                    });
        }
    }

    @Test
    void rejectsRetentionBelowOneSecond() {
        for (String storage : new String[] {"file", "database"}) {
            var persistence = runner.withPropertyValues("ditto.persistence.enabled=true",
                    "ditto.persistence.storage=" + storage);
            persistence.withPropertyValues("ditto.persistence.retention=365d")
                    .run(context -> assertThat(context).hasNotFailed());
            persistence.withPropertyValues("ditto.persistence.retention=0s")
                    .run(context -> assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("retention must be at least one second"));
        }
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
        runner.withPropertyValues("ditto.adaptive-thresholds.enabled=true", "ditto.persistence.enabled=true",
                        "ditto.persistence.storage=database")
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
                .withConfiguration(AutoConfigurations.of(MongoComparatorAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CollectionComparator.class));
    }
}
