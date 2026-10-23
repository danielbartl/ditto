package dev.jbaby.ditto.comparator.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import dev.jbaby.ditto.comparator.report.FileReportStore;

@ExtendWith(OutputCaptureExtension.class)
class ComparatorEngineAutoConfigurationTest {

    private static final String IGNORED = "ditto.persistence.collection and .database have no effect";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ComparatorEngineAutoConfiguration.class))
            .withPropertyValues("ditto.persistence.enabled=true");

    @Test
    void storingFilesWarnsAboutDatabaseSettingsThatNoLongerApply(CapturedOutput output) {
        runner.run(context -> assertThat(context).hasSingleBean(FileReportStore.class));
        assertThat(output).doesNotContain(IGNORED);

        runner.withPropertyValues("ditto.persistence.collection=job_reports")
                .run(context -> assertThat(context).hasSingleBean(FileReportStore.class));
        assertThat(output).contains(IGNORED).contains("set ditto.persistence.storage=database");
    }

    @Test
    void theDatabaseAloneIsEnoughToWarn(CapturedOutput output) {
        runner.withPropertyValues("ditto.persistence.database=reports")
                .run(context -> assertThat(context).hasSingleBean(FileReportStore.class));

        assertThat(output).contains(IGNORED);
    }

    @Test
    void noWarningWithDatabaseStorage(CapturedOutput output) {
        runner.withPropertyValues("ditto.persistence.storage=database", "ditto.persistence.collection=job_reports")
                .run(context -> assertThat(context).doesNotHaveBean(FileReportStore.class));

        assertThat(output).doesNotContain(IGNORED);
    }
}
