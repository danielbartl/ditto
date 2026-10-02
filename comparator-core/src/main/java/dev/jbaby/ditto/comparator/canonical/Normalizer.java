package dev.jbaby.ditto.comparator.canonical;

import static java.util.Comparator.comparing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonRegularExpression;
import org.bson.BsonTimestamp;
import org.bson.BsonValue;
import org.bson.types.Decimal128;

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
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.Field;
import dev.jbaby.ditto.comparator.path.PathRules;

/**
 * Converts BSON documents into their {@link CanonicalValue canonical form}: drops the key field and ignored paths
 * (and null fields if {@code nullEqualsMissing}), sorts fields, sorts arrays that are not order-sensitive and
 * normalizes numbers and dates. Thread-safe; one instance per comparison run.
 */
public final class Normalizer {

    private static final Comparator<Field> BY_NAME = comparing(Field::name);

    private final PathRules rules;
    private final String keyField;
    private final boolean nullEqualsMissing;
    private final CanonicalEncoder encoder;

    public Normalizer(PathRules rules, String keyField, boolean nullEqualsMissing, CanonicalEncoder encoder) {
        this.rules = rules;
        this.keyField = keyField;
        this.nullEqualsMissing = nullEqualsMissing;
        this.encoder = encoder;
    }

    /** Canonical form of a top-level document, without its key field. */
    public CDocument normalize(BsonDocument document) {
        return document(document, rules.root(), true);
    }

    private CDocument document(BsonDocument document, PathRules.State state, boolean root) {
        List<Field> fields = new ArrayList<>(document.size());
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            String name = entry.getKey();
            BsonValue value = entry.getValue();
            if (root && name.equals(keyField)) {
                continue;
            }
            if (nullEqualsMissing && value.isNull()) {
                continue;
            }
            PathRules.State child = state.field(name);
            if (!child.ignored()) {
                fields.add(new Field(name, value(value, child)));
            }
        }
        fields.sort(BY_NAME);
        return fields.isEmpty() ? CDocument.EMPTY : new CDocument(fields);
    }

    private CArray array(BsonArray array, PathRules.State state) {
        PathRules.State elementState = state.element();
        if (elementState.ignored() || array.isEmpty()) {
            return CArray.EMPTY;
        }
        List<CanonicalValue> elements = new ArrayList<>(array.size());
        for (BsonValue element : array) {
            elements.add(value(element, elementState));
        }
        if (state.orderSensitive() || elements.size() == 1) {
            return new CArray(elements);
        }
        return new CArray(sortedByEncoding(elements));
    }

    private List<CanonicalValue> sortedByEncoding(List<CanonicalValue> elements) {
        record Encoded(byte[] bytes, CanonicalValue value) {
        }
        return elements.stream()
                .map(element -> new Encoded(encoder.encode(element), element))
                .sorted((a, b) -> Arrays.compareUnsigned(a.bytes(), b.bytes()))
                .map(Encoded::value)
                .toList();
    }

    private CanonicalValue value(BsonValue value, PathRules.State state) {
        return switch (value.getBsonType()) {
            case DOCUMENT -> document(value.asDocument(), state, false);
            case ARRAY -> array(value.asArray(), state);
            case NULL -> CNull.INSTANCE;
            case BOOLEAN -> new CBoolean(value.asBoolean().getValue());
            case INT32 -> CNumber.of(value.asInt32().getValue());
            case INT64 -> CNumber.of(value.asInt64().getValue());
            case DOUBLE -> number(value.asDouble().getValue());
            case DECIMAL128 -> number(value.asDecimal128().getValue());
            case STRING -> new CString(value.asString().getValue());
            case SYMBOL -> new CString(value.asSymbol().getSymbol());
            case DATE_TIME -> new CDate(value.asDateTime().getValue());
            case OBJECT_ID -> new CObjectId(value.asObjectId().getValue());
            case BINARY -> new CBinary(value.asBinary().getType(), value.asBinary().getData());
            case TIMESTAMP -> {
                BsonTimestamp timestamp = value.asTimestamp();
                yield new COther("TIMESTAMP", timestamp.getTime() + ":" + timestamp.getInc());
            }
            case REGULAR_EXPRESSION -> {
                BsonRegularExpression regex = value.asRegularExpression();
                yield new COther("REGULAR_EXPRESSION", "/" + regex.getPattern() + "/" + sortedChars(regex.getOptions()));
            }
            case JAVASCRIPT -> new COther("JAVASCRIPT", value.asJavaScript().getCode());
            case JAVASCRIPT_WITH_SCOPE -> new COther("JAVASCRIPT_WITH_SCOPE",
                    value.asJavaScriptWithScope().getCode() + "|" + value.asJavaScriptWithScope().getScope().toJson());
            case DB_POINTER -> new COther("DB_POINTER",
                    value.asDBPointer().getNamespace() + "|" + value.asDBPointer().getId().toHexString());
            case MIN_KEY, MAX_KEY, UNDEFINED, END_OF_DOCUMENT -> new COther(value.getBsonType().name(), "");
        };
    }

    static CanonicalValue number(double value) {
        if (Double.isNaN(value)) {
            return new CNonFinite(CNonFinite.Kind.NAN);
        }
        if (Double.isInfinite(value)) {
            return new CNonFinite(value > 0 ? CNonFinite.Kind.POSITIVE_INFINITY : CNonFinite.Kind.NEGATIVE_INFINITY);
        }
        // shortest decimal representation: 0.1 (double) equals 0.1 (Decimal128)
        return new CNumber(BigDecimal.valueOf(value));
    }

    static CanonicalValue number(Decimal128 value) {
        if (value.isNaN()) {
            return new CNonFinite(CNonFinite.Kind.NAN);
        }
        if (value.isInfinite()) {
            return new CNonFinite(value.isNegative()
                    ? CNonFinite.Kind.NEGATIVE_INFINITY : CNonFinite.Kind.POSITIVE_INFINITY);
        }
        try {
            return new CNumber(value.bigDecimalValue());
        } catch (ArithmeticException negativeZero) {
            return new CNumber(BigDecimal.ZERO);
        }
    }

    private static String sortedChars(String options) {
        char[] chars = options.toCharArray();
        Arrays.sort(chars);
        return new String(chars);
    }
}
