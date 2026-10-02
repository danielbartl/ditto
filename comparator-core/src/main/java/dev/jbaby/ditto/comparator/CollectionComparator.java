package dev.jbaby.ditto.comparator;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.ProgressListener;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.metrics.ScanAccumulator;
import dev.jbaby.ditto.comparator.metrics.ScanResult;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.report.ReportRepository;
import dev.jbaby.ditto.comparator.scan.CollectionHandle;
import dev.jbaby.ditto.comparator.scan.MergeJoinComparator;
import dev.jbaby.ditto.comparator.scan.Preflight;
import dev.jbaby.ditto.comparator.scan.ProgressReporter;
import dev.jbaby.ditto.comparator.scan.SampleComparator;

/**
 * Compares two MongoDB collections and judges how similar they are.
 * <pre>{@code
 * ComparisonReport report = comparator.compare(ComparisonRequest.builder("products_backup", "products")
 *         .ignoredPaths("meta.syncedAt")
 *         .build());
 * if (report.verdict() == Level.RED) { ... }
 * }</pre>
 * Blocking; runs on the calling thread and honours thread interruption. Thread-safe: concurrent comparisons are
 * independent.
 */
public class CollectionComparator {

    private static final Logger log = LoggerFactory.getLogger(CollectionComparator.class);

    private final MongoDatabaseFactory databaseFactory;
    private final ComparatorProperties properties;
    private final Hasher hasher;
    private final Preflight preflight;
    private final MergeJoinComparator mergeJoin;
    private final SampleComparator sampler;
    private final ReportAssembler assembler;
    private final @Nullable ReportRepository repository;

    /**
     * @param repository where reports are stored, {@code null} to not store them
     */
    public CollectionComparator(MongoDatabaseFactory databaseFactory, ComparatorProperties properties, Hasher hasher,
                                Preflight preflight, MergeJoinComparator mergeJoin, SampleComparator sampler,
                                ReportAssembler assembler, @Nullable ReportRepository repository) {
        this.databaseFactory = databaseFactory;
        this.properties = properties;
        this.hasher = hasher;
        this.preflight = preflight;
        this.mergeJoin = mergeJoin;
        this.sampler = sampler;
        this.assembler = assembler;
        this.repository = repository;
    }

    public ComparisonReport compare(ComparisonRequest request) {
        return compare(request, ProgressListener.NONE);
    }

    /**
     * @throws ComparisonException  if the comparison cannot be carried out (missing collection, unusable keys,
     *                              interrupted)
     * @throws DataAccessException  on database errors
     */
    public ComparisonReport compare(ComparisonRequest request, ProgressListener listener) {
        ComparisonSettings settings = properties.settingsFor(request,
                databaseFactory.getMongoDatabase().getName());
        try {
            return run(settings, listener);
        } catch (MongoInterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ComparisonException("Comparison of " + label(settings) + " interrupted", e);
        } catch (MongoException e) {
            DataAccessException translated = databaseFactory.getExceptionTranslator().translateExceptionIfPossible(e);
            throw translated != null ? translated : e;
        }
    }

    private ComparisonReport run(ComparisonSettings settings, ProgressListener listener) {
        String label = label(settings);
        Instant startedAt = Instant.now();
        CollectionHandle baseline = CollectionHandle.of("baseline", settings.baseline(),
                databaseFactory.getMongoDatabase(settings.baseline().database()));
        CollectionHandle candidate = CollectionHandle.of("candidate", settings.candidate(),
                databaseFactory.getMongoDatabase(settings.candidate().database()));
        log.info("Comparing {}, mode {}", label, describe(settings.mode()));

        Preflight.Result checked = preflight.run(baseline, candidate, settings);
        checked.warnings().forEach(warning -> log.warn("{}: {}", label, warning));
        ScanAccumulator accumulator = new ScanAccumulator(settings, hasher);
        ScanResult scan = switch (settings.mode()) {
            case ComparisonMode.Full _ -> mergeJoin.compare(baseline, candidate, settings, accumulator,
                    new ProgressReporter(label, settings.tuning().progressInterval(),
                            checked.baselineCount() + checked.candidateCount(), listener));
            case ComparisonMode.Sample sample -> sampler.compare(baseline, candidate, settings, sample.size(),
                    accumulator, new ProgressReporter(label, settings.tuning().progressInterval(),
                            2L * sample.size(), listener));
        };
        ComparisonReport report = assembler.assemble(settings, checked, scan, startedAt, Instant.now());
        log.info("{}: verdict {} (keySimilarity {}, unchangedRate {}, {} ms)", label, report.verdict(),
                report.keys().keySimilarity().value(), report.content().unchangedRate().value(),
                report.run().durationMillis());
        store(report, label);
        return report;
    }

    private void store(ComparisonReport report, String label) {
        if (repository == null) {
            return;
        }
        try {
            repository.save(report);
        } catch (RuntimeException e) {
            // the report itself is valid; a failed write must not hide the verdict from the caller
            log.error("{}: storing report {} failed", label, report.id(), e);
        }
    }

    private static String label(ComparisonSettings settings) {
        return settings.baseline() + " -> " + settings.candidate();
    }

    private static String describe(ComparisonMode mode) {
        return switch (mode) {
            case ComparisonMode.Full _ -> "FULL";
            case ComparisonMode.Sample(int size) -> "SAMPLE (" + size + " keys per side)";
        };
    }
}
