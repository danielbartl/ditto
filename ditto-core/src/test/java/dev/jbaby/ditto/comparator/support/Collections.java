package dev.jbaby.ditto.comparator.support;

import java.util.Arrays;
import java.util.List;

import org.bson.BsonDocument;

import com.mongodb.client.MongoDatabase;

/**
 * Test helpers to set up collections from JSON.
 */
public final class Collections {

    private Collections() {
    }

    public static MongoDatabase database(String name) {
        return MongoContainer.client().getDatabase(name);
    }

    /** Drops and recreates {@code name} with the given documents (Extended JSON). */
    public static void create(MongoDatabase database, String name, String... documents) {
        create(database, name, Arrays.stream(documents).map(BsonDocument::parse).toList());
    }

    public static void create(MongoDatabase database, String name, List<BsonDocument> documents) {
        var collection = database.getCollection(name, BsonDocument.class);
        collection.drop();
        database.createCollection(name);
        if (!documents.isEmpty()) {
            collection.insertMany(documents);
        }
    }
}
