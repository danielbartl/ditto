package dev.jbaby.ditto.comparator.scan;

import java.util.List;

/**
 * What the checks before reading found out.
 *
 * @param baselineCount  documents in the baseline (from collection metadata)
 * @param candidateCount documents in the candidate (from collection metadata)
 * @param warnings       conditions to mention in the report
 */
public record PreflightResult(long baselineCount, long candidateCount, List<String> warnings) {

    public PreflightResult {
        warnings = List.copyOf(warnings);
    }
}
