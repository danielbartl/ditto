package dev.jbaby.ditto.cli.generator;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import dev.jbaby.ditto.opensearch.client.OpenSearchHttp;

/**
 * Writes generated documents into OpenSearch indices with the bulk API. {@code _id} becomes the document id (an
 * ObjectId as its hex string); in the source, dates become ISO-8601 strings and ObjectIds hex strings, as JSON has
 * neither. Indices are created with dynamic mapping off: documents are stored as they are, only {@code sku} is indexed
 * (keyword), so it can serve as key field and no change can cause a mapping conflict.
 */
public final class OpenSearchSink implements DocumentSink {

    private static final String NDJSON = "application/x-ndjson";

    private final OpenSearchHttp http;

    public OpenSearchSink(OpenSearchHttp http) {
        this.http = http;
    }

    @Override
    public void recreate(String name) {
        OpenSearchHttp.Reply deleted = http.execute("DELETE", "/" + name, Map.of(), null);
        if (!deleted.ok() && deleted.status() != 404) {
            throw http.error("DELETE", "/" + name, deleted);
        }
        ObjectNode index = http.object();
        index.putObject("settings").put("number_of_replicas", 0);
        ObjectNode mappings = index.putObject("mappings");
        mappings.put("dynamic", false);
        mappings.putObject("properties").putObject("sku").put("type", "keyword");
        http.require("PUT", "/" + name, Map.of(), index);
    }

    @Override
    public void insert(String name, List<BsonDocument> documents) {
        ByteArrayOutputStream bulk = new ByteArrayOutputStream();
        for (BsonDocument document : documents) {
            ObjectNode action = http.object();
            ObjectNode target = action.putObject("index");
            BsonValue id = document.get("_id");
            if (id != null) {
                target.put("_id", id.isObjectId() ? id.asObjectId().getValue().toHexString() : scalar(id));
            }
            bulk.writeBytes(http.mapper().writeValueAsBytes(action));
            bulk.write('\n');
            bulk.writeBytes(json(document));
            bulk.write('\n');
        }
        byte[] response = http.execute("POST", "/" + name + "/_bulk", Map.of(), bulk.toByteArray(), NDJSON).body();
        JsonNode result = http.tree(response);
        if (result.path("errors").asBoolean(true)) {
            throw new IllegalStateException("Bulk indexing into " + name + " failed: "
                    + new String(response, 0, Math.min(response.length, 2000), StandardCharsets.UTF_8));
        }
    }

    @Override
    public void finish(String name) {
        http.require("POST", "/" + name + "/_refresh", Map.of(), null);
    }

    @Override
    public String describe(String name) {
        return name;
    }

    /** The document as plain JSON, without {@code _id}. */
    private byte[] json(BsonDocument document) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = http.mapper().createGenerator(out)) {
            generator.writeStartObject();
            for (Map.Entry<String, BsonValue> field : document.entrySet()) {
                if (!field.getKey().equals("_id")) {
                    generator.writeName(field.getKey());
                    write(generator, field.getValue());
                }
            }
            generator.writeEndObject();
        }
        return out.toByteArray();
    }

    private static void write(JsonGenerator generator, BsonValue value) {
        switch (value.getBsonType()) {
            case DOCUMENT -> {
                generator.writeStartObject();
                for (Map.Entry<String, BsonValue> field : value.asDocument().entrySet()) {
                    generator.writeName(field.getKey());
                    write(generator, field.getValue());
                }
                generator.writeEndObject();
            }
            case ARRAY -> {
                generator.writeStartArray();
                for (BsonValue element : value.asArray()) {
                    write(generator, element);
                }
                generator.writeEndArray();
            }
            case INT32 -> generator.writeNumber(value.asInt32().getValue());
            case INT64 -> generator.writeNumber(value.asInt64().getValue());
            case DOUBLE -> generator.writeNumber(value.asDouble().getValue());
            case DECIMAL128 -> generator.writeNumber(value.asDecimal128().getValue().bigDecimalValue());
            case BOOLEAN -> generator.writeBoolean(value.asBoolean().getValue());
            case NULL -> generator.writeNull();
            default -> generator.writeString(scalar(value));
        }
    }

    private static String scalar(BsonValue value) {
        return switch (value.getBsonType()) {
            case STRING -> value.asString().getValue();
            case OBJECT_ID -> value.asObjectId().getValue().toHexString();
            case DATE_TIME -> Instant.ofEpochMilli(value.asDateTime().getValue()).toString();
            default -> value.toString();
        };
    }
}
