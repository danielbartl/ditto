package dev.jbaby.ditto.cli.generator;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.cli.CliUsageException;

/**
 * One kind of change applied to the candidate copy, e.g. {@code modify:price:0.05}.
 *
 * @param kind     what to change
 * @param path     dotted field path for field-level kinds, {@code null} for document-level kinds
 * @param fraction fraction of documents affected (for {@code add-docs}: relative to the document count)
 */
public record ChangeSpec(Kind kind, @Nullable String path, double fraction) {

    public enum Kind {
        MODIFY(true),
        DROP_FIELD(true),
        ADD_FIELD(true),
        SET_NULL(true),
        INT_TO_DOUBLE(true),
        TO_STRING(true),
        SHUFFLE_ARRAYS(false),
        DELETE_DOCS(false),
        ADD_DOCS(false);

        private final boolean needsPath;

        Kind(boolean needsPath) {
            this.needsPath = needsPath;
        }

        public boolean needsPath() {
            return needsPath;
        }

        public String spec() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    public ChangeSpec {
        if (!(fraction >= 0 && fraction <= (kind == Kind.ADD_DOCS ? 100 : 1))) {
            throw new CliUsageException("Change " + kind.spec() + ": fraction must be between 0 and 1, was "
                    + fraction);
        }
        if (kind.needsPath() == (path == null)) {
            throw new CliUsageException("Change " + kind.spec() + (kind.needsPath() ? " needs" : " takes no")
                    + " field path");
        }
    }

    /** Parses {@code kind[:path]:fraction}. */
    public static ChangeSpec parse(String spec) {
        String[] parts = spec.strip().split(":");
        Kind kind = Arrays.stream(Kind.values()).filter(k -> k.spec().equals(parts[0].toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new CliUsageException("Unknown change '" + parts[0] + "', expected one of "
                        + Arrays.stream(Kind.values()).map(Kind::spec).toList()));
        int expected = kind.needsPath() ? 3 : 2;
        if (parts.length != expected) {
            throw new CliUsageException("Change '" + spec + "' must look like " + kind.spec()
                    + (kind.needsPath() ? ":<path>" : "") + ":<fraction>");
        }
        try {
            return new ChangeSpec(kind, kind.needsPath() ? parts[1] : null, Double.parseDouble(parts[expected - 1]));
        } catch (NumberFormatException e) {
            throw new CliUsageException("Change '" + spec + "': fraction must be a number");
        }
    }

    /** Parses a comma-separated list; empty for a blank string. */
    public static List<ChangeSpec> parseAll(String specs) {
        return Arrays.stream(specs.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(ChangeSpec::parse)
                .toList();
    }

    @Override
    public String toString() {
        return kind.spec() + (path != null ? ":" + path : "") + ":" + fraction;
    }
}
