package dev.jbaby.ditto.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClients;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.report.ReportJson;

class CliIT {

    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");
    private static final String DB = "cli_it";

    @BeforeAll
    static void start() {
        MONGO.start();
    }

    @Test
    void generateThenCompareWithExitCodes(@TempDir Path tmp) throws Exception {
        Result generated = run("generate", "--docs=3000", "--seed=7", "--baseline=g_base", "--candidate=g_same");
        assertThat(generated.code()).isEqualTo(ExitCodes.OK);
        assertThat(generated.err()).contains("Generated cli_it.g_base (3000 documents)");

        // touch-sync changes meta.syncedAt everywhere: RED unless ignored, and the hint says so
        Result unconfigured = run("--baseline=g_base", "--candidate=g_same");
        assertThat(unconfigured.code()).isEqualTo(ExitCodes.RED);
        assertThat(unconfigured.err()).contains("Hints:").contains("--ignore=meta.syncedAt");
        Result green = run("compare", "--baseline=g_base", "--candidate=g_same", "--ignore=meta.syncedAt",
                "--out=" + tmp.resolve("report.json"), "--html=" + tmp.resolve("report.html"));
        run("generate", "--docs=500", "--baseline=stock_backup", "--candidate=stock");
        assertThat(run("--collection=stock", "--ignore=meta.syncedAt").code()).isEqualTo(ExitCodes.GREEN);
        assertThat(green.code()).isEqualTo(ExitCodes.GREEN);
        ComparisonReport report = new ReportJson().read(green.out());
        assertThat(report.verdict()).isEqualTo(Level.GREEN);
        assertThat(report.keys().matched()).isEqualTo(3000);
        assertThat(Files.readString(tmp.resolve("report.json")).strip()).isEqualTo(green.out().strip());
        assertThat(green.err()).contains("Verdict GREEN").contains("report.json, " + tmp.resolve("report.html"));
        assertThat(Files.readString(tmp.resolve("report.html"))).startsWith("<!doctype html>")
                .contains("<script type=\"application/json\" id=\"ditto-report\">{\"schemaVersion\"")
                .contains(report.id());

        run("generate", "--docs=3000", "--seed=7", "--baseline=g_base", "--candidate=g_yellow",
                "--changes=modify:name:0.08,shuffle-arrays:1");
        // shuffled arrays are content-neutral: only the 8% modified names count
        Result yellow = run("--baseline=g_base", "--candidate=g_yellow", "--ignore=meta.syncedAt",
                "--wildcard=attributes.*");
        assertThat(yellow.code()).isEqualTo(ExitCodes.YELLOW);
        assertThat(new ReportJson().read(yellow.out()).topChangedPaths())
                .extracting(change -> change.path()).containsExactly("name");
        // the name changes are expected: GREEN
        Result expected = run("--baseline=g_base", "--candidate=g_yellow", "--ignore=meta.syncedAt",
                "--wildcard=attributes.*", "--expected=name", "--redact=name");
        assertThat(expected.code()).isEqualTo(ExitCodes.GREEN);
        var name = new ReportJson().read(expected.out()).topChangedPaths().getFirst();
        assertThat(name.expected()).isTrue();
        assertThat(name.valueExamples()).isNotEmpty().allSatisfy(change -> assertThat(change.baseline()).containsExactly("***"));
        // unless the array is order-sensitive
        Result ordered = run("--baseline=g_base", "--candidate=g_yellow", "--ignore=meta.syncedAt",
                "--wildcard=attributes.*", "--ordered=history");
        assertThat(ordered.code()).isEqualTo(ExitCodes.RED);
        assertThat(new ReportJson().read(ordered.out()).topChangedPaths())
                .extracting(change -> change.path()).contains("name", "history[].event", "history[].at");

        run("generate", "--docs=3000", "--seed=7", "--baseline=g_base", "--candidate=g_red",
                "--changes=drop-field:legacyCode:1,int-to-double:qty:0.5");
        Result red = run("--baseline=g_base", "--candidate=g_red", "--ignore=meta.syncedAt", "--sample-size=500");
        assertThat(red.code()).isEqualTo(ExitCodes.RED);
        var redReport = new ReportJson().read(red.out());
        assertThat(redReport.structure().missingPaths()).extracting(p -> p.path()).containsExactly("legacyCode");
        assertThat(redReport.structure().typeShifts()).extracting(s -> s.path()).containsExactly("qty");
    }

