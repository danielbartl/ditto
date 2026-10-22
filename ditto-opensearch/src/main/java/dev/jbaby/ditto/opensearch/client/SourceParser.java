package dev.jbaby.ditto.opensearch.client;

import java.math.BigDecimal;

import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.Decimal128;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts JSON (a document's {@code _source}) into BSON, keeping numbers exact:
 * <ul>
 * <li>integers become INT32 or INT64, larger ones DECIMAL128 (DOUBLE beyond 34 digits)</li>
 * <li>decimals become DOUBLE if a double holds them exactly as written, else DECIMAL128</li>
 * <li>strings, booleans, null, objects and arrays map directly; dates stay strings, as JSON has no date type</li>
 * </ul>
 * Field order is kept. Thread-safe.
 */
public final class SourceParser {

    private final JsonMapper mapper;

    public SourceParser(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** @throws StreamReadException if the JSON is not an object */
    public BsonDocument parse(Slice json) {
        try (JsonParser parser = mapper.createParser(json.bytes(), json.offset(), json.length())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new StreamReadException(parser, "Expected a JSON object");
            }
            return document(parser);
        }
    }

    /** The value at the parser's current token, which must be a value or the start of an object or array. */
    public static BsonValue value(JsonParser parser) {
        JsonToken token = parser.currentToken();
        return switch (token) {
            case START_OBJECT -> document(parser);
            case START_ARRAY -> array(parser);
            case VALUE_STRING -> new BsonString(parser.getString());
            case VALUE_NUMBER_INT -> integer(parser);
            case VALUE_NUMBER_FLOAT -> decimal(parser.getDecimalValue());
            case VALUE_TRUE -> BsonBoolean.TRUE;
            case VALUE_FALSE -> BsonBoolean.FALSE;
            case VALUE_NULL -> BsonNull.VALUE;
            default -> throw new StreamReadException(parser, "Unexpected token " + token);
        };
    }

    private static BsonDocument document(JsonParser parser) {
        BsonDocument document = new BsonDocument();
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            parser.nextToken();
            document.put(name, value(parser));
        }
        return document;
    }

    private static BsonArray array(JsonParser parser) {
        BsonArray array = new BsonArray();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            array.add(value(parser));
        }
        return array;
    }

    private static BsonValue integer(JsonParser parser) {
        return switch (parser.getNumberType()) {
            case INT -> new BsonInt32(parser.getIntValue());
            case LONG -> new BsonInt64(parser.getLongValue());
            default -> big(new BigDecimal(parser.getBigIntegerValue()));
        };
    }

    static BsonValue decimal(BigDecimal value) {
        double asDouble = value.doubleValue();
        if (Double.isFinite(asDouble) && new BigDecimal(Double.toString(asDouble)).compareTo(value) == 0) {
            return new BsonDouble(asDouble);
        }
        return big(value);
    }

    private static BsonValue big(BigDecimal value) {
        try {
            return new BsonDecimal128(new Decimal128(value));
        } catch (NumberFormatException tooPrecise) {
            return new BsonDouble(value.doubleValue());
        }
    }
}
