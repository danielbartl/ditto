package dev.jbaby.ditto.opensearch.client;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;

import dev.jbaby.ditto.opensearch.OpenSearchAccessException;

/**
 * The {@code _shards} part of a search or count response. OpenSearch answers with HTTP 200 even if shards failed, and
 * then returns only the documents of the others; such a result must not be taken for complete.
 *
 * @param total   shards searched
 * @param failed  shards that failed
 * @param failure the first failure, e.g. {@code index products, shard 2: ...}, {@code null} if none was described
 */
public record Shards(long total, long failed, @Nullable String failure) {

    static final Shards NONE = new Shards(0, 0, null);

    /** The {@code _shards} of a response read as a tree. */
    public static Shards of(JsonNode response) {
        JsonNode shards = response.path("_shards");
        JsonNode first = shards.path("failures").path(0);
        return new Shards(shards.path("total").asLong(), shards.path("failed").asLong(),
                first.isMissingNode() ? null : describe(first.path("index").asString(null),
                        first.path("shard").asString(null), first.path("reason").path("type").asString(null),
                        first.path("reason").path("reason").asString(null)));
    }

    /** Reads the {@code _shards} object the parser is at. */
    static Shards read(JsonParser parser) {
        long total = 0;
        long failed = 0;
        String failure = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken token = parser.nextToken();
            if (name.equals("total")) {
                total = parser.getLongValue();
            } else if (name.equals("failed")) {
                failed = parser.getLongValue();
            } else if (name.equals("failures") && token == JsonToken.START_ARRAY) {
                while (parser.nextToken() == JsonToken.START_OBJECT) {
                    if (failure == null) {
                        failure = failure(parser);
                    } else {
                        parser.skipChildren();
                    }
                }
            } else {
                parser.skipChildren();
            }
        }
        return new Shards(total, failed, failure);
    }

    /**
     * @param request  what was asked, for the message, e.g. {@code Search of products}
     * @param timedOut the response's {@code timed_out}
     * @throws OpenSearchAccessException if a shard failed or the request timed out
     */
    public void requireComplete(String request, boolean timedOut) {
        if (failed > 0) {
            throw new OpenSearchAccessException(request + " is incomplete: " + failed + " of " + total
                    + " shards failed" + (failure == null ? "" : " (" + failure + ")"), 200);
        }
        if (timedOut) {
            throw new OpenSearchAccessException(request + " is incomplete: it timed out", 200);
        }
    }

    private static String failure(JsonParser parser) {
        String index = null;
        String shard = null;
        String type = null;
        String reason = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken token = parser.nextToken();
            switch (name) {
                case "index" -> index = parser.getString();
                case "shard" -> shard = parser.getString();
                case "reason" -> {
                    if (token == JsonToken.START_OBJECT) {
                        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                            String field = parser.currentName();
                            parser.nextToken();
                            if (field.equals("type")) {
                                type = parser.getString();
                            } else if (field.equals("reason")) {
                                reason = parser.getString();
                            } else {
                                parser.skipChildren();
                            }
                        }
                    } else {
                        reason = parser.getString();
                    }
                }
                default -> parser.skipChildren();
            }
        }
        return describe(index, shard, type, reason);
    }

    private static String describe(@Nullable String index, @Nullable String shard, @Nullable String type,
                                   @Nullable String reason) {
        StringBuilder text = new StringBuilder();
        if (index != null) {
            text.append("index ").append(index);
        }
        if (shard != null) {
            text.append(text.isEmpty() ? "shard " : ", shard ").append(shard);
        }
        if (!text.isEmpty()) {
            text.append(": ");
        }
        text.append(type != null ? type : "failure");
        if (reason != null) {
            text.append(": ").append(reason);
        }
        return text.toString();
    }
}
