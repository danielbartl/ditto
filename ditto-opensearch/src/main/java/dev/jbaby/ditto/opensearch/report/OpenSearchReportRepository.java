package dev.jbaby.ditto.opensearch.report;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Labels;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.report.ReportStore;
import dev.jbaby.ditto.opensearch.OpenSearchAccessException;
import dev.jbaby.ditto.opensearch.client.OpenSearchHttp;

/**
 * Stores reports in an OpenSearch index (default {@code comparison_reports}), e.g. to derive thresholds from
 * historical runs. A stored document has the report id as {@code _id}, the query fields {@code createdAt} (date),
 * {@code baseline} and {@code candidate} ({@code cluster.index}), {@code verdict} and {@code labels} (e.g.
 * {@code labels.batchJobId}), and the report itself in {@code report}, which is stored but not indexed, so the report
 * format never changes the index mapping.
 * <p>Before saving it creates the index with that mapping if it does not exist. With a retention, each save deletes reports older than the
 * retention ({@code _delete_by_query}); OpenSearch has no expiry of its own. If the index can't be created (e.g.
 * missing privileges), a warning is logged and saving is still attempted.
 * <p>A {@linkplain #readOnly read-only} repository only reads: it neither stores reports nor creates the index or
 * deletes anything.
 */
public final class OpenSearchReportRepository implements ReportStore {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchReportRepository.class);
    /** Reports per scroll page of {@link #forEach}. */
    private static final int PAGE_SIZE = 100;
    private static final String SCROLL_KEEP_ALIVE = "2m";

    private final OpenSearchHttp http;
    private final String index;
    private final @Nullable Duration retention;
    private final ReportJson json;
    private final boolean readOnly;
    private volatile boolean warned;

    /**
     * @param index     report index
     * @param retention how long a report is kept, {@code null} to keep reports forever
     */
    public OpenSearchReportRepository(OpenSearchClient client, String index, @Nullable Duration retention,
                                      ReportJson json) {
        this(new OpenSearchHttp(client), index, retention, json, false);
    }

    private OpenSearchReportRepository(OpenSearchHttp http, String index, @Nullable Duration retention,
                                       ReportJson json, boolean readOnly) {
        if (retention != null && retention.toSeconds() < 1) {
            throw new IllegalArgumentException("retention must be at least one second, was " + retention);
        }
        this.http = http;
        this.index = index;
        this.retention = retention;
        this.json = json;
        this.readOnly = readOnly;
    }

    /** A repository that only reads reports: it creates no index, deletes nothing, and {@link #save} fails. */
    public static OpenSearchReportRepository readOnly(OpenSearchClient client, String index, ReportJson json) {
        return new OpenSearchReportRepository(new OpenSearchHttp(client), index, null, json, true);
    }

    /** @throws UnsupportedOperationException if this repository is {@linkplain #readOnly read-only} */
    @Override
    public void save(ComparisonReport report) {
        if (readOnly) {
            throw new UnsupportedOperationException("This report repository is read-only");
        }
        ensureIndex();
        ObjectNode document = http.object();
        document.put("createdAt", report.run().finishedAt().toString());
        document.put("baseline", report.run().settings().baseline().toString());
        document.put("candidate", report.run().settings().candidate().toString());
        document.put("verdict", report.verdict().name());
        ObjectNode labels = document.putObject("labels");
        report.labels().forEach(labels::put);
        document.set("report", http.tree(json.write(report).getBytes(StandardCharsets.UTF_8)));
        // visible to the next comparison's history right away
        http.require("PUT", "/" + index + "/_doc/" + report.id(), Map.of("refresh", "wait_for"), document);
        applyRetention();
    }

    @Override
    public Optional<ComparisonReport> findById(String id) {
        OpenSearchHttp.Reply reply = http.execute("GET", "/" + index + "/_doc/" + id, Map.of(), null);
        if (reply.status() == 404) {
            return Optional.empty();
        }
        if (!reply.ok()) {
            throw http.error("GET", "/" + index + "/_doc/" + id, reply);
        }
        return Optional.of(toReport(http.tree(reply.body()).path("_source")));
    }

    @Override
    public List<ComparisonReport> findRecent(@Nullable String candidate, int limit) {
        ObjectNode filter = http.object();
        if (candidate != null) {
            filter.putObject("term").put("candidate", candidate);
        }
        return search(candidate == null ? List.of() : List.of(filter), List.of(), limit, false);
    }

    @Override
    public List<ComparisonReport> findByLabels(Map<String, String> labels, int limit) {
        List<ObjectNode> filters = new ArrayList<>();
        Labels.of(labels).forEach((key, value) -> {
            ObjectNode term = http.object();
            term.putObject("term").put("labels." + key, value);
            filters.add(term);
        });
        return search(filters, List.of(), limit, false);
    }

    @Override
    public List<ComparisonReport> findHistory(String baseline, String candidate, boolean includeRed, int limit) {
        ObjectNode onBaseline = http.object();
        onBaseline.putObject("term").put("baseline", baseline);
        ObjectNode onCandidate = http.object();
        onCandidate.putObject("term").put("candidate", candidate);
        ObjectNode red = http.object();
        red.putObject("term").put("verdict", "RED");
        return search(List.of(onBaseline, onCandidate), includeRed ? List.of() : List.of(red), limit, true);
    }

    /** Scrolls through the index; an index that does not exist yet holds no reports. */
    @Override
    public void forEach(Consumer<? super ComparisonReport> action) {
        forEach(action, PAGE_SIZE);
    }

    void forEach(Consumer<? super ComparisonReport> action, int pageSize) {
        ObjectNode body = http.object();
        body.put("size", pageSize);
        body.putArray("sort").add("_doc");
        OpenSearchHttp.Reply reply = http.execute("POST", "/" + index + "/_search",
                Map.of("scroll", SCROLL_KEEP_ALIVE), body);
        if (reply.status() == 404) {
            return;
        }
        if (!reply.ok()) {
            throw http.error("POST", "/" + index + "/_search", reply);
        }
        JsonNode page = http.tree(reply.body());
        String scrollId = page.path("_scroll_id").asString();
        try {
            while (!page.path("hits").path("hits").isEmpty()) {
                for (JsonNode hit : page.path("hits").path("hits")) {
                    ComparisonReport report;
                    try {
                        report = toReport(hit.path("_source"));
                    } catch (RuntimeException e) {
                        log.warn("Skipping unreadable report {} in {}: {}", hit.path("_id").asString(), index,
                                e.getMessage());
                        continue;
                    }
                    action.accept(report);
                }
                ObjectNode next = http.object();
                next.put("scroll", SCROLL_KEEP_ALIVE);
                next.put("scroll_id", scrollId);
                page = http.tree(http.require("POST", "/_search/scroll", Map.of(), next));
                scrollId = page.path("_scroll_id").asString();
            }
        } finally {
            ObjectNode clear = http.object();
            clear.putArray("scroll_id").add(scrollId);
            try {
                http.execute("DELETE", "/_search/scroll", Map.of(), clear);
            } catch (OpenSearchAccessException e) {
                // the scroll expires on its own after the keep-alive
                log.debug("Clearing a scroll of {} failed: {}", index, e.getMessage());
            }
        }
    }

    /** Most recent first; an index that does not exist yet holds no reports. */
    private List<ComparisonReport> search(List<ObjectNode> filters, List<ObjectNode> excluded, int limit,
                                          boolean skipUnreadable) {
        ObjectNode body = http.object();
        body.put("size", limit);
        body.putArray("sort").addObject().put("createdAt", "desc");
        ObjectNode bool = body.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        filters.forEach(filter::add);
        ArrayNode mustNot = bool.putArray("must_not");
        excluded.forEach(mustNot::add);
        OpenSearchHttp.Reply reply = http.execute("POST", "/" + index + "/_search", Map.of(), body);
        if (reply.status() == 404) {
            return List.of();
        }
        if (!reply.ok()) {
            throw http.error("POST", "/" + index + "/_search", reply);
        }
        List<ComparisonReport> reports = new ArrayList<>();
        for (JsonNode hit : http.tree(reply.body()).path("hits").path("hits")) {
            try {
                reports.add(toReport(hit.path("_source")));
            } catch (RuntimeException e) {
                if (!skipUnreadable) {
                    throw e;
                }
                log.warn("Skipping unreadable report {} in {}: {}", hit.path("_id").asString(), index,
                        e.getMessage());
            }
        }
        return reports;
    }

    private ComparisonReport toReport(JsonNode source) {
        return json.read(source.path("report").toString());
    }

    /**
     * Checked before every save, so an index deleted meanwhile comes back with the right mapping instead of being
     * created with dynamic mappings by the write, which would break the history queries.
     */
    private void ensureIndex() {
        try {
            if (http.execute("HEAD", "/" + index, Map.of(), null).ok()) {
                return;
            }
            OpenSearchHttp.Reply reply = http.execute("PUT", "/" + index, Map.of(), indexDefinition());
            if (!reply.ok() && !new String(reply.body(), StandardCharsets.UTF_8)
                    .contains("resource_already_exists_exception")) {
                throw http.error("PUT", "/" + index, reply);
            }
        } catch (OpenSearchAccessException e) {
            if (!warned) {
                warned = true;
                log.warn("Could not create the report index {}: {}. Storing reports may fail or map their fields"
                        + " dynamically.", index, e.getMessage());
            }
        }
    }

    private ObjectNode indexDefinition() {
        ObjectNode definition = http.object();
        ObjectNode mappings = definition.putObject("mappings");
        mappings.put("dynamic", false);
        ObjectNode properties = mappings.putObject("properties");
        properties.putObject("createdAt").put("type", "date");
        properties.putObject("baseline").put("type", "keyword");
        properties.putObject("candidate").put("type", "keyword");
        properties.putObject("verdict").put("type", "keyword");
        // label keys are free, so map whatever arrives below labels as keyword
        ObjectNode labels = properties.putObject("labels");
        labels.put("type", "object");
        labels.put("dynamic", true);
        ObjectNode template = mappings.putArray("dynamic_templates").addObject().putObject("labels");
        template.put("path_match", "labels.*");
        template.putObject("mapping").put("type", "keyword");
        properties.putObject("report").put("type", "object").put("enabled", false);
        return definition;
    }

    private void applyRetention() {
        if (retention == null) {
            return;
        }
        ObjectNode body = http.object();
        body.putObject("query").putObject("range").putObject("createdAt").put("lt", "now-" + retention.toSeconds()
                + "s");
        try {
            http.require("POST", "/" + index + "/_delete_by_query", Map.of("conflicts", "proceed"), body);
        } catch (OpenSearchAccessException e) {
            // the report itself is stored; old ones are deleted with the next save
            log.warn("Deleting reports older than {} from {} failed: {}", retention, index, e.getMessage());
        }
    }
}
