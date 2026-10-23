package dev.jbaby.ditto.cli;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jspecify.annotations.Nullable;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.stereotype.Component;

import dev.jbaby.ditto.cli.generator.DocumentSink;
import dev.jbaby.ditto.cli.generator.MongoSink;
import dev.jbaby.ditto.cli.generator.OpenSearchSink;
import dev.jbaby.ditto.comparator.DocumentComparator;
import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.report.ReportStore;
import dev.jbaby.ditto.mongo.CollectionComparator;
import dev.jbaby.ditto.mongo.report.ReportRepository;
import dev.jbaby.ditto.opensearch.IndexComparator;
import dev.jbaby.ditto.opensearch.client.OpenSearchHttp;
import dev.jbaby.ditto.opensearch.report.OpenSearchReportRepository;

/**
 * Where the CLI works: MongoDB ({@code --uri}, {@code --db}) or, with {@code --opensearch=<url>}, OpenSearch. Only
 * the chosen store is connected.
 */
@Component
public class Target {

    private final CliOptions options;
    private final ComparatorProperties properties;
    private final ReportJson json;
    private final ObjectProvider<CollectionComparator> collections;
    private final ObjectProvider<MongoDatabaseFactory> mongo;
    private final ObjectProvider<IndexComparator> indices;
    private final ObjectProvider<OpenSearchClient> openSearch;

    public Target(CliOptions options, ComparatorProperties properties, ReportJson json,
                  ObjectProvider<CollectionComparator> collections, ObjectProvider<MongoDatabaseFactory> mongo,
                  ObjectProvider<IndexComparator> indices, ObjectProvider<OpenSearchClient> openSearch) {
        this.options = options;
        this.properties = properties;
        this.json = json;
        this.collections = collections;
        this.mongo = mongo;
        this.indices = indices;
        this.openSearch = openSearch;
    }

    public boolean isOpenSearch() {
        return options.string("opensearch") != null;
    }

    /** "collection" or "index", for messages. */
    public String noun() {
        return isOpenSearch() ? "index" : "collection";
    }

    public DocumentComparator comparator() {
        return isOpenSearch() ? indices.getObject() : collections.getObject();
    }

    /** The configured report collection or index, read-only. */
    public ReportStore reports() {
        var persistence = properties.getPersistence();
        return isOpenSearch()
                ? OpenSearchReportRepository.readOnly(openSearch.getObject(), persistence.getCollection(), json)
                : ReportRepository.readOnly(mongo.getObject(), persistence.getDatabase(), persistence.getCollection(),
                json);
    }

    /** Where {@link #reports()} reads, for messages. */
    public String reportLocation() {
        var persistence = properties.getPersistence();
        return isOpenSearch() ? "index " + persistence.getCollection()
                : qualify(persistence.getDatabase(), persistence.getCollection());
    }

    /**
     * A collection or index as reports name it: {@code db.collection} (the default database if none is given) or
     * {@code cluster.index}.
     */
    public String qualify(@Nullable String database, String name) {
        if (isOpenSearch()) {
            try {
                return openSearch.getObject().info().clusterName() + "." + name;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot reach OpenSearch: " + e.getMessage(), e);
            }
        }
        return new CollectionRef(database, name).withDefaultDatabase(mongo.getObject().getMongoDatabase().getName())
                .toString();
    }

    public DocumentSink sink() {
        return isOpenSearch() ? new OpenSearchSink(new OpenSearchHttp(openSearch.getObject()))
                : new MongoSink(mongo.getObject().getMongoDatabase());
    }

    /** Connection options for a printed command line. */
    public String connection() {
        return isOpenSearch() ? "--opensearch=" + options.string("opensearch")
                : "--db=" + mongo.getObject().getMongoDatabase().getName();
    }
}
