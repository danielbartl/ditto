package dev.jbaby.ditto.opensearch;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.context.ApplicationEventPublisher;

import dev.jbaby.ditto.comparator.DocumentComparator;
import dev.jbaby.ditto.comparator.api.ComparisonCompletedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonFailedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ProgressListener;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.engine.ComparisonEngine;
import dev.jbaby.ditto.comparator.history.ThresholdAdvisor;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.report.ReportStore;
import dev.jbaby.ditto.opensearch.client.OpenSearchHttp;
import dev.jbaby.ditto.opensearch.scan.OpenSearchDocumentStore;

/**
 * Compares two OpenSearch indices and judges how similar they are, with the same metrics, verdict and report as for
 * MongoDB collections. Without any configuration:
 * <pre>{@code
 * ComparisonReport report = comparator.compareWithBackup("products");   // products_backup -> products
 * if (report.verdict() == Level.RED) { ... }
 * report.hints().forEach(hint -> log.info(hint.message()));            // what to configure next
 * }</pre>
 * Documents are compared by their {@code _source}, matched by {@code _id} unless a key field is configured (a keyword
 * or integer field, or a text field with a keyword subfield). Names are index names or aliases of one index; reports
 * show them as {@code cluster.index}. Only refreshed documents are visible, so refresh the indices after writing.
 * <p>
 * Blocking; runs on the calling thread and honours thread interruption. Thread-safe: concurrent comparisons are
 * independent. Publishes a {@link ComparisonCompletedEvent} or {@link ComparisonFailedEvent} for every comparison.
 */
public class IndexComparator implements DocumentComparator {

    private final ComparisonEngine engine;

    /**
     * Created by the auto-configuration; inject the bean instead of calling this. The constructor takes internal types
     * and may change in any release.
     *
     * @param scrollKeepAlive  how long OpenSearch keeps a scroll between two pages
     * @param reports          where reports are stored, {@code null} to not store them
     * @param thresholdAdvisor derives thresholds from history, {@code null} to always use the configured ones
     * @param events           receives a completed or failed event per comparison
     */
    public IndexComparator(OpenSearchClient client, Duration scrollKeepAlive, ComparatorProperties properties,
                           Hasher hasher, ReportAssembler assembler, @Nullable ReportStore reports,
                           @Nullable ThresholdAdvisor thresholdAdvisor, ApplicationEventPublisher events) {
        this.engine = new ComparisonEngine(new OpenSearchDocumentStore(new OpenSearchHttp(client), scrollKeepAlive),
                properties, hasher, assembler, reports, thresholdAdvisor, events);
    }

    /**
     * Compares {@code index + backup-suffix} (default {@code _backup}) as baseline with {@code index} as candidate,
     * with all defaults.
     */
    @Override
    public ComparisonReport compareWithBackup(String index) {
        return DocumentComparator.super.compareWithBackup(index);
    }

    /** Compares two indices with all defaults. */
    @Override
    public ComparisonReport compare(String baselineIndex, String candidateIndex) {
        return DocumentComparator.super.compare(baselineIndex, candidateIndex);
    }

    /** A request builder for {@code index + backup-suffix} vs. {@code index}, to add options to. */
    @Override
    public ComparisonRequest.Builder backupRequest(String index) {
        return engine.backupRequest(index);
    }

    @Override
    public ComparisonReport compare(ComparisonRequest request) {
        return DocumentComparator.super.compare(request);
    }

    /**
     * @throws ComparisonException       if the comparison cannot be carried out (missing index, unusable keys,
     *                                   interrupted)
     * @throws OpenSearchAccessException if OpenSearch cannot be reached or answers with an error
     */
    @Override
    public ComparisonReport compare(ComparisonRequest request, ProgressListener listener) {
        return engine.compare(request, listener);
    }
}
