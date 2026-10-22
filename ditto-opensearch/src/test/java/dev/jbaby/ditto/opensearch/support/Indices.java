package dev.jbaby.ditto.opensearch.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates test indices over plain HTTP, independent of the code under test. Documents are written in relaxed JSON
 * ({@code {_id: 'a', n: 1}}); {@code _id} becomes the document id, {@code _routing} its routing.
 */
public final class Indices {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper RELAXED = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES, JsonReadFeature.ALLOW_SINGLE_QUOTES).build();

    private Indices() {
    }

    /** Replaces the index with one holding these documents (one shard, refreshed). */
    public static void create(String index, String... documents) {
        create(index, "{}", 1, Arrays.asList(documents));
    }

    public static void create(String index, List<String> documents) {
        create(index, "{}", 1, documents);
    }

    /**
     * @param mappings the index's {@code mappings} in relaxed JSON
     * @param shards   number of primary shards
     */
    public static void create(String index, String mappings, int shards, List<String> documents) {
        send("DELETE", "/" + index, null, true);
        ObjectNode body = RELAXED.createObjectNode();
        body.putObject("settings").put("number_of_shards", shards).put("number_of_replicas", 0);
        body.set("mappings", RELAXED.readTree(mappings));
        send("PUT", "/" + index, body.toString(), false);
        if (documents.isEmpty()) {
            return;
        }
        StringBuilder bulk = new StringBuilder();
        for (String document : documents) {
            ObjectNode source = (ObjectNode) RELAXED.readTree(document);
            ObjectNode action = RELAXED.createObjectNode();
            ObjectNode index1 = action.putObject("index");
            JsonNode id = source.remove("_id");
            if (id != null) {
                index1.put("_id", id.asString());
            }
            JsonNode routing = source.remove("_routing");
            if (routing != null) {
                index1.put("routing", routing.asString());
            }
            bulk.append(action).append('\n').append(source).append('\n');
        }
        String response = send("POST", "/" + index + "/_bulk?refresh=true", bulk.toString(), false);
        if (RELAXED.readTree(response).path("errors").asBoolean()) {
            throw new IllegalStateException("Bulk indexing into " + index + " failed: " + response);
        }
    }

    /** Points {@code alias} at the given indices only. */
    public static void alias(String alias, String... indices) {
        send("DELETE", "/_all/_alias/" + alias, null, true);
        for (String index : indices) {
            send("PUT", "/" + index + "/_alias/" + alias, null, false);
        }
    }

    public static void settings(String index, String settings) {
        send("PUT", "/" + index + "/_settings", RELAXED.readTree(settings).toString(), false);
    }

    public static void delete(String index) {
        send("DELETE", "/" + index, null, true);
    }

    public static List<String> documents(int count, IntFunction<String> document) {
        return IntStream.range(0, count).mapToObj(document).toList();
    }

    /** @return the response body */
    public static String send(String method, String path, String body, boolean ignoreNotFound) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(OpenSearchContainer.uri() + path))
                .header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300 && !(ignoreNotFound && response.statusCode() == 404)) {
                throw new IllegalStateException(method + " " + path + " failed with " + response.statusCode() + ": "
                        + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
