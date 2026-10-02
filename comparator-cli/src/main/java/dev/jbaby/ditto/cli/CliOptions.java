package dev.jbaby.ditto.cli;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Typed access to command-line options ({@code --name=value}), which Spring exposes as properties.
 */
@Component
public class CliOptions {

    private final Environment environment;

    public CliOptions(Environment environment) {
        this.environment = environment;
    }

    public @Nullable String string(String name) {
        String value = environment.getProperty(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    public String string(String name, String defaultValue) {
        String value = string(name);
        return value != null ? value : defaultValue;
    }

    public String required(String name) {
        String value = string(name);
        if (value == null) {
            throw new CliUsageException("Missing required option --" + name);
        }
        return value;
    }

    /** Comma-separated list, {@code null} if the option is absent. */
    public @Nullable List<String> list(String name) {
        String value = string(name);
        return value == null ? null : Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .toList();
    }

    public @Nullable Integer integer(String name) {
        String value = string(name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.replace("_", ""));
        } catch (NumberFormatException e) {
            throw new CliUsageException("Option --" + name + " must be an integer, was '" + value + "'");
        }
    }

    public int integer(String name, int defaultValue) {
        Integer value = integer(name);
        return value != null ? value : defaultValue;
    }

    public @Nullable Boolean bool(String name) {
        String value = string(name);
        if (value == null) {
            // a bare flag (--name) is an empty property
            return environment.containsProperty(name) ? Boolean.TRUE : null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> throw new CliUsageException("Option --" + name + " must be true or false, was '" + value + "'");
        };
    }

    public <E extends Enum<E>> @Nullable E enumValue(String name, Class<E> type) {
        String value = string(name);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new CliUsageException("Option --" + name + " must be one of "
                    + Arrays.toString(type.getEnumConstants()) + ", was '" + value + "'");
        }
    }
}
