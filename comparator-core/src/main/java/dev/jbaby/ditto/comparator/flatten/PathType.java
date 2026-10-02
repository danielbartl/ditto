package dev.jbaby.ditto.comparator.flatten;

import org.bson.BsonType;

/**
 * A path occurring in a document together with the BSON type of its value there, e.g. {@code items[].price -> INT32}.
 */
public record PathType(String path, BsonType type) {
}
