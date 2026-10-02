package dev.jbaby.ditto.comparator.history;

import java.util.List;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.report.ReportRepository;

/**
 * Previous non-RED reports of the same pair of collections, most recent first.
 */
@FunctionalInterface
public interface ReportHistory {

    List<ComparisonReport> previousRuns(ComparisonSettings settings, int limit);

    static ReportHistory of(ReportRepository repository) {
        return (settings, limit) -> repository.findHistory(settings.baseline().toString(),
                settings.candidate().toString(), false, limit);
    }
}
