package dev.jbaby.ditto.comparator.canonical;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.bson.types.ObjectId;

/**
 * A BSON value in canonical form: equal canonical values mean equal content for the comparison.
 * <ul>
 * <li>all numeric types become {@link CNumber} (by value) or {@link CNonFinite}</li>
 * <li>document fields are sorted by name</li>
 * <li>arrays are sorted by the canonical encoding of their elements unless order-sensitive</li>
 * <li>dates are epoch millis</li>
 * </ul>
 * {@code equals} and {@code hashCode} are structural, so canonical values can be compared and used in sets directly.
 */
public sealed interface CanonicalValue {

    record CNull() implements CanonicalValue {
        public static final CNull INSTANCE = new CNull();
    }

    record CBoolean(boolean value) implements CanonicalValue {
    }

    /** A finite number, with trailing zeros stripped so equal values have equal representations. */
    record CNumber(BigDecimal value) implements CanonicalValue {

        public CNumber {
            requireNonNull(value, "value");
            value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }

        public static CNumber of(long value) {
            return new CNumber(BigDecimal.valueOf(value));
        }
    }

    /** NaN or an infinity (double or Decimal128). */
    record CNonFinite(Kind kind) implements CanonicalValue {

        public enum Kind {
            NAN,
            POSITIVE_INFINITY,
            NEGATIVE_INFINITY
        }
    }

    /** A string (also used for the deprecated BSON symbol type). */
    record CString(String value) implements CanonicalValue {
    }

    record CDate(long epochMillis) implements CanonicalValue {
    }

    record CObjectId(ObjectId value) implements CanonicalValue {
    }

    record CBinary(byte subtype, byte[] data) implements CanonicalValue {

        public CBinary {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        byte[] dataUnsafe() {
            return data;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CBinary binary && subtype == binary.subtype && Arrays.equals(data, binary.data);
        }

        @Override
        public int hashCode() {
            return 31 * subtype + Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return "CBinary[subtype=" + subtype + ", data=" + HexFormat.of().formatHex(data) + "]";
        }
    }

    /**
     * Any other BSON type (regex, timestamp, JavaScript, min/max key, ...), compared by a type-specific representation.
     *
     * @param type           BSON type name
     * @param representation deterministic textual form of the value
     */
    record COther(String type, String representation) implements CanonicalValue {
    }

    /** A document with fields sorted by name. */
    record CDocument(List<Field> fields) implements CanonicalValue {

        public static final CDocument EMPTY = new CDocument(List.of());

        public CDocument {
            fields = List.copyOf(fields);
        }
    }

    record Field(String name, CanonicalValue value) {
    }

    /** An array; sorted by canonical encoding unless its path is order-sensitive. */
    record CArray(List<CanonicalValue> elements) implements CanonicalValue {

        public static final CArray EMPTY = new CArray(List.of());

        public CArray {
            elements = List.copyOf(elements);
        }
    }

    /** Whether this value is a leaf for path flattening: a scalar, an empty document or an empty array. */
    default boolean isLeaf() {
        return switch (this) {
            case CDocument document -> document.fields().isEmpty();
            case CArray array -> array.elements().isEmpty();
            default -> true;
        };
    }
}
