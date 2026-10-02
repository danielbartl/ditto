package dev.jbaby.ditto.cli;

import dev.jbaby.ditto.comparator.api.Level;

/**
 * Process exit codes of the CLI.
 */
public final class ExitCodes {

    public static final int OK = 0;
    public static final int GREEN = 0;
    public static final int YELLOW = 1;
    public static final int RED = 2;
    public static final int ERROR = 3;

    private ExitCodes() {
    }

    public static int of(Level level) {
        return switch (level) {
            case GREEN -> GREEN;
            case YELLOW -> YELLOW;
            case RED -> RED;
        };
    }
}
