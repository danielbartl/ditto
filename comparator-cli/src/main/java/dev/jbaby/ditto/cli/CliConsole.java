package dev.jbaby.ditto.cli;

import java.io.PrintStream;

/**
 * Where the CLI writes: {@code out} for the report (machine-readable), {@code err} for human-readable messages.
 */
public record CliConsole(PrintStream out, PrintStream err) {
}
