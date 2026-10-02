package dev.jbaby.ditto.comparator.report;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import dev.jbaby.ditto.comparator.api.ComparisonReport;

/**
 * JSON (de)serialization of reports with a dedicated mapper, so the format does not depend on the host application's
 * Jackson configuration. Dates are ISO-8601 strings, durations ISO-8601 periods. Thread-safe.
 */
public final class ReportJson {

    private final JsonMapper compact;
    private final JsonMapper pretty;

    public ReportJson() {
        this.compact = JsonMapper.builder().build();
        this.pretty = compact.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
    }

    public String write(ComparisonReport report) {
        return compact.writeValueAsString(report);
    }

    public String writePretty(ComparisonReport report) {
        return pretty.writeValueAsString(report);
    }

    public ComparisonReport read(String json) {
        return compact.readValue(json, ComparisonReport.class);
    }
}
