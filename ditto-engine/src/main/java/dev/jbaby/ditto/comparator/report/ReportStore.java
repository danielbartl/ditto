package dev.jbaby.ditto.comparator.report;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.ComparisonReport;

/**
 * Where reports are stored, e.g. to derive thresholds from historical runs or to look up the report of a batch job.
 * Implemented for MongoDB ({@code ReportRepository}) and OpenSearch.
 */
public interface ReportStore {

    /** @throws UnsupportedOperationException if this store is read-only */
    void save(ComparisonReport report);

    Optional<ComparisonReport> findById(String id);

    /**
     * Most recent reports first.
     *
     * @param candidate only reports for this candidate ({@code db.collection}), all if {@code null}
     * @param limit     maximum number of reports
     */
    List<ComparisonReport> findRecent(@Nullable String candidate, int limit);

    /**
     * Reports carrying all the given labels, e.g. {@code batchJobId=4711}, most recent first.
     *
     * @param labels labels a report must have, with these values; all reports if empty
     * @param limit  maximum number of reports
     */
    List<ComparisonReport> findByLabels(Map<String, String> labels, int limit);

    /**
     * Previous comparisons of the same pair, most recent first, for deriving thresholds. Unreadable reports are
     * skipped.
     *
     * @param baseline   {@code db.collection} of the baseline
     * @param candidate  {@code db.collection} of the candidate
     * @param includeRed whether RED reports are included
     * @param limit      maximum number of reports
     */
    List<ComparisonReport> findHistory(String baseline, String candidate, boolean includeRed, int limit);
}
