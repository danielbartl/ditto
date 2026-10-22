package dev.jbaby.ditto.comparator.flatten;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.canonical.CanonicalValue;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CArray;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CDocument;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.Field;
import dev.jbaby.ditto.comparator.path.PathRules;
import dev.jbaby.ditto.comparator.path.Paths;

/**
 * Flattens documents into paths: nested fields joined with {@code .}, array elements as {@code []}, fields of
 * wildcard maps as {@code *}. Ignored paths are skipped. Thread-safe; one instance per comparison run.
 */
public final class Flattener {

    private final PathRules rules;
    private final boolean nullEqualsMissing;

    public Flattener(PathRules rules, boolean nullEqualsMissing) {
        this.rules = rules;
        this.nullEqualsMissing = nullEqualsMissing;
    }

    /**
     * Every node of a raw document as {@code path -> BSON type}, including intermediate documents and arrays, each
     * entry once even if it occurs in several array elements.
     */
    public Set<PathType> pathTypes(BsonDocument document) {
        Set<PathType> result = new HashSet<>();
        collectDocument(document, rules.root(), Paths.ROOT, result);
        return result;
    }

    private void collectDocument(BsonDocument document, PathRules.State state, String path, Set<PathType> result) {
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            BsonValue value = entry.getValue();
            if (nullEqualsMissing && value.isNull()) {
                continue;
            }
            PathRules.State child = state.field(entry.getKey());
            if (!child.ignored()) {
                collectValue(value, child, Paths.field(path, entry.getKey(), state), result);
            }
        }
    }

    private void collectArray(BsonArray array, PathRules.State state, String path, Set<PathType> result) {
        PathRules.State elementState = state.element();
        if (elementState.ignored()) {
            return;
        }
        String elementPath = Paths.element(path);
        for (BsonValue element : array) {
            collectValue(element, elementState, elementPath, result);
        }
    }

    private void collectValue(BsonValue value, PathRules.State state, String path, Set<PathType> result) {
        result.add(new PathType(path, value.getBsonType()));
        if (value.isDocument()) {
            collectDocument(value.asDocument(), state, path, result);
        } else if (value.isArray()) {
            collectArray(value.asArray(), state, path, result);
        }
    }

    /**
     * Emits the leaves of a canonical value: scalars, empty documents and empty arrays, with their paths.
     *
     * @param state rule state at {@code path}
     * @param path  path of {@code value}; {@link Paths#ROOT} for a top-level document
     */
    public void leaves(CanonicalValue value, PathRules.State state, String path,
                       BiConsumer<String, CanonicalValue> sink) {
        switch (value) {
            case CDocument(var fields) when !fields.isEmpty() -> {
                for (Field field : fields) {
                    leaves(field.value(), state.field(field.name()), Paths.field(path, field.name(), state), sink);
                }
            }
            case CArray(var elements) when !elements.isEmpty() -> {
                PathRules.State elementState = state.element();
                String elementPath = Paths.element(path);
                for (CanonicalValue element : elements) {
                    leaves(element, elementState, elementPath, sink);
                }
            }
            default -> {
                if (!path.isEmpty()) {
                    sink.accept(path, value);
                }
            }
        }
    }
}
