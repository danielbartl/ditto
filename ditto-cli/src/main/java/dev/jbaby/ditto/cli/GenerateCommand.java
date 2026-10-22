package dev.jbaby.ditto.cli;

import org.springframework.stereotype.Component;

import dev.jbaby.ditto.cli.generator.ChangeSpec;
import dev.jbaby.ditto.cli.generator.TestDataGenerator;

/**
 * {@code generate}: writes a baseline collection (or index) and a modified candidate copy for manual experiments.
 */
@Component
public class GenerateCommand {

    private final Target target;
    private final CliOptions options;
    private final CliConsole console;

    public GenerateCommand(Target target, CliOptions options, CliConsole console) {
        this.target = target;
        this.options = options;
        this.console = console;
    }

    public int run() {
        var settings = new TestDataGenerator.Settings(
                options.string("baseline", "demo_backup"),
                options.string("candidate", "demo"),
                options.integer("docs", 10_000),
                options.integer("seed", 42),
                ChangeSpec.parseAll(options.string("changes", "")),
                !Boolean.FALSE.equals(options.bool("touch-sync")));
        var sink = target.sink();
        var stats = new TestDataGenerator(sink).generate(settings);
        console.err().println("Generated " + sink.describe(settings.baseline()) + " (" + stats.baselineDocs()
                + " documents) and " + sink.describe(settings.candidate()) + " (" + stats.candidateDocs()
                + " documents)");
        stats.applied().forEach((change, count) -> console.err().println("  " + change + ": " + count));
        console.err().println("Compare with: java -jar ditto-cli.jar " + target.connection()
                + " --baseline=" + settings.baseline() + " --candidate=" + settings.candidate()
                + (settings.touchSync() ? " --ignore=meta.syncedAt" : "") + " --wildcard=attributes.* --ordered=history");
        return ExitCodes.OK;
    }
}
