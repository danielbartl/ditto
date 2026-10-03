package dev.jbaby.ditto.comparator.path;

import java.util.Collection;
import java.util.List;

/**
 * Matches reported paths (as they appear in the report, e.g. {@code items[].price} or {@code attributes.*}) against
 * path patterns. A path matches if the pattern matches the path itself or one of its ancestors, so {@code customer}
 * covers {@code customer.email} and {@code items[]} covers {@code items[].price}.
 */
public final class PathMatcher {

    public static final PathMatcher NONE = new PathMatcher(List.of());

    private final List<PathPattern> patterns;

    private PathMatcher(List<PathPattern> patterns) {
        this.patterns = patterns;
    }

    public static PathMatcher of(Collection<String> patterns) {
        return patterns.isEmpty() ? NONE : new PathMatcher(patterns.stream().map(PathPattern::parse).toList());
    }

    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    public boolean matches(String path) {
        if (patterns.isEmpty()) {
            return false;
        }
        List<PathPattern.Segment> segments;
        try {
            segments = PathPattern.parse(path).segments();
        } catch (IllegalArgumentException unaddressable) {
            // field names containing '.', '[' or ']' cannot be expressed as patterns
            return false;
        }
        for (PathPattern pattern : patterns) {
            if (matchesPrefix(pattern.segments(), segments)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code pattern} matches the first {@code pattern.size()} segments of {@code path}. */
    private static boolean matchesPrefix(List<PathPattern.Segment> pattern, List<PathPattern.Segment> path) {
        if (pattern.size() > path.size()) {
            return false;
        }
        for (int i = 0; i < pattern.size(); i++) {
            if (!matches(pattern.get(i), path.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(PathPattern.Segment pattern, PathPattern.Segment segment) {
        return switch (pattern) {
            case PathPattern.AnyField() -> !(segment instanceof PathPattern.Element);
            case PathPattern.Element() -> segment instanceof PathPattern.Element;
            case PathPattern.Field(String name) -> segment instanceof PathPattern.Field(String other) && name.equals(other);
        };
    }
}
