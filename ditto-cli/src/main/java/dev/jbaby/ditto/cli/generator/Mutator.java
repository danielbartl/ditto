package dev.jbaby.ditto.cli.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bson.BsonArray;
import org.bson.BsonDateTime;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.types.Decimal128;
import org.jspecify.annotations.Nullable;

/**
 * Applies field-level {@link ChangeSpec}s to a candidate document. Paths are dotted field names into nested
 * documents (no arrays).
 */
public final class Mutator {

    private final Random random;

    public Mutator(long seed) {
        this.random = new Random(seed);
    }

    /** Whether a change with this fraction hits the next document. */
    public boolean hits(double fraction) {
        return random.nextDouble() < fraction;
    }

    /**
     * Applies {@code spec} to {@code document} in place.
     *
     * @return whether the document was changed
     */
    public boolean apply(ChangeSpec spec, BsonDocument document) {
        return switch (spec.kind()) {
            case SHUFFLE_ARRAYS -> {
                shuffleArrays(document);
                yield true;
            }
            case ADD_FIELD -> set(document, spec.path(), new BsonString("added-" + random.nextInt(1000)));
            case DROP_FIELD -> remove(document, spec.path());
            case SET_NULL -> replace(document, spec.path(), old -> BsonNull.VALUE);
            case MODIFY -> replace(document, spec.path(), Mutator::modified);
            case INT_TO_DOUBLE -> replace(document, spec.path(), value -> switch (value.getBsonType()) {
                case INT32 -> new BsonDouble(value.asInt32().getValue());
                case INT64 -> new BsonDouble(value.asInt64().getValue());
                default -> null;
            });
            case TO_STRING -> replace(document, spec.path(), Mutator::asString);
            case DELETE_DOCS, ADD_DOCS -> throw new IllegalArgumentException(spec + " is not a field change");
        };
    }

    private static @Nullable BsonValue modified(BsonValue value) {
        return switch (value.getBsonType()) {
            case INT32 -> new BsonInt32(value.asInt32().getValue() + 1);
            case INT64 -> new BsonInt64(value.asInt64().getValue() + 1);
            case DOUBLE -> new BsonDouble(value.asDouble().getValue() + 1.0);
            case DECIMAL128 -> new BsonDecimal128(new Decimal128(
                    value.asDecimal128().getValue().bigDecimalValue().add(java.math.BigDecimal.ONE)));
            case STRING -> new BsonString(value.asString().getValue() + " (changed)");
            case BOOLEAN -> org.bson.BsonBoolean.valueOf(!value.asBoolean().getValue());
            case DATE_TIME -> new BsonDateTime(value.asDateTime().getValue() + 86_400_000L);
            case NULL -> new BsonString("was null");
            case ARRAY -> {
                var array = value.asArray().clone();
                array.add(new BsonString("extra"));
                yield array;
            }
            case DOCUMENT -> value.asDocument().clone().append("changed", org.bson.BsonBoolean.TRUE);
            default -> null;
        };
    }

    private static @Nullable BsonValue asString(BsonValue value) {
        return switch (value.getBsonType()) {
            case INT32 -> new BsonString(Integer.toString(value.asInt32().getValue()));
            case INT64 -> new BsonString(Long.toString(value.asInt64().getValue()));
            case DOUBLE -> new BsonString(Double.toString(value.asDouble().getValue()));
            case DECIMAL128 -> new BsonString(value.asDecimal128().getValue().toString());
            case BOOLEAN -> new BsonString(Boolean.toString(value.asBoolean().getValue()));
            case DATE_TIME -> new BsonString(java.time.Instant.ofEpochMilli(value.asDateTime().getValue()).toString());
            case OBJECT_ID -> new BsonString(value.asObjectId().getValue().toHexString());
            default -> null;
        };
    }

    private void shuffleArrays(BsonValue value) {
        if (value.isDocument()) {
            value.asDocument().values().forEach(this::shuffleArrays);
        } else if (value.isArray()) {
            BsonArray array = value.asArray();
            List<BsonValue> elements = new ArrayList<>(array.getValues());
            Collections.shuffle(elements, random);
            array.clear();
            array.addAll(elements);
            elements.forEach(this::shuffleArrays);
        }
    }

    private static boolean replace(BsonDocument document, @Nullable String path,
                                   java.util.function.Function<BsonValue, @Nullable BsonValue> change) {
        Map.Entry<BsonDocument, String> target = parent(document, path, false);
        if (target == null || !target.getKey().containsKey(target.getValue())) {
            return false;
        }
        BsonValue replacement = change.apply(target.getKey().get(target.getValue()));
        if (replacement == null) {
            return false;
        }
        target.getKey().put(target.getValue(), replacement);
        return true;
    }

    private static boolean set(BsonDocument document, @Nullable String path, BsonValue value) {
        Map.Entry<BsonDocument, String> target = parent(document, path, true);
        if (target == null) {
            return false;
        }
        target.getKey().put(target.getValue(), value);
        return true;
    }

    private static boolean remove(BsonDocument document, @Nullable String path) {
        Map.Entry<BsonDocument, String> target = parent(document, path, false);
        return target != null && target.getKey().remove(target.getValue()) != null;
    }

    /** The document holding the last path segment, and that segment; {@code null} if the path is not reachable. */
    private static Map.@Nullable Entry<BsonDocument, String> parent(BsonDocument document, @Nullable String path,
                                                                     boolean create) {
        if (path == null) {
            return null;
        }
        String[] segments = path.split("\\.");
        BsonDocument current = document;
        for (int i = 0; i < segments.length - 1; i++) {
            BsonValue next = current.get(segments[i]);
            if (next == null && create) {
                next = new BsonDocument();
                current.put(segments[i], next);
            }
            if (next == null || !next.isDocument()) {
                return null;
            }
            current = next.asDocument();
        }
        return Map.entry(current, segments[segments.length - 1]);
    }
}
