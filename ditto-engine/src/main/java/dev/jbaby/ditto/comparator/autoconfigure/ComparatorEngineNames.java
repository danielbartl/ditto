package dev.jbaby.ditto.comparator.autoconfigure;

/**
 * Names of the store-specific auto-configurations, which the store-neutral ones order themselves after without
 * depending on their modules.
 */
final class ComparatorEngineNames {

    static final String MONGODB = "dev.jbaby.ditto.mongo.autoconfigure.MongoComparatorAutoConfiguration";
    static final String OPENSEARCH = "dev.jbaby.ditto.opensearch.autoconfigure.OpenSearchComparatorAutoConfiguration";

    private ComparatorEngineNames() {
    }
}
