package dev.jbaby.ditto.comparator.report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Labels;

/**
 * Stores reports in a MongoDB collection (default {@code comparison_reports}), e.g. to derive thresholds from
 * historical runs. A stored document is the report's JSON with {@code _id} = report id, plus top-level query fields
 * {@code createdAt} (date), {@code baseline} and {@code candidate} ({@code db.collection}). The report's labels are
 * stored as the subdocument {@code labels}, e.g. {@code labels.batchJobId}.
 * <p>On first use it creates the indexes for its queries and, if a retention is set, a TTL index on {@code createdAt}
 * that lets MongoDB delete older reports. Without a retention, a TTL index left from an earlier configuration is
 * dropped, so reports are kept. If the indexes can't be created (e.g. missing privileges), a warning is logged and
 * reading and writing work as before.
 * <p>A {@linkplain #readOnly read-only} repository only reads: it neither stores reports nor touches the indexes, so a
 * tool that just looks at reports can't change the retention the writing application configured.
 */
public final class ReportRepository {

    private static final Logger log = LoggerFactory.getLogger(ReportRepository.class);
    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final List<String> QUERY_FIELDS = List.of("_id", "createdAt", "baseline", "candidate");
    static final String HISTORY_INDEX = "ditto_history";
    static final String RECENT_INDEX = "ditto_recent";
    static final String RETENTION_INDEX = "ditto_retention";
    static final String LABELS_INDEX = "ditto_labels";

    private final MongoDatabaseFactory databaseFactory;
    private final @Nullable String database;
    private final String collection;
    private final @Nullable Duration retention;
    private final ReportJson json;
    private final boolean readOnly;
    private volatile boolean indexed;

    /**
     * Keeps reports forever.
     *
     * @param database   database of the report collection, {@code null} for the default database
     * @param collection report collection
     */
    public ReportRepository(MongoDatabaseFactory databaseFactory, @Nullable String database, String collection,
                            ReportJson json) {
        this(databaseFactory, database, collection, null, json);
    }

    /**
     * @param database   database of the report collection, {@code null} for the default database
     * @param collection report collection
     * @param retention  how long MongoDB keeps a report before deleting it, {@code null} to keep reports forever
     */
    public ReportRepository(MongoDatabaseFactory databaseFactory, @Nullable String database, String collection,
                            @Nullable Duration retention, ReportJson json) {
        this(databaseFactory, database, collection, retention, json, false);
    }

    private ReportRepository(MongoDatabaseFactory databaseFactory, @Nullable String database, String collection,
                             @Nullable Duration retention, ReportJson json, boolean readOnly) {
        if (retention != null && retention.toSeconds() < 1) {
            throw new IllegalArgumentException("retention must be at least one second, was " + retention);
        }
        this.databaseFactory = databaseFactory;
        this.database = database;
        this.collection = collection;
        this.retention = retention;
        this.json = json;
        this.readOnly = readOnly;
    }

    /**
     * A repository that only reads reports: it creates, changes or drops no index, and {@link #save} fails.
     *
     * @param database   database of the report collection, {@code null} for the default database
     * @param collection report collection
     */
    public static ReportRepository readOnly(MongoDatabaseFactory databaseFactory, @Nullable String database,
                                            String collection, ReportJson json) {
        return new ReportRepository(databaseFactory, database, collection, null, json, true);
    }

    /** @throws UnsupportedOperationException if this repository is {@linkplain #readOnly read-only} */
    public void save(ComparisonReport report) {
        if (readOnly) {
            throw new UnsupportedOperationException("This report repository is read-only");
        }
        Document document = Document.parse(json.write(report));
        document.remove("id");
        Document stored = new Document("_id", report.id())
                .append("createdAt", Date.from(report.run().finishedAt()))
                .append("baseline", report.run().settings().baseline().toString())
                .append("candidate", report.run().settings().candidate().toString());
        stored.putAll(document);
        collection().insertOne(stored);
    }

    public Optional<ComparisonReport> findById(String id) {
        return Optional.ofNullable(collection().find(Filters.eq("_id", id)).first()).map(this::toReport);
    }

    /**
     * Most recent reports first.
     *
     * @param candidate only reports for this candidate ({@code db.collection}), all if {@code null}
     * @param limit     maximum number of reports
     */
    public List<ComparisonReport> findRecent(@Nullable String candidate, int limit) {
        Bson filter = candidate == null ? Filters.empty() : Filters.eq("candidate", candidate);
        List<ComparisonReport> reports = new ArrayList<>();
        collection().find(filter).sort(Sorts.descending("createdAt")).limit(limit)
                .forEach(document -> reports.add(toReport(document)));
        return reports;
    }

