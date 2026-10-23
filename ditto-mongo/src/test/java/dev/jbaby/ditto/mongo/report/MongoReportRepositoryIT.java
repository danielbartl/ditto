package dev.jbaby.ditto.mongo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.mongo.support.Collections;
import dev.jbaby.ditto.mongo.support.MongoContainer;

class MongoReportRepositoryIT {

    private static final String DATABASE = "report_repository_it";
    private static final String COLLECTION = "reports";

    private final SimpleMongoClientDatabaseFactory factory =
            new SimpleMongoClientDatabaseFactory(MongoContainer.client(), DATABASE);

    @BeforeEach
    void dropReports() {
        reports().drop();
    }

    @Test
    void createsQueryIndexesOnFirstUse() {
        repository(null).findRecent(null, 1);

        assertThat(indexNames()).contains(MongoReportRepository.HISTORY_INDEX, MongoReportRepository.RECENT_INDEX,
                        MongoReportRepository.LABELS_INDEX)
                .doesNotContain(MongoReportRepository.RETENTION_INDEX);
        assertThat(index(MongoReportRepository.HISTORY_INDEX).get("key", Document.class))
                .isEqualTo(Document.parse("{baseline: 1, candidate: 1, createdAt: -1}"));
    }

    @Test
    void historyAndRecentQueriesUseTheIndexes() {
        repository(null).findRecent(null, 1);

        // the queries of findHistory, findRecent and findByLabels
        var history = reports().find(Filters.and(Filters.eq("baseline", "db.a_backup"), Filters.eq("candidate", "db.a"),
                Filters.ne("verdict", "RED"))).sort(Sorts.descending("createdAt")).limit(20).explain();
        var recent = reports().find(Filters.eq("candidate", "db.a")).sort(Sorts.descending("createdAt")).limit(50)
                .explain();

        var labels = reports().find(Filters.eq("labels.batchJobId", "4711")).sort(Sorts.descending("createdAt"))
                .limit(50).explain();

        assertThat(history.toJson()).contains(MongoReportRepository.HISTORY_INDEX).doesNotContain("COLLSCAN");
        assertThat(labels.toJson()).contains(MongoReportRepository.LABELS_INDEX).doesNotContain("COLLSCAN");
        assertThat(recent.toJson()).contains(MongoReportRepository.RECENT_INDEX).doesNotContain("COLLSCAN");
    }

    @Test
    void retentionCreatesChangesAndDropsTheTtlIndex() {
        repository(Duration.ofDays(30)).findRecent(null, 1);
        assertThat(index(MongoReportRepository.RETENTION_INDEX).get("expireAfterSeconds", Number.class).longValue())
                .isEqualTo(Duration.ofDays(30).toSeconds());

        repository(Duration.ofDays(7)).findRecent(null, 1);
        assertThat(index(MongoReportRepository.RETENTION_INDEX).get("expireAfterSeconds", Number.class).longValue())
                .as("changed in place").isEqualTo(Duration.ofDays(7).toSeconds());

        repository(Duration.ofDays(7)).findRecent(null, 1);
        repository(null).findRecent(null, 1);
        assertThat(indexNames()).as("no retention keeps reports").doesNotContain(MongoReportRepository.RETENTION_INDEX);
    }

    @Test
    void aReadOnlyRepositoryReadsWithoutTouchingTheIndexes() throws IOException {
        ComparisonReport report;
        try (var in = getClass().getResourceAsStream("/reports/report-v0.1.0.json")) {
            report = new ReportJson().read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        repository(Duration.ofDays(30)).save(report);
        List<String> indexes = indexNames();

        MongoReportRepository readOnly = MongoReportRepository.readOnly(factory, null, COLLECTION, new ReportJson());

        assertThat(readOnly.findById(report.id())).contains(report);
        assertThat(readOnly.findRecent(null, 10)).containsExactly(report);
        assertThat(indexNames()).as("the retention is kept").isEqualTo(indexes)
                .contains(MongoReportRepository.RETENTION_INDEX);
        assertThatThrownBy(() -> readOnly.save(report)).isInstanceOf(UnsupportedOperationException.class);
    }

    private MongoReportRepository repository(@Nullable Duration retention) {
        return new MongoReportRepository(factory, null, COLLECTION, retention, new ReportJson());
    }

    private MongoCollection<Document> reports() {
        return Collections.database(DATABASE).getCollection(COLLECTION);
    }

    private List<String> indexNames() {
        return reports().listIndexes().map(index -> index.getString("name")).into(new ArrayList<>());
    }

    private Document index(String name) {
        return reports().listIndexes().into(new ArrayList<>()).stream()
                .filter(index -> name.equals(index.getString("name"))).findFirst().orElseThrow();
    }
}
