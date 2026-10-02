package dev.jbaby.ditto.comparator.report;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.MongoDatabaseFactory;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import dev.jbaby.ditto.comparator.api.ComparisonReport;

/**
 * Stores reports in a MongoDB collection (default {@code comparison_reports}), e.g. to derive thresholds from
 * historical runs. A stored document is the report's JSON with {@code _id} = report id, plus top-level query fields
 * {@code createdAt} (date), {@code baseline} and {@code candidate} ({@code db.collection}).
 */
public final class ReportRepository {

    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final List<String> QUERY_FIELDS = List.of("_id", "createdAt", "baseline", "candidate");

    private final MongoDatabaseFactory databaseFactory;
    private final @Nullable String database;
    private final String collection;
    private final ReportJson json;

    /**
     * @param database   database of the report collection, {@code null} for the default database
     * @param collection report collection
     */
    public ReportRepository(MongoDatabaseFactory databaseFactory, @Nullable String database, String collection,
                            ReportJson json) {
        this.databaseFactory = databaseFactory;
        this.database = database;
        this.collection = collection;
        this.json = json;
    }

    public void save(ComparisonReport report) {
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
        return db.getCollection(collection);
    }
}
