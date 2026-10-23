package dev.jbaby.ditto.mongo.scan;

import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.Store;
import dev.jbaby.ditto.comparator.engine.ComparisonEngine;
import dev.jbaby.ditto.comparator.engine.DocumentStore;

/**
 * Collections of the application's MongoDB connection, read as raw BSON and merge-joined in key order.
 */
public final class MongoDocumentStore implements DocumentStore {

    private final MongoDatabaseFactory databaseFactory;
    private final Preflight preflight;

    public MongoDocumentStore(MongoDatabaseFactory databaseFactory, Preflight preflight) {
        this.databaseFactory = databaseFactory;
        this.preflight = preflight;
    }

    @Override
    public Store kind() {
        return Store.MONGODB;
    }

    @Override
    public String defaultDatabase() {
        return databaseFactory.getMongoDatabase().getName();
    }

    @Override
    public Sides open(ComparisonSettings settings) {
        MongoCollectionSource baseline = MongoCollectionSource.of("baseline", settings.baseline(),
                databaseFactory.getMongoDatabase(settings.baseline().database()));
        MongoCollectionSource candidate = MongoCollectionSource.of("candidate", settings.candidate(),
                databaseFactory.getMongoDatabase(settings.candidate().database()));
        return new Sides(baseline, candidate, preflight.run(baseline, candidate, settings));
    }

    /** Interruption becomes a {@link ComparisonException}, driver exceptions {@link DataAccessException}s. */
    @Override
    public RuntimeException translate(RuntimeException e, ComparisonSettings settings) {
        return switch (e) {
            case MongoInterruptedException interrupted -> {
                Thread.currentThread().interrupt();
                yield new ComparisonException("Comparison of " + ComparisonEngine.label(settings) + " interrupted",
                        interrupted);
            }
            case MongoException mongo -> {
                DataAccessException translated =
                        databaseFactory.getExceptionTranslator().translateExceptionIfPossible(mongo);
                yield translated != null ? translated : mongo;
            }
            default -> e;
        };
    }
}
