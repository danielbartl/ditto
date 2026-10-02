package dev.jbaby.ditto.comparator.api;

import org.jspecify.annotations.Nullable;

/**
 * A collection, optionally in a database other than the default one of the {@code MongoClient}.
 *
 * @param database   database name, {@code null} for the default database
 * @param collection collection name
 */
public record CollectionRef(@Nullable String database, String collection) {

    public CollectionRef {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("collection must not be blank");
        }
        if (database != null && database.isBlank()) {
            throw new IllegalArgumentException("database must not be blank, use null for the default database");
        }
    }

    public static CollectionRef of(String collection) {
        return new CollectionRef(null, collection);
    }

    public static CollectionRef of(String database, String collection) {
        return new CollectionRef(database, collection);
    }

    /** This reference with {@code defaultDatabase} filled in if no database was given. */
    public CollectionRef withDefaultDatabase(String defaultDatabase) {
        return database != null ? this : new CollectionRef(defaultDatabase, collection);
    }

    @Override
    public String toString() {
        return database == null ? collection : database + "." + collection;
    }
}
