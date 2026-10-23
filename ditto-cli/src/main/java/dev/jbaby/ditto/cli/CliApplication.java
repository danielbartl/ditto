package dev.jbaby.ditto.cli;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code java -jar ditto-cli.jar [compare|report|copy-reports|generate] --option=value ...}
 * <p>
 * Works on MongoDB by default; {@code --opensearch=<url>} switches to OpenSearch and leaves MongoDB unconnected.
 * <p>
 * Exit codes: 0 GREEN, 1 YELLOW, 2 RED, 3 error (including usage errors); for {@code report} those of the stored
 * report. {@code copy-reports} and {@code generate} exit with 0 or 3.
 */
@SpringBootApplication
public class CliApplication {

    /** Kept off with {@code --opensearch}, so no MongoClient is created. */
    static final String MONGO_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
            "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration");

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs the CLI and returns the exit code; output goes to {@code out}, messages to {@code err}. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (List.of(args).contains("--help") || List.of(args).contains("-h")) {
            out.print(Usage.TEXT);
            return ExitCodes.OK;
        }
        SpringApplication application = new SpringApplication(CliApplication.class);
        application.addInitializers(context -> context.getBeanFactory()
                .registerSingleton("cliConsole", new CliConsole(out, err)));
        application.setDefaultProperties(openSearchProperties(args));
        try (ConfigurableApplicationContext context = application.run(flagsAsTrue(args))) {
            return context.getBean(CliRunner.class).exitCode();
        } catch (RuntimeException e) {
            err.println("Error: " + e.getMessage());
            return ExitCodes.ERROR;
        }
    }

    /**
     * With {@code --opensearch=<url,...>}: the client ditto creates for those nodes ({@code --opensearch-user}, the
     * password from {@code DITTO_OPENSEARCH_PASSWORD}), and no MongoDB connection.
     */
    static Map<String, Object> openSearchProperties(String[] args) {
        Map<String, Object> properties = new LinkedHashMap<>();
        String uris = option(args, "opensearch");
        if (uris != null) {
            properties.put("ditto.opensearch.uris", uris);
            properties.put("spring.autoconfigure.exclude", MONGO_AUTO_CONFIGURATIONS);
            String user = option(args, "opensearch-user");
            if (user != null) {
                properties.put("ditto.opensearch.username", user);
            }
        }
        return properties;
    }

    private static @Nullable String option(String[] args, String name) {
        String prefix = "--" + name + "=";
        return Stream.of(args).filter(arg -> arg.startsWith(prefix)).map(arg -> arg.substring(prefix.length()))
                .filter(value -> !value.isBlank()).reduce((first, last) -> last).orElse(null);
    }

    /**
     * Passes the bare flags that {@code application.yml} maps onto library properties ({@code --persist}) as
     * {@code --flag=true}: a bare flag is an empty property, which is not a valid boolean.
     */
    static String[] flagsAsTrue(String[] args) {
        return Stream.of(args).map(arg -> arg.equals("--persist") ? "--persist=true" : arg).toArray(String[]::new);
    }
}
