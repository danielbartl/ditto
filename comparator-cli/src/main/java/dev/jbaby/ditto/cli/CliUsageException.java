package dev.jbaby.ditto.cli;

/**
 * Invalid or missing command-line options.
 */
public class CliUsageException extends RuntimeException {

    public CliUsageException(String message) {
        super(message);
    }
}
