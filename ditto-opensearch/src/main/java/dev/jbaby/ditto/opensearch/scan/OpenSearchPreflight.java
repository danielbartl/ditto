package dev.jbaby.ditto.opensearch.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.scan.PreflightResult;
import dev.jbaby.ditto.opensearch.client.OpenSearchHttp;
import dev.jbaby.ditto.opensearch.client.Shards;

/**
 * Checks before reading two OpenSearch indices: both exist and are single indices (an alias must point to exactly
 * one), store {@code _source}, and have a key field that can be looked up exactly and is present in every document.
 * Also provides the document counts and warns if refreshing is switched off.
 */
public final class OpenSearchPreflight {

    /** Characters that make a name a pattern or a list instead of one index or alias. */
    private static final Pattern NOT_A_NAME = Pattern.compile("[\\s,*?\"<>|/\\\\#]");
    private static final List<String> INTEGER_TYPES = List.of("long", "integer", "short", "byte");

    private final OpenSearchHttp http;

    public OpenSearchPreflight(OpenSearchHttp http) {
        this.http = http;
    }

    /**
     * @param baselineKey  how the baseline's documents are identified
     * @param candidateKey how the candidate's documents are identified
     * @param result       counts and warnings
     */
    public record Result(KeyField baselineKey, KeyField candidateKey, PreflightResult result) {
    }

    /**
     * @throws ComparisonException if an index does not exist or the keys cannot be compared
     */
    public Result run(String keyField, CollectionRef baseline, CollectionRef candidate) {
        List<String> warnings = new ArrayList<>();
        KeyField baselineKey = check("baseline", baseline.collection(), keyField, warnings);
        KeyField candidateKey = check("candidate", candidate.collection(), keyField, warnings);
        if (baselineKey.kind() != candidateKey.kind()) {
            throw new ComparisonException("Key field '" + keyField + "' holds " + describe(baselineKey)
                    + " in the baseline index and " + describe(candidateKey) + " in the candidate index; both"
                    + " must be mapped alike");
        }
        return new Result(baselineKey, candidateKey, new PreflightResult(count(baseline.collection(), null),
                count(candidate.collection(), null), warnings));
    }

    private KeyField check(String side, String index, String keyField, List<String> warnings) {
        if (index.isBlank() || NOT_A_NAME.matcher(index).find() || index.startsWith("_")) {
            throw new ComparisonException("The " + side + " '" + index + "' is not the name of an index or alias");
        }
        OpenSearchHttp.Reply reply = http.execute("GET", "/" + index + "/_mapping", Map.of(), null);
        if (reply.status() == 404) {
            throw new ComparisonException("The " + side + " index " + index + " does not exist");
        }
        if (!reply.ok()) {
            throw http.error("GET", "/" + index + "/_mapping", reply);
        }
        JsonNode indices = http.tree(reply.body());
        if (indices.size() != 1) {
            throw new ComparisonException("The " + side + " alias " + index + " points to " + indices.size()
                    + " indices " + indices.propertyNames() + "; compare a single index");
        }
        JsonNode mapping = indices.properties().iterator().next().getValue().path("mappings");
        if (!mapping.path("_source").path("enabled").asBoolean(true)) {
            throw new ComparisonException("The " + side + " index " + index + " does not store _source, so its"
                    + " documents cannot be compared");
        }
        KeyField key = keyField(side, index, keyField, mapping);
        if (!key.isId()) {
            ObjectNode missing = http.object();
            missing.putObject("bool").putObject("must_not").putObject("exists").put("field", key.lookupField());
            long withoutKey = count(index, missing);
            if (withoutKey > 0) {
                throw new ComparisonException("Key field '" + keyField + "' is missing in " + withoutKey
                        + " documents of the " + side + " index " + index
                        + (key.lookupField().equals(keyField) ? "" : " (as " + key.lookupField()
                        + ", which skips values longer than its ignore_above)"));
            }
        }
        if ("-1".equals(refreshInterval(index))) {
            warnings.add("The " + side + " index " + index + " has refreshing switched off"
                    + " (index.refresh_interval=-1): documents written since the last refresh are not compared."
                    + " Refresh it after writing (POST /" + index + "/_refresh).");
        }
        return key;
    }

    /** Resolves the key field in an index mapping; dotted names address fields of objects. */
    private static KeyField keyField(String side, String index, String name, JsonNode mapping) {
        if (name.equals("_id")) {
            return KeyField.ID;
        }
        JsonNode field = mapping;
        for (String part : name.split("\\.", -1)) {
            field = field.path("properties").path(part);
        }
        if (field.isMissingNode()) {
            throw new ComparisonException("Key field '" + name + "' is not mapped in the " + side + " index "
                    + index);
        }
        String type = field.path("type").asString("object");
        if (type.equals("keyword")) {
            return new KeyField(name, name, KeyField.Kind.KEYWORD);
        }
        if (INTEGER_TYPES.contains(type)) {
            return new KeyField(name, name, KeyField.Kind.INTEGER);
        }
        for (var subfield : field.path("fields").properties()) {
            if (subfield.getValue().path("type").asString("").equals("keyword")) {
                return new KeyField(name, name + "." + subfield.getKey(), KeyField.Kind.KEYWORD);
            }
        }
        throw new ComparisonException("Key field '" + name + "' is mapped as " + type + " in the " + side
                + " index " + index + ", so its values cannot be looked up exactly; use a keyword or integer field,"
                + " or add a keyword subfield");
    }

    private long count(String index, @Nullable ObjectNode query) {
        ObjectNode body = null;
        if (query != null) {
            body = http.object();
            body.set("query", query);
        }
        JsonNode response = http.tree(http.require("POST", "/" + index + "/_count", Map.of(), body));
        Shards.of(response).requireComplete("Counting the documents of " + index, false);
        return response.path("count").asLong();
    }

    private @Nullable String refreshInterval(String index) {
        JsonNode settings = http.tree(http.require("GET", "/" + index + "/_settings/index.refresh_interval",
                Map.of(), null));
        for (var entry : settings.properties()) {
            JsonNode interval = entry.getValue().path("settings").path("index").path("refresh_interval");
            if (interval.isString()) {
                return interval.asString();
            }
        }
        return null;
    }

    private static String describe(KeyField key) {
        return switch (key.kind()) {
            case ID -> "document ids";
            case KEYWORD -> "keywords";
            case INTEGER -> "integers";
        };
    }
}
