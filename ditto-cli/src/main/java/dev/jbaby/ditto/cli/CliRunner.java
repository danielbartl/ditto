package dev.jbaby.ditto.cli;

import java.io.UncheckedIOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.mongodb.MongoException;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.opensearch.OpenSearchAccessException;

/**
 * Dispatches to the command given as first non-option argument ({@code compare} if none) and records its exit code.
 * Errors are reported as one line on stderr instead of a stack trace.
 */
@Component
public class CliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    private final CompareCommand compare;
    private final GenerateCommand generate;
    private final ReportCommand report;
    private final CopyReportsCommand copyReports;
    private final CliConsole console;
    private int exitCode = ExitCodes.ERROR;

    public CliRunner(CompareCommand compare, GenerateCommand generate, ReportCommand report,
                     CopyReportsCommand copyReports, CliConsole console) {
        this.compare = compare;
        this.generate = generate;
        this.report = report;
        this.copyReports = copyReports;
        this.console = console;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<String> commands = arguments.getNonOptionArgs();
        String command = commands.isEmpty() ? "compare" : commands.getFirst();
        try {
            exitCode = switch (command) {
                case "compare" -> compare.run();
                case "generate" -> generate.run();
                case "report" -> report.run();
                case "copy-reports" -> copyReports.run();
                default -> throw new CliUsageException("Unknown command '" + command + "'");
            };
        } catch (CliUsageException e) {
            console.err().println(e.getMessage());
            console.err().println("Run with --help for usage.");
            exitCode = ExitCodes.ERROR;
        } catch (ComparisonException | DataAccessException | MongoException | OpenSearchAccessException
                 | IllegalArgumentException | UncheckedIOException e) {
            console.err().println("Error: " + e.getMessage());
            exitCode = ExitCodes.ERROR;
        } catch (RuntimeException e) {
            log.error("Unexpected error", e);
            console.err().println("Error: " + e);
            exitCode = ExitCodes.ERROR;
        }
    }

    public int exitCode() {
        return exitCode;
    }
}
