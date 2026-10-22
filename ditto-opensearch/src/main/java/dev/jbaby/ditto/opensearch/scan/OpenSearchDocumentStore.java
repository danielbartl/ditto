package dev.jbaby.ditto.opensearch.scan;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.Store;
import dev.jbaby.ditto.comparator.engine.ComparisonEngine;
import dev.jbaby.ditto.comparator.engine.DocumentStore;
import dev.jbaby.ditto.opensearch.OpenSearchAccessException;
import dev.jbaby.ditto.opensearch.client.OpenSearchHttp;
import dev.jbaby.ditto.opensearch.client.SourceParser;

/**
 * Indices of one OpenSearch cluster. References carry the cluster name where MongoDB has the database, so reports
 * read {@code cluster.index}.
 */
public final class OpenSearchDocumentStore implements DocumentStore {

    private final OpenSearchHttp http;
    private final OpenSearchPreflight preflight;
    private final SourceParser parser;
    private final Duration scrollKeepAlive;
    private volatile @Nullable String clusterName;

    /** @param scrollKeepAlive how long OpenSearch keeps a scroll between two pages */
    public OpenSearchDocumentStore(OpenSearchHttp http, Duration scrollKeepAlive) {
        this.http = http;
        this.preflight = new OpenSearchPreflight(http);
        this.parser = new SourceParser(http.mapper());
        this.scrollKeepAlive = scrollKeepAlive;
    }

    @Override
    public Store kind() {
        return Store.OPENSEARCH;
    }

    /** The cluster name, asked once. */
    @Override
    public String defaultDatabase() {
        String name = clusterName;
        if (name == null) {
            name = http.tree(http.require("GET", "/", Map.of(), null)).path("cluster_name").asString("opensearch");
            clusterName = name;
        }
        return name;
    }

    @Override
    public Sides open(ComparisonSettings settings) {
        String cluster = defaultDatabase();
        for (CollectionRef ref : List.of(settings.baseline(), settings.candidate())) {
            if (!cluster.equals(ref.database())) {
                throw new ComparisonException("OpenSearch has no databases, but " + ref + " names '"
                        + ref.database() + "' (the cluster is '" + cluster + "'): give the index name only");
            }
        }
        OpenSearchPreflight.Result checked = preflight.run(settings.keyField(), settings.baseline(),
                settings.candidate());
        return new Sides(
                new OpenSearchIndexSource("baseline", settings.baseline(), checked.baselineKey(), http, parser,
                        scrollKeepAlive),
                new OpenSearchIndexSource("candidate", settings.candidate(), checked.candidateKey(), http, parser,
                        scrollKeepAlive),
                checked.result());
    }

    /** An interrupted request becomes a {@link ComparisonException}. */
    @Override
    public RuntimeException translate(RuntimeException e, ComparisonSettings settings) {
        if (e instanceof OpenSearchAccessException && Thread.currentThread().isInterrupted()) {
            return new ComparisonException("Comparison of " + ComparisonEngine.label(settings) + " interrupted", e);
        }
        return e;
    }
}
