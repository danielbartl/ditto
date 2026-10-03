package dev.jbaby.ditto.comparator.path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Ignored, order-sensitive and wildcard path patterns compiled into a trie, so a document walk can track which rules
 * apply with a {@link State} per node instead of matching strings.
 * <p>
 * Semantics:
 * <ul>
 * <li>ignored: the matching field (or array element) is dropped before comparing and profiling</li>
 * <li>order-sensitive: the matching array keeps its element order; all other arrays are compared as multisets</li>
 * <li>wildcard: must end with {@code *}; the field names below the matching object are rendered as {@code *} in
 * reported paths (e.g. {@code attributes.color} becomes {@code attributes.*}). Content comparison still uses the real
 * names.</li>
 * </ul>
 */
public final class PathRules {

    public static final PathRules NONE = compile(List.of(), List.of(), List.of());

    private final Node root;

    private PathRules(Node root) {
        this.root = root;
    }

    public static PathRules compile(Collection<String> ignored, Collection<String> orderSensitive,
                                    Collection<String> wildcard) {
        Node root = new Node();
        ignored.forEach(source -> root.insert(PathPattern.parse(source).segments()).ignored = true);
        orderSensitive.forEach(source -> root.insert(PathPattern.parse(source).segments()).orderSensitive = true);
        for (String source : wildcard) {
            PathPattern pattern = PathPattern.parse(source);
            if (!(pattern.last() instanceof PathPattern.AnyField)) {
                throw new IllegalArgumentException("wildcard path '" + source + "' must end with '.*'");
            }
            root.insert(pattern.parentSegments()).collapsesChildren = true;
        }
        return new PathRules(root);
    }

    /** State at the document root. */
    public State root() {
        return root.isLeaf() ? State.EMPTY : new State(new Node[] {root});
    }

    /**
     * The rules that apply at one node of a document walk. Immutable; {@link #field} and {@link #element} return the
     * state of a child node.
     */
    public static final class State {

        static final State EMPTY = new State(new Node[0]);

        private final Node[] nodes;
        private final boolean ignored;
        private final boolean orderSensitive;
        private final boolean collapsesChildren;

        private State(Node[] nodes) {
            this.nodes = nodes;
            boolean ign = false;
            boolean ord = false;
            boolean col = false;
            for (Node node : nodes) {
                ign |= node.ignored;
                ord |= node.orderSensitive;
                col |= node.collapsesChildren;
            }
            this.ignored = ign;
            this.orderSensitive = ord;
            this.collapsesChildren = col;
        }

        public State field(String name) {
            if (nodes.length == 0) {
                return EMPTY;
            }
            List<Node> next = new ArrayList<>(2);
            for (Node node : nodes) {
                addIfPresent(next, node.fields.get(name));
                addIfPresent(next, node.anyField);
            }
            return of(next);
        }

        public State element() {
            if (nodes.length == 0) {
                return EMPTY;
            }
            List<Node> next = new ArrayList<>(1);
            for (Node node : nodes) {
                addIfPresent(next, node.element);
            }
            return of(next);
        }

        /** Whether the node is dropped. */
        public boolean ignored() {
            return ignored;
        }

        /** Whether the node, an array, keeps its element order. */
        public boolean orderSensitive() {
            return orderSensitive;
        }

        /** Whether the node is a map whose field names are reported as {@code *}. */
        public boolean collapsesChildren() {
            return collapsesChildren;
        }

        private static void addIfPresent(List<Node> next, @Nullable Node node) {
            if (node != null) {
                next.add(node);
            }
        }

        private static State of(List<Node> nodes) {
            return nodes.isEmpty() ? EMPTY : new State(nodes.toArray(Node[]::new));
        }
    }

    private static final class Node {

        final Map<String, Node> fields = new HashMap<>();
        @Nullable Node anyField;
        @Nullable Node element;
        boolean ignored;
        boolean orderSensitive;
        boolean collapsesChildren;

        Node insert(List<PathPattern.Segment> segments) {
            Node node = this;
            for (PathPattern.Segment segment : segments) {
                node = switch (segment) {
                    case PathPattern.Field(String name) -> node.fields.computeIfAbsent(name, key -> new Node());
                    case PathPattern.AnyField() -> node.anyField != null ? node.anyField : (node.anyField = new Node());
                    case PathPattern.Element() -> node.element != null ? node.element : (node.element = new Node());
                };
            }
            return node;
        }

        boolean isLeaf() {
            return fields.isEmpty() && anyField == null && element == null && !ignored && !orderSensitive
                    && !collapsesChildren;
        }
    }
}
