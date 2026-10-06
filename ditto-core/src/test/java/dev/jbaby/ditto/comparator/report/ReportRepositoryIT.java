package dev.jbaby.ditto.comparator.report;

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
import dev.jbaby.ditto.comparator.support.Collections;
import dev.jbaby.ditto.comparator.support.MongoContainer;

class ReportRepositoryIT {

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

        assertThat(indexNames()).contains(ReportRepository.HISTORY_INDEX, ReportRepository.RECENT_INDEX,
                        ReportRepository.LABELS_INDEX)
                .doesNotContain(ReportRepository.RETENTION_INDEX);
        assertThat(index(ReportRepository.HISTORY_INDEX).get("key", Document.class))
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

        assertThat(history.toJson()).contains(ReportRepository.HISTORY_INDEX).doesNotContain("COLLSCAN");
        assertThat(labels.toJson()).contains(ReportRepository.LABELS_INDEX).doesNotContain("COLLSCAN");
        assertThat(recent.toJson()).contains(ReportRepository.RECENT_INDEX).doesNotContain("COLLSCAN");
    }

    @Test
    void retentionCreatesChangesAndDropsTheTtlIndex() {
        repository(Duration.ofDays(30)).findRecent(null, 1);
        assertThat(index(ReportRepository.RETENTION_INDEX).get("expireAfterSeconds", Number.class).longValue())
                .isEqualTo(Duration.ofDays(30).toSeconds());

        repository(Duration.ofDays(7)).findRecent(null, 1);
        assertThat(index(ReportRepository.RETENTION_INDEX).get("expireAfterSeconds", Number.class).longValue())
                .as("changed in place").isEqualTo(Duration.ofDays(7).toSeconds());

        repository(Duration.ofDays(7)).findRecent(null, 1);
        repository(null).findRecent(null, 1);
        assertThat(indexNames()).as("no retention keeps reports").doesNotContain(ReportRepository.RETENTION_INDEX);
    }

    @Test
    void aReadOnlyRepositoryReadsWithoutTouchingTheIndexes() throws IOException {
        ComparisonReport report;
        try (var in = getClass().getResourceAsStream("/reports/report-v0.1.0.json")) {
            report = new ReportJson().read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        repository(Duration.ofDays(30)).save(report);
        List<String> indexes = indexNames();

        ReportRepository readOnly = ReportRepository.readOnly(factory, null, COLLECTION, new ReportJson());

        assertThat(readOnly.findById(report.id())).contains(report);
        assertThat(readOnly.findRecent(null, 10)).containsExactly(report);
        assertThat(indexNames()).as("the retention is kept").isEqualTo(indexes)
                .contains(ReportRepository.RETENTION_INDEX);
        assertThatThrownBy(() -> readOnly.save(report)).isInstanceOf(UnsupportedOperationException.class);
    }

    private ReportRepository repository(@Nullable Duration retention) {
        return new ReportRepository(factory, null, COLLECTION, retention, new ReportJson());
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
