package dev.jbaby.ditto.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.stereotype.Component;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Labels;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.report.ReportHtml;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.report.ReportRepository;

/**
 * {@code report}: reads a stored report ({@code ditto.persistence.*}), prints it as JSON and maps its verdict to the
 * exit code, like {@code compare}. It selects the report by id, by labels or by candidate collection, or takes the
 * most recent one. Reading never changes the report collection, not even its indexes.
 */
@Component
public class ReportCommand {

    private final MongoDatabaseFactory databaseFactory;
    private final ComparatorProperties properties;
    private final ReportJson json;
    private final ReportHtml html;
    private final CliOptions options;
    private final CliConsole console;

    public ReportCommand(MongoDatabaseFactory databaseFactory, ComparatorProperties properties, ReportJson json,
                         ReportHtml html, CliOptions options, CliConsole console) {
        this.databaseFactory = databaseFactory;
        this.properties = properties;
        this.json = json;
        this.html = html;
        this.options = options;
        this.console = console;
    }

    public int run() {
        var persistence = properties.getPersistence();
        ReportRepository repository = ReportRepository.readOnly(databaseFactory, persistence.getDatabase(),
                persistence.getCollection(), json);
        Optional<ComparisonReport> found = find(repository);
        if (found.isEmpty()) {
            console.err().println("No stored report " + selection() + " in "
                    + new CollectionRef(persistence.getDatabase(), persistence.getCollection())
                    .withDefaultDatabase(databaseFactory.getMongoDatabase().getName()));
            return ExitCodes.ERROR;
        }
        ComparisonReport report = found.get();
        String text = json.writePretty(report);
        console.out().println(text);
        List<String> files = new ArrayList<>();
        String out = options.string("out");
        if (out != null) {
            CompareCommand.write(Path.of(out), text);
            files.add(out);
        }
        String page = options.string("html");
        if (page != null) {
            CompareCommand.write(Path.of(page), html.write(report));
            files.add(page);
        }
        console.err().println("Report " + report.id() + " of " + report.run().finishedAt() + ": "
                + CompareCommand.summary(report)
                + (files.isEmpty() ? "" : "; report written to " + String.join(", ", files)));
        return ExitCodes.of(report.verdict());
    }

    private Optional<ComparisonReport> find(ReportRepository repository) {
        String id = options.string("id");
        String labels = options.string("label");
        String candidate = candidate();
        long criteria = Stream.of(id, labels, candidate).filter(Objects::nonNull).count();
        if (criteria > 1) {
            throw new CliUsageException("Select the report by one of --id, --label or --collection/--candidate");
        }
        if (id != null) {
            return repository.findById(id);
        }
        if (labels != null) {
            return repository.findByLabels(labels(labels), 1).stream().findFirst();
        }
        return repository.findRecent(candidate, 1).stream().findFirst();
    }

    /** {@code db.collection} of the candidate, as stored with the report; {@code null} if not given. */
    private @Nullable String candidate() {
        String collection = options.string("collection");
        String candidate = options.string("candidate");
        if (collection != null && candidate != null) {
            throw new CliUsageException("Use either --collection or --candidate, not both");
        }
        String name = collection != null ? collection : candidate;
        if (name == null) {
            return null;
        }
        return new CollectionRef(options.string("candidate-db"), name)
                .withDefaultDatabase(databaseFactory.getMongoDatabase().getName()).toString();
    }

    private static Map<String, String> labels(String labels) {
        try {
            return Labels.parse(labels);
        } catch (IllegalArgumentException e) {
            throw new CliUsageException("Option --label: " + e.getMessage());
        }
    }

    private String selection() {
        if (options.string("id") != null) {
            return "with id " + options.string("id");
        }
        if (options.string("label") != null) {
            return "with labels " + options.string("label");
        }
        String candidate = candidate();
        return candidate != null ? "for candidate " + candidate : "at all";
    }
}
