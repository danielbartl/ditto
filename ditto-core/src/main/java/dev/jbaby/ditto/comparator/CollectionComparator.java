package dev.jbaby.ditto.comparator;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.MongoDatabaseFactory;

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
import dev.jbaby.ditto.comparator.key.KeyInspector;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.report.ReportStore;
import dev.jbaby.ditto.comparator.scan.MongoDocumentStore;
import dev.jbaby.ditto.comparator.scan.Preflight;

/**
 * Compares two MongoDB collections and judges how similar they are. Without any configuration:
 * <pre>{@code
 * ComparisonReport report = comparator.compareWithBackup("products");   // products_backup -> products
 * if (report.verdict() == Level.RED) { ... }
 * report.hints().forEach(hint -> log.info(hint.message()));            // what to configure next
 * }</pre>
 * With options:
 * <pre>{@code
 * comparator.compare(comparator.backupRequest("products").ignoredPaths("meta.syncedAt").build());
 * }</pre>
 * Blocking; runs on the calling thread and honours thread interruption. Thread-safe: concurrent comparisons are
 * independent. Publishes a {@link ComparisonCompletedEvent} or {@link ComparisonFailedEvent} for every comparison.
 */
public class CollectionComparator implements DocumentComparator {

    private final ComparisonEngine engine;

    /**
     * Created by the auto-configuration; inject the bean instead of calling this. The constructor takes internal types
     * and may change in any release.
     *
     * @param reports          where reports are stored, {@code null} to not store them
     * @param thresholdAdvisor derives thresholds from history, {@code null} to always use the configured ones
     * @param events           receives a completed or failed event per comparison
     */
    public CollectionComparator(MongoDatabaseFactory databaseFactory, ComparatorProperties properties, Hasher hasher,
                                ReportAssembler assembler, @Nullable ReportStore reports,
                                @Nullable ThresholdAdvisor thresholdAdvisor, ApplicationEventPublisher events) {
        this.engine = new ComparisonEngine(new MongoDocumentStore(databaseFactory, new Preflight(new KeyInspector())),
                properties, hasher, assembler, reports, thresholdAdvisor, events);
    }

    /**
     * Compares {@code collection + backup-suffix} (default {@code _backup}) as baseline with {@code collection} as
     * candidate, in the default database, with all defaults.
     */
    @Override
    public ComparisonReport compareWithBackup(String collection) {
        return DocumentComparator.super.compareWithBackup(collection);
    }

    /** Compares two collections of the default database with all defaults. */
    @Override
    public ComparisonReport compare(String baselineCollection, String candidateCollection) {
        return DocumentComparator.super.compare(baselineCollection, candidateCollection);
    }

    /** A request builder for {@code collection + backup-suffix} vs. {@code collection}, to add options to. */
    @Override
    public ComparisonRequest.Builder backupRequest(String collection) {
        return engine.backupRequest(collection);
    }

    @Override
    public ComparisonReport compare(ComparisonRequest request) {
        return DocumentComparator.super.compare(request);
    }

    /**
     * @throws ComparisonException  if the comparison cannot be carried out (missing collection, unusable keys,
     *                              interrupted)
     * @throws DataAccessException  on database errors
     */
    @Override
    public ComparisonReport compare(ComparisonRequest request, ProgressListener listener) {
        return engine.compare(request, listener);
    }
}
