package dev.jbaby.ditto.comparator.api;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

/**
 * A document key in a JSON-friendly form, e.g. for example keys in the report.
 *
 * @param type  BSON type of the key, e.g. {@code OBJECT_ID}
 * @param value the key as relaxed Extended JSON, e.g. {@code {"$oid": "65f0..."}} or {@code "ABC-1"}; usable in a
 *              mongosh query as is
 */
public record KeyRef(String type, String value) {

    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final String FIELD = "k";

    public static KeyRef of(BsonValue key) {
        String json = new BsonDocument(FIELD, key).toJson(JSON);
        // {"k": <value>} -> <value>
        String value = json.substring(json.indexOf(':') + 1, json.length() - 1).strip();
        return new KeyRef(key.getBsonType().name(), value);
    }

    /** Parses the key back into its BSON value. */
    public BsonValue toBsonValue() {
        return BsonDocument.parse("{\"" + FIELD + "\": " + value + "}").get(FIELD);
    }
}
