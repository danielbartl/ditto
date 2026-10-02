package dev.jbaby.ditto.comparator.path;

/**
 * Builds reported path strings: {@code a.b} for nested fields, {@code items[]} for array elements.
 */
public final class Paths {

    public static final String ROOT = "";
    public static final String ELEMENT = "[]";
    public static final String WILDCARD = "*";

    private Paths() {
    }

    /**
     * Path of field {@code name} below {@code parent}; {@code *} instead of the name if the parent is a wildcard map.
     */
    public static String field(String parent, String name, PathRules.State parentState) {
        String segment = parentState.collapsesChildren() ? WILDCARD : name;
        return parent.isEmpty() ? segment : parent + "." + segment;
    }

    public static String element(String parent) {
        return parent + ELEMENT;
    }
}
