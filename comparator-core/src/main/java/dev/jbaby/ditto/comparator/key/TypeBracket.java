package dev.jbaby.ditto.comparator.key;

import org.bson.BsonType;

/**
 * MongoDB's canonical type order: values of different brackets sort by bracket, values within a bracket by value.
 * Declared in sort order. All numeric types share {@link #NUMBER}, strings and symbols share {@link #STRING}.
 */
public enum TypeBracket {
    MIN_KEY,
    UNDEFINED,
    NULL,
    NUMBER,
    STRING,
    OBJECT,
    ARRAY,
    BINARY,
    OBJECT_ID,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    REGEX,
    DB_POINTER,
    JAVASCRIPT,
    JAVASCRIPT_WITH_SCOPE,
    MAX_KEY;

    public static TypeBracket of(BsonType type) {
        return switch (type) {
            case MIN_KEY -> MIN_KEY;
            case UNDEFINED, END_OF_DOCUMENT -> UNDEFINED;
            case NULL -> NULL;
            case INT32, INT64, DOUBLE, DECIMAL128 -> NUMBER;
            case STRING, SYMBOL -> STRING;
            case DOCUMENT -> OBJECT;
            case ARRAY -> ARRAY;
            case BINARY -> BINARY;
            case OBJECT_ID -> OBJECT_ID;
            case BOOLEAN -> BOOLEAN;
            case DATE_TIME -> DATE;
            case TIMESTAMP -> TIMESTAMP;
            case REGULAR_EXPRESSION -> REGEX;
            case DB_POINTER -> DB_POINTER;
            case JAVASCRIPT -> JAVASCRIPT;
            case JAVASCRIPT_WITH_SCOPE -> JAVASCRIPT_WITH_SCOPE;
            case MAX_KEY -> MAX_KEY;
        };
    }
}
