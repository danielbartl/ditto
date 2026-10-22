package dev.jbaby.ditto.cli.generator;

import java.util.List;

import org.bson.BsonDocument;

/**
 * Where generated documents go: a MongoDB collection or an OpenSearch index.
 */
public interface DocumentSink {

    /** Drops the collection or index if it exists and creates it empty. */
    void recreate(String name);

    void insert(String name, List<BsonDocument> documents);

    /** Called after the last insert, e.g. to make the documents visible. */
    default void finish(String name) {
    }

    /** The name as shown to the user, e.g. {@code db.collection}. */
    String describe(String name);
}
