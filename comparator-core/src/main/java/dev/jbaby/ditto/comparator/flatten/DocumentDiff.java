package dev.jbaby.ditto.comparator.flatten;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import dev.jbaby.ditto.comparator.canonical.CanonicalValue;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CArray;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CDocument;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.Field;
import dev.jbaby.ditto.comparator.path.PathRules;
import dev.jbaby.ditto.comparator.path.Paths;

/**
 * Finds the leaf paths in which two canonical documents differ.
 * <p>
 * Documents are compared field by field. Order-sensitive arrays are compared index by index. Other arrays are
 * compared as multisets: elements equal on both sides cancel out, and the remaining elements are flattened and their
 * leaf values compared per path. So a price change in one element of a reordered {@code items} array is reported as
 * {@code items[].price} only. If the remaining elements have equal values per path (values swapped between
 * elements), the array itself is reported as {@code items[]}. Thread-safe; one instance per comparison run.
 */
public final class DocumentDiff {

    private final PathRules rules;
    private final Flattener flattener;

    public DocumentDiff(PathRules rules, Flattener flattener) {
        this.rules = rules;
        this.flattener = flattener;
    }

    /** Reported paths (wildcards collapsed) in which the documents differ, sorted. */
    public SortedSet<String> changedPaths(CDocument baseline, CDocument candidate) {
        SortedSet<String> changed = new TreeSet<>();
        diff(baseline, candidate, rules.root(), Paths.ROOT, changed);
        return changed;
    }

    private void diff(CanonicalValue a, CanonicalValue b, PathRules.State state, String path, Set<String> out) {
        if (a.equals(b)) {
            return;
        }
        switch (a) {
            case CDocument docA when b instanceof CDocument docB -> diffDocuments(docA, docB, state, path, out);
            case CArray arrayA when b instanceof CArray arrayB && state.orderSensitive() ->
                    diffOrdered(arrayA, arrayB, state, path, out);
            case CArray arrayA when b instanceof CArray arrayB -> diffUnordered(arrayA, arrayB, state, path, out);
            default -> {
                if (a.isLeaf() && b.isLeaf()) {
                    out.add(path);
                } else {
                    addLeafPaths(a, state, path, out);
                    addLeafPaths(b, state, path, out);
                }
            }
        }
    }

    private void diffDocuments(CDocument a, CDocument b, PathRules.State state, String path, Set<String> out) {
        List<Field> fieldsA = a.fields();
        List<Field> fieldsB = b.fields();
        int i = 0;
        int j = 0;
        // fields are sorted by name: merge-walk both lists
        while (i < fieldsA.size() || j < fieldsB.size()) {
            int order = i == fieldsA.size() ? 1
                    : j == fieldsB.size() ? -1
                    : fieldsA.get(i).name().compareTo(fieldsB.get(j).name());
            Field field = order <= 0 ? fieldsA.get(i) : fieldsB.get(j);
            PathRules.State child = state.field(field.name());
            String childPath = Paths.field(path, field.name(), state);
            if (order == 0) {
                diff(field.value(), fieldsB.get(j).value(), child, childPath, out);
                i++;
                j++;
            } else {
                addLeafPaths(field.value(), child, childPath, out);
                if (order < 0) {
                    i++;
                } else {
                    j++;
                }
            }
        }
    }

    private void diffOrdered(CArray a, CArray b, PathRules.State state, String path, Set<String> out) {
        PathRules.State elementState = state.element();
        String elementPath = Paths.element(path);
        List<CanonicalValue> elementsA = a.elements();
        List<CanonicalValue> elementsB = b.elements();
        int common = Math.min(elementsA.size(), elementsB.size());
        for (int i = 0; i < common; i++) {
            diff(elementsA.get(i), elementsB.get(i), elementState, elementPath, out);
        }
        for (CanonicalValue extra : elementsA.subList(common, elementsA.size())) {
            addLeafPaths(extra, elementState, elementPath, out);
        }
        for (CanonicalValue extra : elementsB.subList(common, elementsB.size())) {
            addLeafPaths(extra, elementState, elementPath, out);
        }
    }

    private void diffUnordered(CArray a, CArray b, PathRules.State state, String path, Set<String> out) {
        PathRules.State elementState = state.element();
        String elementPath = Paths.element(path);
        Map<CanonicalValue, Integer> remainingA = new HashMap<>();
        a.elements().forEach(element -> remainingA.merge(element, 1, Integer::sum));
        List<CanonicalValue> onlyB = new ArrayList<>();
        for (CanonicalValue element : b.elements()) {
            Integer count = remainingA.get(element);
            if (count == null) {
                onlyB.add(element);
            } else if (count == 1) {
                remainingA.remove(element);
            } else {
                remainingA.put(element, count - 1);
            }
        }
        List<CanonicalValue> onlyA = new ArrayList<>();
        remainingA.forEach((element, count) -> {
            for (int i = 0; i < count; i++) {
                onlyA.add(element);
            }
        });
        int before = out.size();
        Map<String, Map<CanonicalValue, Integer>> leavesA = leafMultisets(onlyA, elementState, elementPath);
        Map<String, Map<CanonicalValue, Integer>> leavesB = leafMultisets(onlyB, elementState, elementPath);
        for (String leafPath : union(leavesA.keySet(), leavesB.keySet())) {
            if (!leavesA.getOrDefault(leafPath, Map.of()).equals(leavesB.getOrDefault(leafPath, Map.of()))) {
                out.add(leafPath);
            }
        }
        if (out.size() == before) {
            // same leaf values per path, but distributed differently among the elements
            out.add(elementPath);
        }
    }

    private Map<String, Map<CanonicalValue, Integer>> leafMultisets(List<CanonicalValue> elements,
                                                                    PathRules.State elementState, String elementPath) {
        Map<String, Map<CanonicalValue, Integer>> result = new HashMap<>();
        for (CanonicalValue element : elements) {
            flattener.leaves(element, elementState, elementPath,
                    (leafPath, value) -> result.computeIfAbsent(leafPath, _ -> new HashMap<>())
                            .merge(value, 1, Integer::sum));
        }
        return result;
    }

    private void addLeafPaths(CanonicalValue value, PathRules.State state, String path, Set<String> out) {
        flattener.leaves(value, state, path, (leafPath, _) -> out.add(leafPath));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> union = new TreeSet<>(a);
        union.addAll(b);
        return union;
    }
}