    @Test
    void matchedOnlyComparesASubset() {
        run("generate", "--docs=2000", "--seed=3", "--baseline=sub_base", "--candidate=sub_part",
                "--changes=delete-docs:0.9");

        Result plain = run("--baseline=sub_base", "--candidate=sub_part", "--ignore=meta.syncedAt");
        assertThat(plain.code()).isEqualTo(ExitCodes.RED);
        assertThat(plain.err()).contains("--matched-only");

        Result matchedOnly = run("--baseline=sub_base", "--candidate=sub_part", "--ignore=meta.syncedAt",
                "--matched-only");
        assertThat(matchedOnly.code()).isEqualTo(ExitCodes.GREEN);
        var report = new ReportJson().read(matchedOnly.out());
        assertThat(report.run().settings().matchedOnly()).isTrue();
        assertThat(report.keys().removed()).isEqualTo(2000 - report.keys().matched());
    }

    @Test
    void labelsAreStoredWithTheReport() {
        run("generate", "--docs=200", "--baseline=label_base", "--candidate=label_cand");

        Result labelled = run("--baseline=label_base", "--candidate=label_cand", "--ignore=meta.syncedAt",
                "--label=batchJobId=4711", "--label=env:test", "--ditto.labels.team=data");
        assertThat(labelled.code()).isEqualTo(ExitCodes.GREEN);
        assertThat(new ReportJson().read(labelled.out()).labels())
                .containsExactly(Map.entry("batchJobId", "4711"), Map.entry("env", "test"),
                        Map.entry("team", "data"));

        Result invalid = run("--baseline=label_base", "--candidate=label_cand", "--label=job.id=1");
        assertThat(invalid.code()).isEqualTo(ExitCodes.ERROR);
        assertThat(invalid.err()).contains("Option --label: Invalid label key 'job.id'");
    }

    @Test
    void errorsExitWithThree() {
        assertThat(run().err()).contains("Missing collections: --collection=<name>");
        assertThat(run("--collection=x", "--baseline=y").err()).contains("either --collection or --baseline");
        assertThat(run("--baseline=only").code()).isEqualTo(ExitCodes.ERROR);
        assertThat(run("--baseline=only").err()).contains("Missing required option --candidate");
        assertThat(run("frobnicate").err()).contains("Unknown command 'frobnicate'");
        assertThat(run("--baseline=nope_a", "--candidate=nope_b").err()).contains("does not exist");
        assertThat(run("--baseline=a", "--candidate=b", "--mode=fast").err()).contains("must be auto, full or sample");

        try (var client = MongoClients.create(MONGO.getConnectionString())) {
            var db = client.getDatabase(DB);
            for (String name : List.of("mixed_a", "mixed_b")) {
                db.getCollection(name, BsonDocument.class).drop();
            }
            db.getCollection("mixed_a", BsonDocument.class).insertMany(List.of(BsonDocument.parse("{_id: 1}"),
                    BsonDocument.parse("{_id: 'x'}")));
            db.getCollection("mixed_b", BsonDocument.class).insertOne(BsonDocument.parse("{_id: 1}"));
        }
        Result mixed = run("--baseline=mixed_a", "--candidate=mixed_b");
        assertThat(mixed.code()).isEqualTo(ExitCodes.ERROR);
        assertThat(mixed.out()).isEmpty();
        assertThat(mixed.err()).contains("mixed types");
        assertThat(run("--baseline=mixed_a", "--candidate=mixed_b", "--mixed-key-types=compare").code())
                .isEqualTo(ExitCodes.RED);
    }

    @Test
    void helpPrintsUsage() {
        Result help = run("--help");
        assertThat(help.code()).isZero();
        assertThat(help.out()).contains("Usage: java -jar ditto-cli.jar");
    }

    private static Result run(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--uri=" + MONGO.getConnectionString());
        all.add("--db=" + DB);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = CliApplication.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {
    }
}
