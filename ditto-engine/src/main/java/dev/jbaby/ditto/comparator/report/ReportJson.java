package dev.jbaby.ditto.comparator.report;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import dev.jbaby.ditto.comparator.api.ComparisonReport;

/**
 * JSON (de)serialization of reports with a dedicated mapper, so the format does not depend on the host application's
 * Jackson configuration. Dates are ISO-8601 strings, durations ISO-8601 periods. Reading is lenient, so reports
 * stored by older or newer versions stay readable: missing fields get defaults, unknown fields are ignored.
 * Thread-safe.
 */
public final class ReportJson {

    private final JsonMapper compact;
    private final JsonMapper pretty;

    public ReportJson() {
        this.compact = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
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
