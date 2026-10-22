package dev.jbaby.ditto.opensearch.client;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonValue;
import org.jspecify.annotations.Nullable;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * One page of a search or scroll response, read by streaming: the hits' ids, the exact bytes of their
 * {@code _source} (not parsed, not copied) and the values of one requested field.
 *
 * @param scrollId id for the next scroll page, {@code null} if not scrolling
 * @param hits     the hits of this page
 * @param total    total hits if tracked, else -1
 */
public record SearchPage(@Nullable String scrollId, List<Hit> hits, long total) {

    /**
     * @param id     the document's {@code _id}
     * @param source its {@code _source} as returned, {@code null} if not requested
     * @param values values of the requested field ({@code fields}, from doc values), empty if it has none
     */
    public record Hit(String id, @Nullable Slice source, List<BsonValue> values) {
    }

    /**
     * @param response the response body
     * @param field    the field whose values to read from each hit's {@code fields}, {@code null} for none
     */
    public static SearchPage read(JsonMapper mapper, byte[] response, @Nullable String field) {
        String scrollId = null;
        List<Hit> hits = new ArrayList<>();
        long total = -1;
        try (JsonParser parser = mapper.createParser(response)) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = parser.currentName();
                parser.nextToken();
                if (name.equals("_scroll_id")) {
                    scrollId = parser.getString();
                } else if (name.equals("hits") && parser.currentToken() == JsonToken.START_OBJECT) {
                    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        String hitsField = parser.currentName();
                        parser.nextToken();
                        if (hitsField.equals("hits")) {
                            while (parser.nextToken() == JsonToken.START_OBJECT) {
                                hits.add(hit(parser, response, field));
                            }
                        } else if (hitsField.equals("total") && parser.currentToken() == JsonToken.START_OBJECT) {
                            total = total(parser);
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        return new SearchPage(scrollId, hits, total);
    }

    private static Hit hit(JsonParser parser, byte[] response, @Nullable String field) {
        String id = null;
        Slice source = null;
        List<BsonValue> values = List.of();
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken token = parser.nextToken();
            switch (name) {
                case "_id" -> id = parser.getString();
                case "_source" -> {
                    if (token == JsonToken.START_OBJECT) {
                        int start = (int) parser.currentTokenLocation().getByteOffset();
                        parser.skipChildren();
                        // just past the closing brace
                        int end = (int) parser.currentLocation().getByteOffset();
                        source = new Slice(response, start, end - start);
                    } else {
                        parser.skipChildren();
                    }
                }
                case "fields" -> values = fieldValues(parser, field);
                default -> parser.skipChildren();
            }
        }
        if (id == null) {
            throw new IllegalStateException("Search hit without _id");
        }
        return new Hit(id, source, values);
    }

    private static List<BsonValue> fieldValues(JsonParser parser, @Nullable String field) {
        List<BsonValue> values = List.of();
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            parser.nextToken();
            if (name.equals(field) && parser.currentToken() == JsonToken.START_ARRAY) {
                values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    values.add(SourceParser.value(parser));
                }
            } else {
                parser.skipChildren();
            }
        }
        return values;
    }

    private static long total(JsonParser parser) {
        long value = -1;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            parser.nextToken();
            if (name.equals("value")) {
                value = parser.getLongValue();
            } else {
                parser.skipChildren();
            }
        }
        return value;
    }
}
