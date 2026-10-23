package dev.jbaby.ditto.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Labels;
import dev.jbaby.ditto.comparator.report.ReportHtml;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.report.ReportStore;

/**
 * {@code report}: reads a stored report ({@code ditto.persistence.*}), prints it as JSON and maps its verdict to the
 * exit code, like {@code compare}. It selects the report by id, by labels or by candidate collection (or index), or
 * takes the most recent one. Reading never changes the report files, collection or index, not even its indexes.
 */
@Component
public class ReportCommand {

    private final Target target;
    private final ReportJson json;
    private final ReportHtml html;
    private final CliOptions options;
    private final CliConsole console;

    public ReportCommand(Target target, ReportJson json, ReportHtml html, CliOptions options, CliConsole console) {
        this.target = target;
        this.json = json;
        this.html = html;
        this.options = options;
        this.console = console;
    }

    public int run() {
        Optional<ComparisonReport> found = find(target.reports());
        if (found.isEmpty()) {
            console.err().println("No stored report " + selection() + " in " + target.reportLocation());
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

    private Optional<ComparisonReport> find(ReportStore repository) {
        String id = options.string("id");
        String labels = options.string("label");
        String candidate = candidate();
        long criteria = Stream.of(id, labels, candidate).filter(Objects::nonNull).count();
        if (criteria > 1) {
            throw new CliUsageException("Select the report by one of --id, --label or --" + target.noun()
                    + "/--candidate");
        }
        if (id != null) {
            return repository.findById(id);
        }
        if (labels != null) {
            return repository.findByLabels(labels(labels), 1).stream().findFirst();
        }
        return repository.findRecent(candidate, 1).stream().findFirst();
    }

    /** {@code db.collection} (or {@code cluster.index}) of the candidate, as stored with the report. */
    private @Nullable String candidate() {
        String collection = CompareCommand.name(options, target);
        String candidate = options.string("candidate");
        if (collection != null && candidate != null) {
            throw new CliUsageException("Use either --" + target.noun() + " or --candidate, not both");
        }
        String name = collection != null ? collection : candidate;
        if (name == null) {
            return null;
        }
        return target.qualify(options.string("candidate-db"), name);
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
