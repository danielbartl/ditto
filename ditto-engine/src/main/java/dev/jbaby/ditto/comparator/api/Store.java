package dev.jbaby.ditto.comparator.api;

/**
 * The kind of database both sides of a comparison live in.
 */
public enum Store {

    /** MongoDB collections; {@link CollectionRef} is {@code database.collection}. */
    MONGODB,

    /** OpenSearch indices; {@link CollectionRef} is {@code cluster.index}. */
    OPENSEARCH
}
