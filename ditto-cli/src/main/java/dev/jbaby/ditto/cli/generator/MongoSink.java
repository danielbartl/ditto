package dev.jbaby.ditto.cli.generator;

import java.util.List;

import org.bson.BsonDocument;

import com.mongodb.client.MongoDatabase;

/**
 * Writes generated documents into collections of one MongoDB database.
 */
public final class MongoSink implements DocumentSink {

    private final MongoDatabase database;

    public MongoSink(MongoDatabase database) {
        this.database = database;
    }

    @Override
    public void recreate(String name) {
        database.getCollection(name).drop();
        database.createCollection(name);
    }

    @Override
    public void insert(String name, List<BsonDocument> documents) {
        database.getCollection(name, BsonDocument.class).insertMany(documents);
    }

    @Override
    public String describe(String name) {
        return database.getName() + "." + name;
    }
}
