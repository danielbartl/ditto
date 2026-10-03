package dev.jbaby.ditto.comparator.support;

import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

/**
 * One MongoDB container shared by all integration tests of the module, started on first use.
 */
public final class MongoContainer {

    public static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    private static MongoClient client;

    private MongoContainer() {
    }

    public static synchronized MongoClient client() {
        if (client == null) {
            MONGO.start();
            client = MongoClients.create(MONGO.getConnectionString());
        }
        return client;
    }

    public static String connectionString() {
        client();
        return MONGO.getConnectionString();
    }
}
