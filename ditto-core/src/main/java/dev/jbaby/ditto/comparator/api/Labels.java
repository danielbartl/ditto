package dev.jbaby.ditto.comparator.api;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Labels are caller-defined string pairs stored with a report, e.g. {@code batchJobId=4711}, so reports can be found
 * per job later. Keys are plain names (letters, digits, {@code _} and {@code -}, at most {@value #MAX_KEY_LENGTH}
 * characters), because they become MongoDB field names; values are any string up to {@value #MAX_VALUE_LENGTH}
 * characters. At most {@value #MAX_LABELS} labels per report.
 */
public final class Labels {

    public static final int MAX_LABELS = 32;
    public static final int MAX_KEY_LENGTH = 64;
    public static final int MAX_VALUE_LENGTH = 512;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_KEY_LENGTH + "}");

    private Labels() {
    }

    /**
     * A validated, immutable copy sorted by key, so reports are deterministic.
     *
     * @throws IllegalArgumentException if a key or value is invalid or there are too many labels
     */
    public static SortedMap<String, String> of(Map<String, String> labels) {
        if (labels.size() > MAX_LABELS) {
            throw new IllegalArgumentException("At most " + MAX_LABELS + " labels allowed, got " + labels.size());
        }
        SortedMap<String, String> sorted = new TreeMap<>();
        labels.forEach((key, value) -> {
            if (key == null || !KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid label key '" + key + "': use letters, digits, _ and -,"
                        + " at most " + MAX_KEY_LENGTH + " characters");
            }
            if (value == null || value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("Invalid value of label '" + key + "': must be set and at most "
                        + MAX_VALUE_LENGTH + " characters");
            }
            sorted.put(key, value);
        });
        return Collections.unmodifiableSortedMap(sorted);
    }

    /**
     * Parses {@code key=value} or {@code key:value} pairs separated by commas, e.g. {@code batchJobId=4711,env=test}.
     * The value is everything after the first {@code =} or {@code :}, so it may contain either character, but no
     * comma.
     *
     * @throws IllegalArgumentException if a pair has no separator or is invalid
     */
    public static SortedMap<String, String> parse(String pairs) {
        Map<String, String> labels = new TreeMap<>();
        for (String pair : pairs.split(",")) {
            String trimmed = pair.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = firstSeparator(trimmed);
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid label '" + trimmed + "': expected key=value");
            }
            labels.put(trimmed.substring(0, separator).strip(), trimmed.substring(separator + 1).strip());
        }
        return of(labels);
    }

    private static int firstSeparator(String pair) {
        int equals = pair.indexOf('=');
        int colon = pair.indexOf(':');
        return equals < 0 ? colon : colon < 0 ? equals : Math.min(equals, colon);
    }
}
