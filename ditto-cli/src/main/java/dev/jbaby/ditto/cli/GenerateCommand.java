package dev.jbaby.ditto.cli;

import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.stereotype.Component;

import dev.jbaby.ditto.cli.generator.ChangeSpec;
import dev.jbaby.ditto.cli.generator.TestDataGenerator;

/**
 * {@code generate}: writes a baseline collection and a modified candidate copy for manual experiments.
 */
@Component
public class GenerateCommand {

    private final MongoDatabaseFactory databaseFactory;
    private final CliOptions options;
    private final CliConsole console;

    public GenerateCommand(MongoDatabaseFactory databaseFactory, CliOptions options, CliConsole console) {
        this.databaseFactory = databaseFactory;
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
        var database = databaseFactory.getMongoDatabase();
        var stats = new TestDataGenerator(database).generate(settings);
        console.err().println("Generated " + database.getName() + "." + settings.baseline() + " ("
                + stats.baselineDocs() + " documents) and " + database.getName() + "." + settings.candidate() + " ("
                + stats.candidateDocs() + " documents)");
        stats.applied().forEach((change, count) -> console.err().println("  " + change + ": " + count));
        console.err().println("Compare with: java -jar ditto-cli.jar --db=" + database.getName()
                + " --baseline=" + settings.baseline() + " --candidate=" + settings.candidate()
                + (settings.touchSync() ? " --ignore=meta.syncedAt" : "") + " --wildcard=attributes.* --ordered=history");
        return ExitCodes.OK;
    }
}
