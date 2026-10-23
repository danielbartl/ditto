package dev.jbaby.ditto.comparator.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Level;

class FileReportStoreTest {

    private static final ReportJson JSON = new ReportJson();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.now();
    private static String fixture;

    @TempDir
    Path directory;

    @BeforeAll
    static void readFixture() throws IOException {
        try (var in = FileReportStoreTest.class.getResourceAsStream("/reports/report-v0.1.0.json")) {
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void storesAReportAsAJsonFileNamedByTimeAndId() throws IOException {
        var store = new FileReportStore(directory, null, JSON);
        ComparisonReport report = report("a1", Instant.parse("2026-10-23T08:15:02.311Z"), "products", Level.GREEN,
                Map.of());

        store.save(report);

        Path file = directory.resolve("2026-10-23T08-15-02.311Z_a1.json");
        assertThat(files()).containsExactly(file);
        assertThat(Files.readString(file)).isEqualTo(JSON.writePretty(report));
        assertThat(store.findById("a1")).contains(report);
        assertThat(store.findById("a2")).isEmpty();
    }

    @Test
    void findsTheMostRecentReportsFirst() {
        var store = new FileReportStore(directory, null, JSON);
        ComparisonReport oldest = report("r1", NOW.minusSeconds(30), "products", Level.GREEN, Map.of());
        ComparisonReport other = report("r2", NOW.minusSeconds(20), "orders", Level.GREEN, Map.of());
        ComparisonReport newest = report("r3", NOW.minusSeconds(10), "products", Level.RED, Map.of());
        Stream.of(newest, oldest, other).forEach(store::save);

        assertThat(store.findRecent(null, 10)).containsExactly(newest, other, oldest);
        assertThat(store.findRecent(null, 2)).containsExactly(newest, other);
        assertThat(store.findRecent("ditto.products", 10)).containsExactly(newest, oldest);
        assertThat(store.findRecent("ditto.customers", 10)).isEmpty();
    }

    @Test
    void findsReportsByLabels() {
        var store = new FileReportStore(directory, null, JSON);
        ComparisonReport job1 = report("l1", NOW.minusSeconds(20), "products", Level.GREEN,
                Map.of("batchJobId", "1", "env", "test"));
        ComparisonReport job2 = report("l2", NOW.minusSeconds(10), "products", Level.GREEN,
                Map.of("batchJobId", "2", "env", "test"));
        Stream.of(job1, job2).forEach(store::save);

        assertThat(store.findByLabels(Map.of("batchJobId", "1"), 10)).containsExactly(job1);
        assertThat(store.findByLabels(Map.of("env", "test"), 10)).containsExactly(job2, job1);
        assertThat(store.findByLabels(Map.of("env", "test", "batchJobId", "2"), 10)).containsExactly(job2);
        assertThat(store.findByLabels(Map.of("env", "prod"), 10)).isEmpty();
        assertThat(store.findByLabels(Map.of(), 10)).containsExactly(job2, job1);
        assertThatIllegalArgumentException().isThrownBy(() -> store.findByLabels(Map.of("bad key", "x"), 10));
    }

    @Test
    void theHistoryHoldsThePairAndLeavesOutRedUnlessAsked() {
        var store = new FileReportStore(directory, null, JSON);
        ComparisonReport green = report("h1", NOW.minusSeconds(40), "products", Level.GREEN, Map.of());
        ComparisonReport red = report("h2", NOW.minusSeconds(30), "products", Level.RED, Map.of());
        ComparisonReport yellow = report("h3", NOW.minusSeconds(20), "products", Level.YELLOW, Map.of());
        ComparisonReport otherPair = report("h4", NOW.minusSeconds(10), "orders", Level.GREEN, Map.of());
        Stream.of(green, red, yellow, otherPair).forEach(store::save);

        assertThat(store.findHistory("ditto.demo_backup", "ditto.products", false, 10)).containsExactly(yellow, green);
        assertThat(store.findHistory("ditto.demo_backup", "ditto.products", true, 10))
                .containsExactly(yellow, red, green);
        assertThat(store.findHistory("ditto.demo_backup", "ditto.products", false, 1)).containsExactly(yellow);
        assertThat(store.findHistory("ditto.other_backup", "ditto.products", true, 10)).isEmpty();
    }

    @Test
    void seesTheReportsOfOtherInstances() {
        var writer = new FileReportStore(directory, null, JSON);
        var reader = new FileReportStore(directory, null, JSON);
        ComparisonReport first = report("i1", NOW.minusSeconds(20), "products", Level.GREEN, Map.of());
        ComparisonReport second = report("i2", NOW.minusSeconds(10), "products", Level.GREEN, Map.of());

        writer.save(first);
        assertThat(reader.findRecent(null, 10)).containsExactly(first);
        writer.save(second);

        assertThat(reader.findRecent(null, 10)).containsExactly(second, first);
        assertThat(reader.findById("i2")).contains(second);
    }

    @Test
    void theRetentionDeletesOlderReportsOnSave() {
        ComparisonReport old = report("o1", NOW.minus(Duration.ofDays(10)), "products", Level.GREEN, Map.of());
        ComparisonReport recent = report("o2", NOW.minus(Duration.ofDays(2)), "products", Level.GREEN, Map.of());
        new FileReportStore(directory, null, JSON).save(old);
        var store = new FileReportStore(directory, Duration.ofDays(7), JSON);

        store.save(recent);

        assertThat(store.findRecent(null, 10)).containsExactly(recent);
        assertThat(files()).hasSize(1);
    }

    @Test
    void ignoresOtherFilesAndSkipsUnreadableReports() throws IOException {
        var store = new FileReportStore(directory, null, JSON);
        ComparisonReport report = report("u1", NOW.minusSeconds(20), "products", Level.GREEN, Map.of());
        store.save(report);
        Files.writeString(directory.resolve("notes.txt"), "not a report");
        Files.writeString(directory.resolve(".2026-10-23T08-15-02.311Z_x.json.1234.tmp"), "{");
        Files.writeString(directory.resolve("2999-01-01T00-00-00.000Z_broken.json"), "{\"id\": ");

        assertThat(store.findRecent(null, 10)).containsExactly(report);
        assertThat(store.findHistory("ditto.demo_backup", "ditto.products", true, 10)).containsExactly(report);
        assertThatThrownBy(() -> store.findById("broken")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aReadOnlyStoreChangesNothing() {
        Path missing = directory.resolve("reports");
        var readOnly = FileReportStore.readOnly(missing, JSON);
        ComparisonReport report = report("ro1", NOW.minusSeconds(10), "products", Level.GREEN, Map.of());

        assertThat(readOnly.findRecent(null, 10)).isEmpty();
        assertThat(readOnly.findById("ro1")).isEmpty();
        assertThatThrownBy(() -> readOnly.save(report)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(missing).doesNotExist();

        new FileReportStore(missing, null, JSON).save(report);
        assertThat(readOnly.findById("ro1")).contains(report);
    }

    @Test
    void rejectsIdsThatCannotBeFileNames() {
        var store = new FileReportStore(directory, null, JSON);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.save(report("../escape", NOW, "products", Level.GREEN, Map.of())))
                .withMessageContaining("can't be part of a file name");
        assertThat(files()).isEmpty();
    }

    @Test
    void storesIntoARelativeDirectoryAsAnAbsolutePath() {
        var store = new FileReportStore(Path.of("reports"), null, JSON);

        assertThat(store.directory()).isAbsolute().endsWithRaw(Path.of("reports"));
        assertThat(store.directory().getParent()).isEqualTo(Path.of("").toAbsolutePath());
        assertThatIllegalArgumentException().isThrownBy(() -> new FileReportStore(directory, Duration.ZERO, JSON));
    }

    private List<Path> files() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The fixture report with another id, finish time, candidate, verdict and labels. */
    private static ComparisonReport report(String id, Instant finishedAt, String candidate, Level verdict,
                                           Map<String, String> labels) {
        ObjectNode tree = (ObjectNode) MAPPER.readTree(fixture);
        tree.put("id", id);
        tree.put("verdict", verdict.name());
        ObjectNode run = (ObjectNode) tree.get("run");
        run.put("finishedAt", finishedAt.toString());
        ((ObjectNode) run.get("settings").get("candidate")).put("collection", candidate);
        ObjectNode labelNode = tree.putObject("labels");
        labels.forEach(labelNode::put);
        return JSON.read(MAPPER.writeValueAsString(tree));
    }
}
