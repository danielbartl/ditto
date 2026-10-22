package dev.jbaby.ditto.comparator.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonReport;

class ReportHtmlTest {

    private static final String OPEN = "<script type=\"application/json\" id=\"ditto-report\">";

    private final ReportJson json = new ReportJson();
    private final ReportHtml html = new ReportHtml(json);

    @Test
    void embedsTheReportInTheViewer() throws IOException {
        ComparisonReport report = storedReport();

        String page = html.write(report);

        assertThat(page).startsWith("<!doctype html>").contains("<title>ditto report</title>");
        assertThat(json.read(embedded(page))).isEqualTo(report);
    }

    @Test
    void valuesCannotEndTheScriptElement() throws IOException {
        ComparisonReport stored = storedReport();
        String hostile = "a value with </script><script>alert(1)</script> and <!-- in it";
        ComparisonReport report = new ComparisonReport(stored.schemaVersion(), stored.id(), stored.verdict(),
                stored.rules(), stored.keys(), stored.content(), stored.topChangedPaths(), stored.structure(),
                stored.examples(), stored.run(), List.of(hostile), stored.hints(), stored.labels());

        String page = html.write(report);

        String data = embedded(page);
        assertThat(data).doesNotContain("<");
        assertThat(json.read(data).warnings()).containsExactly(hostile);
        // the viewer's own script still follows the data
        assertThat(page.substring(page.indexOf(OPEN))).contains("</script>\n<script>");
    }

    @Test
    void theViewerAloneHasAnEmptySlot() throws IOException {
        String template;
        try (var in = ReportHtml.class.getResourceAsStream(ReportHtml.TEMPLATE)) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(template).containsOnlyOnce(ReportHtml.SLOT);
    }

    /** The content of the data slot. */
    private static String embedded(String page) {
        int start = page.indexOf(OPEN) + OPEN.length();
        return page.substring(start, page.indexOf("</script>", start));
    }

    private ComparisonReport storedReport() throws IOException {
        try (var in = getClass().getResourceAsStream("/reports/report-v0.1.0.json")) {
            return json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
