package dev.jbaby.ditto.comparator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The hosted viewer on the home page ({@code docs/}, served by GitHub Pages) is a copy of the viewer {@link ReportHtml}
 * embeds reports into. After changing the viewer, copy it: {@code cp <resource> docs/viewer.html}.
 */
class ViewerPageSyncTest {

    private static final Path DOCS = Path.of("..", "docs");

    @Test
    void theHostedViewerIsTheEmbeddedOne() throws IOException {
        String viewer;
        try (var in = ReportHtml.class.getResourceAsStream(ReportHtml.TEMPLATE)) {
            viewer = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(Files.readString(DOCS.resolve("viewer.html")))
                .as("docs/viewer.html must be a copy of %s", ReportHtml.TEMPLATE).isEqualTo(viewer);
    }

    @Test
    void theExampleReportIsReadable() throws IOException {
        var report = new ReportJson().read(Files.readString(DOCS.resolve("example-report.json")));

        assertThat(report.topChangedPaths()).isNotEmpty();
        assertThat(report.hints()).isNotEmpty();
    }
}
