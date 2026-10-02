package dev.jbaby.ditto.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

        // touch-sync changes meta.syncedAt everywhere: RED unless ignored
        assertThat(run("--baseline=g_base", "--candidate=g_same").code()).isEqualTo(ExitCodes.RED);
        Result green = run("compare", "--baseline=g_base", "--candidate=g_same", "--ignore=meta.syncedAt",
                "--out=" + tmp.resolve("report.json"));
        assertThat(green.code()).isEqualTo(ExitCodes.GREEN);
        ComparisonReport report = new ReportJson().read(green.out());
        assertThat(report.verdict()).isEqualTo(Level.GREEN);
        assertThat(report.keys().matched()).isEqualTo(3000);
        assertThat(Files.readString(tmp.resolve("report.json")).strip()).isEqualTo(green.out().strip());
        assertThat(green.err()).contains("Verdict GREEN");

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
    void errorsExitWithThree() {
        assertThat(run("--baseline=only").code()).isEqualTo(ExitCodes.ERROR);
        assertThat(run("--baseline=only").err()).contains("Missing required option --candidate");
        assertThat(run("frobnicate").err()).contains("Unknown command 'frobnicate'");
        assertThat(run("--baseline=nope_a", "--candidate=nope_b").err()).contains("does not exist");
        assertThat(run("--baseline=a", "--candidate=b", "--mode=fast").err()).contains("must be full or sample");

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
        assertThat(help.out()).contains("Usage: java -jar comparator-cli.jar");
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
