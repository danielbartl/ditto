package dev.jbaby.ditto.comparator.path;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed path pattern such as {@code meta.syncedAt}, {@code items[].price} or {@code attributes.*}.
 * <p>
 * Syntax: field names separated by {@code .}; {@code []} after a field (repeatable for nested arrays) stands for the
 * elements of that array; {@code *} matches exactly one arbitrary field name. Field names containing {@code .},
 * {@code [} or {@code ]} cannot be addressed.
 */
public record PathPattern(String source, List<Segment> segments) {

    public PathPattern {
        segments = List.copyOf(segments);
    }

    public sealed interface Segment {
    }

    /** A literal field name. */
    public record Field(String name) implements Segment {
    }

    /** {@code *}: any single field name. */
    public record AnyField() implements Segment {
    }

    /** {@code []}: any element of an array. */
    public record Element() implements Segment {
    }

    public static PathPattern parse(String source) {
        if (source == null || source.isBlank()) {
            throw invalid(source, "must not be blank");
        }
        List<Segment> segments = new ArrayList<>();
        for (String part : source.split("\\.", -1)) {
            String name = part;
            int elements = 0;
            while (name.endsWith("[]")) {
                name = name.substring(0, name.length() - 2);
                elements++;
            }
            if (name.isEmpty()) {
                throw invalid(source, "empty field name");
            }
            if (name.contains("[") || name.contains("]")) {
                throw invalid(source, "'[]' may only follow a field name");
            }
            if (name.contains("*") && !name.equals("*")) {
                throw invalid(source, "'*' must be a whole segment");
            }
            segments.add(name.equals("*") ? new AnyField() : new Field(name));
            for (int i = 0; i < elements; i++) {
                segments.add(new Element());
            }
        }
        return new PathPattern(source, segments);
    }

    public Segment last() {
        return segments.getLast();
    }

    /** This pattern without its last segment, e.g. {@code attributes} for {@code attributes.*}. */
    public List<Segment> parentSegments() {
        return segments.subList(0, segments.size() - 1);
    }

    private static IllegalArgumentException invalid(String source, String reason) {
        return new IllegalArgumentException("invalid path pattern '" + source + "': " + reason);
    }

    @Override
    public String toString() {
        return source;
    }
}