    /**
     * Reports carrying all the given labels, e.g. {@code batchJobId=4711}, most recent first.
     *
     * @param labels labels a report must have, with these values; all reports if empty
     * @param limit  maximum number of reports
     */
    public List<ComparisonReport> findByLabels(Map<String, String> labels, int limit) {
        Bson filter = labels.isEmpty() ? Filters.empty() : Filters.and(Labels.of(labels).entrySet().stream()
                .map(label -> Filters.eq("labels." + label.getKey(), label.getValue())).toList());
        List<ComparisonReport> reports = new ArrayList<>();
        collection().find(filter).sort(Sorts.descending("createdAt")).limit(limit)
                .forEach(document -> reports.add(toReport(document)));
        return reports;
    }

    /**
     * Previous comparisons of the same pair of collections, most recent first, for deriving thresholds.
     *
     * @param baseline     {@code db.collection} of the baseline
     * @param candidate    {@code db.collection} of the candidate
     * @param includeRed   whether RED reports are included
     * @param limit        maximum number of reports
     */
    public List<ComparisonReport> findHistory(String baseline, String candidate, boolean includeRed, int limit) {
        Bson filter = Filters.and(Filters.eq("baseline", baseline), Filters.eq("candidate", candidate));
        if (!includeRed) {
            filter = Filters.and(filter, Filters.ne("verdict", "RED"));
        }
        List<ComparisonReport> reports = new ArrayList<>();
        collection().find(filter).sort(Sorts.descending("createdAt")).limit(limit).forEach(document -> {
            try {
                reports.add(toReport(document));
            } catch (RuntimeException e) {
                log.warn("Skipping unreadable report {} in the history of {}: {}", document.get("_id"), candidate,
                        e.getMessage());
            }
        });
        return reports;
    }

    private ComparisonReport toReport(Document stored) {
        Document document = new Document("id", stored.get("_id"));
        stored.forEach((name, value) -> {
            if (!QUERY_FIELDS.contains(name)) {
                document.put(name, value);
            }
        });
        return json.read(document.toJson(RELAXED));
    }

    private MongoCollection<Document> collection() {
        var db = database == null ? databaseFactory.getMongoDatabase() : databaseFactory.getMongoDatabase(database);
        MongoCollection<Document> reports = db.getCollection(collection);
        if (!indexed && !readOnly) {
            ensureIndexes(db, reports);
        }
        return reports;
    }

    // once per instance; a failure is not retried, so it is logged only once
    private synchronized void ensureIndexes(MongoDatabase db, MongoCollection<Document> reports) {
        if (indexed) {
            return;
        }
        indexed = true;
        try {
            reports.createIndex(Indexes.compoundIndex(Indexes.ascending("baseline", "candidate"),
                    Indexes.descending("createdAt")), new IndexOptions().name(HISTORY_INDEX));
            reports.createIndex(Indexes.compoundIndex(Indexes.ascending("candidate"), Indexes.descending("createdAt")),
                    new IndexOptions().name(RECENT_INDEX));
            reports.createIndex(Indexes.ascending("labels.$**"), new IndexOptions().name(LABELS_INDEX));
            applyRetention(db, reports);
        } catch (MongoException e) {
            log.warn("Could not create the indexes of the report collection {}.{}: {}. Reports are still stored, but"
                     + " reading the history scans the collection{}.", db.getName(), collection, e.getMessage(),
                    retention == null ? "" : " and the retention of " + retention + " is not applied");
        }
    }

    private void applyRetention(MongoDatabase db, MongoCollection<Document> reports) {
        Document existing = null;
        for (Document index : reports.listIndexes()) {
            if (RETENTION_INDEX.equals(index.getString("name"))) {
                existing = index;
            }
        }
        if (retention == null) {
            if (existing != null) {
                reports.dropIndex(RETENTION_INDEX);
                log.info("No retention configured: dropped the TTL index of {}.{}, reports are kept",
                        db.getName(), collection);
            }
            return;
        }
        long seconds = retention.toSeconds();
        if (existing == null) {
            reports.createIndex(Indexes.ascending("createdAt"),
                    new IndexOptions().name(RETENTION_INDEX).expireAfter(seconds, TimeUnit.SECONDS));
        } else if (!(existing.get("expireAfterSeconds") instanceof Number current) || current.longValue() != seconds) {
            db.runCommand(new Document("collMod", collection)
                    .append("index", new Document("name", RETENTION_INDEX).append("expireAfterSeconds", seconds)));
            log.info("Changed the retention of {}.{} to {}", db.getName(), collection, retention);
        }
    }
}
