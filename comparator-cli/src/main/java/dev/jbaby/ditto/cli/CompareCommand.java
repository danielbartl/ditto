package dev.jbaby.ditto.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import dev.jbaby.ditto.comparator.CollectionComparator;
import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.MixedKeyPolicy;
import dev.jbaby.ditto.comparator.api.VerdictBasis;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.report.ReportJson;

/**
 * {@code compare}: runs one comparison, prints the report as JSON and maps the verdict to the exit code.
 */
@Component
public class CompareCommand {

    private final CollectionComparator comparator;
    private final ComparatorProperties properties;
    private final ReportJson json;
    private final CliOptions options;
    private final CliConsole console;

    public CompareCommand(CollectionComparator comparator, ComparatorProperties properties, ReportJson json,
                          CliOptions options, CliConsole console) {
        this.comparator = comparator;
        this.properties = properties;
        this.json = json;
        this.options = options;
        this.console = console;
    }

    public int run() {
        ComparisonRequest request = request();
        ComparisonReport report = comparator.compare(request);
        String text = json.writePretty(report);
        console.out().println(text);
        String out = options.string("out");
        if (out != null) {
            write(Path.of(out), text);
        }
        console.err().println(summary(report) + (out != null ? "; report written to " + out : ""));
        return ExitCodes.of(report.verdict());
    }

    ComparisonRequest request() {
        return builder()
                .keyField(options.string("key"))
                .ignoredPaths(options.list("ignore"))
                .orderSensitivePaths(options.list("ordered"))
                .wildcardPaths(options.list("wildcard"))
                .expectedChangePaths(options.list("expected"))
                .redactedPaths(options.list("redact"))
                .mode(mode())
                .nullEqualsMissing(options.bool("null-equals-missing"))
                .mixedKeyPolicy(options.enumValue("mixed-key-types", MixedKeyPolicy.class))
                .verdictBasis(options.enumValue("verdict-basis", VerdictBasis.class))
                .build();
    }

    /** {@code --collection=x} for x_backup vs. x, or explicit {@code --baseline} and {@code --candidate}. */
    private ComparisonRequest.Builder builder() {
        String collection = options.string("collection");
        if (collection != null) {
            if (options.string("baseline") != null || options.string("candidate") != null) {
                throw new CliUsageException("Use either --collection or --baseline/--candidate, not both");
            }
            return comparator.backupRequest(collection);
        }
        if (options.string("baseline") == null && options.string("candidate") == null) {
            throw new CliUsageException("Missing collections: --collection=<name> compares <name>"
                    + properties.getBackupSuffix() + " with <name>, or give --baseline and --candidate");
        }
        var baseline = new CollectionRef(options.string("baseline-db"), options.required("baseline"));
        var candidate = new CollectionRef(options.string("candidate-db"), options.required("candidate"));
        return ComparisonRequest.builder(baseline, candidate);
    }

    private @Nullable ComparisonMode mode() {
        String mode = options.string("mode");
        Integer size = options.integer("sample-size");
        if (mode == null) {
            return size == null ? null : ComparisonMode.sample(size);
        }
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "auto" -> {
                if (size != null) {
                    throw new CliUsageException("--sample-size cannot be combined with --mode=auto");
                }
                yield ComparisonMode.auto();
            }
            case "full" -> {
                if (size != null) {
                    throw new CliUsageException("--sample-size cannot be combined with --mode=full");
                }
                yield ComparisonMode.full();
            }
            case "sample" -> ComparisonMode.sample(size != null ? size : properties.getSample().getSize());
            default -> throw new CliUsageException("Option --mode must be auto, full or sample, was '" + mode + "'");
        };
    }

    private static String summary(ComparisonReport report) {
        return String.format(Locale.ROOT, "Verdict %s: keySimilarity %s, unchangedRate %s, %d changed paths, %d ms",
                report.verdict(), format(report.keys().keySimilarity().value()),
                format(report.content().unchangedRate().value()), report.topChangedPaths().size(),
                report.run().durationMillis());
    }

    private static String format(@Nullable Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
    }

    private static void write(Path path, String text) {
        try {
            Files.writeString(path, text + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write report to " + path, e);
        }
    }
}
