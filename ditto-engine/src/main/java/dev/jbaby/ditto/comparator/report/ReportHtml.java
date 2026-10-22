package dev.jbaby.ditto.comparator.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import dev.jbaby.ditto.comparator.api.ComparisonReport;

/**
 * Renders a report as one self-contained HTML page: the report viewer with the report's JSON embedded. The page makes
 * no external requests, so it can be opened offline, e.g. as a CI artifact or an e-mail attachment. The same viewer,
 * without an embedded report, is the hosted viewer on the project's home page. Thread-safe.
 */
public final class ReportHtml {

    /** The empty data slot of the viewer, filled with the report's JSON. */
    static final String SLOT = "<script type=\"application/json\" id=\"ditto-report\"></script>";
    static final String TEMPLATE = "report-viewer.html";

    private final ReportJson json;
    private final String before;
    private final String after;

    public ReportHtml(ReportJson json) {
        this.json = json;
        String template = template();
        int slot = template.indexOf(SLOT);
        if (slot < 0 || template.indexOf(SLOT, slot + 1) >= 0) {
            throw new IllegalStateException("The report viewer must contain the data slot exactly once: " + SLOT);
        }
        int content = slot + SLOT.indexOf("</script>");
        this.before = template.substring(0, content);
        this.after = template.substring(content);
    }

    public String write(ComparisonReport report) {
        // "<" only occurs inside JSON strings, where the escape is equivalent; it keeps "</script>" or "<!--" in a
        // value from ending the script element
        return before + json.write(report).replace("<", "\\u003c") + after;
    }

    private static String template() {
        try (InputStream in = ReportHtml.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("Report viewer " + TEMPLATE + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the report viewer " + TEMPLATE, e);
        }
    }
}
