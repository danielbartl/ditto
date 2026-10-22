package dev.jbaby.ditto.comparator.scan;

import org.bson.BsonValue;

/**
 * A document with its key.
 */
public record KeyedDocument(BsonValue key, SourceDocument document) {
}
