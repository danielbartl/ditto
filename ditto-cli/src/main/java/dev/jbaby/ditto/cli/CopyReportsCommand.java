package dev.jbaby.ditto.cli;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import dev.jbaby.ditto.comparator.report.FileReportStore;
import dev.jbaby.ditto.comparator.report.ReportJson;

/**
 * {@code copy-reports}: copies the reports stored in the database ({@code ditto.persistence.database} /
 * {@code .collection}, with {@code --opensearch} the index) into the report directory ({@code --report-dir}), e.g. to
 * keep the history when switching to report files. The database is only read. Copying again replaces each file with
 * the same report, so it can be repeated.
 */
@Component
public class CopyReportsCommand {

    private final Target target;
    private final ReportJson json;
    private final CliConsole console;

    public CopyReportsCommand(Target target, ReportJson json, CliConsole console) {
        this.target = target;
        this.json = json;
        this.console = console;
    }

    public int run() {
        // no retention: the application applies its own on its next save
        var files = new FileReportStore(target.reportDirectory(), null, json);
        AtomicLong copied = new AtomicLong();
        target.databaseReports().forEach(report -> {
            files.save(report);
            copied.incrementAndGet();
        });
        console.err().println("Copied " + copied.get() + (copied.get() == 1 ? " report" : " reports") + " from "
                + target.databaseReportLocation() + " to " + files.directory());
        return ExitCodes.OK;
    }
}
