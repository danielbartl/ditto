package dev.jbaby.ditto.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Store;
import dev.jbaby.ditto.comparator.report.ReportJson;

class OpenSearchCliIT {

    private static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(
            System.getProperty("ditto.opensearch.image", "opensearchproject/opensearch:3.9.0"))
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=60s").forPort(9200)
                    .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    @BeforeAll
    static void start() {
        OPENSEARCH.start();
    }

    @Test
    void generateCompareAndReadTheStoredReport(@TempDir Path tmp) throws Exception {
        Result generated = run("generate", "--docs=1500", "--seed=5", "--baseline=os_backup", "--candidate=os",
                "--changes=modify:name:0.08,delete-docs:0.01,add-docs:0.01");
        assertThat(generated.code()).isEqualTo(ExitCodes.OK);
        assertThat(generated.err()).contains("Generated os_backup (1500 documents)")
                .contains("Compare with: java -jar ditto-cli.jar --opensearch=");

        // meta.syncedAt holds ISO strings in OpenSearch; still recognised as a technical timestamp
        Result unconfigured = run("--index=os");
        assertThat(unconfigured.code()).isEqualTo(ExitCodes.RED);
        assertThat(unconfigured.err()).contains("--ignore=meta.syncedAt");

        // reports in the cluster, so this covers the report index
        String database = "--ditto.persistence.storage=database";
        Result compared = run("--index=os", "--ignore=meta.syncedAt", "--wildcard=attributes.*", "--persist",
                "--label=batchJobId=os-1", "--html=" + tmp.resolve("os.html"), database);
        assertThat(compared.code()).isEqualTo(ExitCodes.YELLOW);
        ComparisonReport report = new ReportJson().read(compared.out());
        assertThat(report.run().settings().store()).isEqualTo(Store.OPENSEARCH);
        assertThat(report.run().settings().candidate().collection()).isEqualTo("os");
        assertThat(report.topChangedPaths()).extracting(change -> change.path()).containsExactly("name");
        assertThat(report.keys().removed()).isPositive();
        assertThat(report.keys().added()).isPositive();
        assertThat(Files.readString(tmp.resolve("os.html"))).contains(report.id());

        assertThat(new ReportJson().read(run("report", "--label=batchJobId=os-1", database).out()))
                .isEqualTo(report);
        assertThat(new ReportJson().read(run("report", "--index=os", database).out())).isEqualTo(report);
        assertThat(run("report", "--id=nope", database).err()).contains("No stored report with id nope in index"
                + " comparison_reports");

        String files = "--report-dir=" + tmp.resolve("copied");
        assertThat(run("copy-reports", files).err()).contains("Copied 1 report from index comparison_reports");
        assertThat(new ReportJson().read(run("report", "--id=" + report.id(), files).out())).isEqualTo(report);

        // sku identifies the same documents as _id
        Result byKey = run("--index=os", "--key=sku", "--ignore=meta.syncedAt", "--wildcard=attributes.*");
        assertThat(byKey.code()).isEqualTo(compared.code());
        assertThat(new ReportJson().read(byKey.out()).keys()).isEqualTo(report.keys());
    }

    @Test
    void errorsExitWithThree() {
        Result missing = run("--baseline=nope_a", "--candidate=nope_b");
        assertThat(missing.code()).isEqualTo(ExitCodes.ERROR);
        assertThat(missing.err()).contains("Error: The baseline index nope_a does not exist");
        assertThat(run().err()).contains("Missing indices: --index=<name>");
        assertThat(run("--index=a", "--collection=b").err()).contains("either --index or --collection");

        Result unreachable = run(List.of("--index=x"), "http://localhost:1");
        assertThat(unreachable.code()).isEqualTo(ExitCodes.ERROR);
        assertThat(unreachable.err()).startsWith("Error: ").doesNotContain("\tat ");
    }

    private static Result run(String... args) {
        return run(List.of(args), "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
    }

    private static Result run(List<String> args, String openSearch) {
        List<String> all = new ArrayList<>(args);
        all.add("--opensearch=" + openSearch);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = CliApplication.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String out, String err) {
    }
}
