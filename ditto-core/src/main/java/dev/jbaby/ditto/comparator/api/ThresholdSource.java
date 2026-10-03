package dev.jbaby.ditto.comparator.api;

import java.util.List;

/**
 * Where the thresholds of a comparison came from.
 *
 * @param kind        {@code CONFIGURED} (properties), {@code REQUEST} (set on the request) or {@code HISTORY}
 *                    (derived from previous reports)
 * @param historyRuns previous reports used, 0 unless {@code HISTORY}
 * @param notes       how the thresholds were derived, or why history was not used
 */
public record ThresholdSource(Kind kind, int historyRuns, List<String> notes) {

    public enum Kind {
        CONFIGURED,
        REQUEST,
        HISTORY
    }

    public ThresholdSource {
        notes = List.copyOf(notes);
    }

    public static ThresholdSource configured() {
        return new ThresholdSource(Kind.CONFIGURED, 0, List.of());
    }

    public static ThresholdSource request() {
        return new ThresholdSource(Kind.REQUEST, 0, List.of());
    }
}
