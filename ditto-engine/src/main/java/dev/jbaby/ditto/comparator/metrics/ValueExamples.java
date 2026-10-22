package dev.jbaby.ditto.comparator.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.ValueChange;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CArray;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CBinary;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CBoolean;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CDate;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CDocument;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CNonFinite;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CNull;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CNumber;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CObjectId;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.COther;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CString;
import dev.jbaby.ditto.comparator.flatten.Flattener;
import dev.jbaby.ditto.comparator.path.PathMatcher;
import dev.jbaby.ditto.comparator.path.PathRules;
import dev.jbaby.ditto.comparator.path.Paths;

/**
 * Extracts before/after values of changed paths from two canonical documents, for {@link ValueChange} examples.
 * Per path, values present on both sides cancel out; what remains is listed per side, rendered as short strings.
 * Values at redacted paths are replaced by {@code ***}. Thread-safe.
 */
public final class ValueExamples {

    static final String REDACTED = "***";
    static final int MAX_VALUES = 5;
    static final int MAX_LENGTH = 120;

    private final PathRules rules;
    private final Flattener flattener;
    private final PathMatcher redacted;

    public ValueExamples(PathRules rules, Flattener flattener, PathMatcher redacted) {
        this.rules = rules;
        this.flattener = flattener;
        this.redacted = redacted;
    }

    /**
     * Value changes of {@code paths}; paths without any differing leaf value (e.g. values moved between array
     * elements) are left out.
     */
    public Map<String, ValueChange> of(BsonValue key, CDocument baseline, CDocument candidate, Set<String> paths) {
        Map<String, List<CanonicalValue>> before = leaves(baseline, paths);
        Map<String, List<CanonicalValue>> after = leaves(candidate, paths);
        Map<String, ValueChange> changes = new LinkedHashMap<>();
        KeyRef keyRef = null;
        for (String path : paths) {
            List<CanonicalValue> onlyBefore = new ArrayList<>();
            List<CanonicalValue> onlyAfter = new ArrayList<>();
            difference(before.getOrDefault(path, List.of()), after.getOrDefault(path, List.of()), onlyBefore,
                    onlyAfter);
            if (onlyBefore.isEmpty() && onlyAfter.isEmpty()) {
                continue;
            }
            boolean redact = redacted.matches(path);
            keyRef = keyRef != null ? keyRef : KeyRef.of(key);
            changes.put(path, new ValueChange(keyRef, render(onlyBefore, redact), render(onlyAfter, redact)));
        }
        return changes;
    }

    private Map<String, List<CanonicalValue>> leaves(CDocument document, Set<String> paths) {
        Map<String, List<CanonicalValue>> leaves = new HashMap<>();
        flattener.leaves(document, rules.root(), Paths.ROOT, (path, value) -> {
            if (paths.contains(path)) {
                leaves.computeIfAbsent(path, key -> new ArrayList<>()).add(value);
            }
        });
        return leaves;
    }

    /** Multiset difference in both directions, keeping the order of appearance. */
    private static void difference(List<CanonicalValue> before, List<CanonicalValue> after,
                                   List<CanonicalValue> onlyBefore, List<CanonicalValue> onlyAfter) {
        Map<CanonicalValue, Integer> remaining = new HashMap<>();
        after.forEach(value -> remaining.merge(value, 1, Integer::sum));
        for (CanonicalValue value : before) {
            Integer count = remaining.get(value);
            if (count == null) {
                onlyBefore.add(value);
            } else if (count == 1) {
                remaining.remove(value);
            } else {
                remaining.put(value, count - 1);
            }
        }
        for (CanonicalValue value : after) {
            Integer count = remaining.get(value);
            if (count != null) {
                onlyAfter.add(value);
                if (count == 1) {
                    remaining.remove(value);
                } else {
                    remaining.put(value, count - 1);
                }
            }
        }
    }

    private static List<String> render(List<CanonicalValue> values, boolean redact) {
        List<String> rendered = new ArrayList<>();
        for (CanonicalValue value : values.subList(0, Math.min(values.size(), MAX_VALUES))) {
            rendered.add(redact ? REDACTED : render(value));
        }
        if (values.size() > MAX_VALUES) {
            rendered.add("… " + (values.size() - MAX_VALUES) + " more");
        }
        return rendered;
    }

    /** Short, readable form of a canonical leaf value. */
    static String render(CanonicalValue value) {
        String text = switch (value) {
            case CNull cnull -> "null";
            case CBoolean(boolean bool) -> Boolean.toString(bool);
            case CNumber(BigDecimal number) -> number.toPlainString();
            case CNonFinite(CNonFinite.Kind kind) -> switch (kind) {
                case NAN -> "NaN";
                case POSITIVE_INFINITY -> "Infinity";
                case NEGATIVE_INFINITY -> "-Infinity";
            };
            case CString(String string) -> "\"" + string.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            case CDate(long millis) -> Instant.ofEpochMilli(millis).toString();
            case CObjectId(var objectId) -> "ObjectId(" + objectId.toHexString() + ")";
            case CBinary binary -> "Binary(" + binary.subtype() + ", "
                    + Base64.getEncoder().encodeToString(binary.data()) + ")";
            case COther(String type, String representation) -> type + "(" + representation + ")";
            case CDocument document -> document.fields().isEmpty() ? "{}" : "{…}";
            case CArray array -> array.elements().isEmpty() ? "[]" : "[…]";
        };
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1) + "…";
    }
}
