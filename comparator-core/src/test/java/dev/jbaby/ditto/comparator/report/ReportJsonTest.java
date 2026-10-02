package dev.jbaby.ditto.comparator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.ThresholdSource;

class ReportJsonTest {

    private final ReportJson json = new ReportJson();

    @Test
    void readsReportsWrittenByVersion010() throws IOException {
        String stored;
        try (var in = getClass().getResourceAsStream("/reports/report-v0.1.0.json")) {
            stored = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        ComparisonReport report = json.read(stored);

        assertThat(report.verdict()).isEqualTo(Level.GREEN);
        assertThat(report.keys().matched()).isEqualTo(19900);
        assertThat(report.content().unchangedOrExpectedRate()).isEqualTo(report.content().unchangedRate());
        assertThat(report.content().changedExpectedOnly()).isZero();
        assertThat(report.topChangedPaths()).first().satisfies(change -> {
            assertThat(change.expected()).isFalse();
            assertThat(change.valueExamples()).isEmpty();
        });
        assertThat(report.run().thresholdSource()).isEqualTo(ThresholdSource.configured());
        assertThat(report.run().settings().expectedChangePaths()).isEmpty();
        assertThat(report.run().settings().tuning().maxValueExamples()).isZero();
        // and it round-trips in the current format
        assertThat(json.read(json.write(report))).isEqualTo(report);
    }
}
