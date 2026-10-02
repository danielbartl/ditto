package dev.jbaby.ditto.comparator.api;

import java.util.List;

/**
 * What changed at one path of one document: the values only the baseline has there and the values only the candidate
 * has there. For a plain field that is one value on each side; an empty side means the field is missing on that side.
 * For array paths such as {@code items[].price} only the values that differ are listed.
 * <p>
 * Values are rendered as short strings ({@code 19.99}, {@code "red"}, {@code 2024-01-01T00:00:00Z}, ...); values at
 * redacted paths are shown as {@code ***}.
 *
 * @param key       the document
 * @param baseline  values only present in the baseline document at this path
 * @param candidate values only present in the candidate document at this path
 */
public record ValueChange(KeyRef key, List<String> baseline, List<String> candidate) {

    public ValueChange {
        baseline = List.copyOf(baseline);
        candidate = List.copyOf(candidate);
    }
}
