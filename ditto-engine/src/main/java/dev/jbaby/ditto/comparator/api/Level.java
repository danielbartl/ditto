package dev.jbaby.ditto.comparator.api;

import java.util.Collection;

/**
 * Traffic-light level of a single rule or of the overall verdict. Declared from best to worst.
 */
public enum Level {
    GREEN,
    YELLOW,
    RED;

    public Level worst(Level other) {
        return compareTo(other) >= 0 ? this : other;
    }

    /** Worst level of the given ones, {@link #GREEN} if there are none. */
    public static Level worstOf(Collection<Level> levels) {
        return levels.stream().reduce(GREEN, Level::worst);
    }
}
