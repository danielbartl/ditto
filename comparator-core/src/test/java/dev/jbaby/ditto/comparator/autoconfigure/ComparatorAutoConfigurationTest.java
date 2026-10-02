package dev.jbaby.ditto.comparator.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import dev.jbaby.ditto.comparator.CollectionComparator;
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
        runner.withPropertyValues("comparator.persistence.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ReportRepository.class));
    }

    @Test
    void backsOffForUserBeans() {
        var custom = new VerdictEvaluator();
        runner.withBean(VerdictEvaluator.class, () -> custom)
                .run(context -> assertThat(context.getBean(VerdictEvaluator.class)).isSameAs(custom));
    }

    @Test
    void staysAwayWithoutMongo() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ComparatorAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(CollectionComparator.class));
    }
}
