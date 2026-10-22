package dev.jbaby.ditto.comparator.key;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;

import org.bson.BsonBinary;
import org.bson.BsonDbPointer;
import org.bson.BsonDocument;
import org.bson.BsonRegularExpression;
import org.bson.BsonValue;
import org.bson.types.Decimal128;

/**
 * MongoDB's sort order for BSON values under the simple (binary) collation, as used by {@code sort({key: 1})}.
 * The merge-join relies on this matching the server exactly:
 * <ul>
 * <li>different {@link TypeBracket}s sort by bracket</li>
 * <li>numbers by exact value across int32/int64/double/Decimal128; NaN below all other numbers</li>
 * <li>strings by UTF-8 bytes, i.e. by code point (not by UTF-16 unit like {@link String#compareTo})</li>
 * <li>documents element by element: bracket of the value, then field name, then value; shorter first</li>
 * <li>binary data by length, then subtype, then bytes</li>
 * </ul>
 * Arrays as top-level sort keys are not covered: MongoDB sorts those by their smallest element.
 */
public final class BsonKeyOrder implements Comparator<BsonValue> {

    public static final BsonKeyOrder INSTANCE = new BsonKeyOrder();

    private BsonKeyOrder() {
    }

    @Override
    public int compare(BsonValue a, BsonValue b) {
        TypeBracket bracketA = TypeBracket.of(a.getBsonType());
        TypeBracket bracketB = TypeBracket.of(b.getBsonType());
        if (bracketA != bracketB) {
            return bracketA.compareTo(bracketB);
        }
        return switch (bracketA) {
            case MIN_KEY, UNDEFINED, NULL, MAX_KEY -> 0;
            case NUMBER -> compareNumbers(a, b);
            case STRING -> compareStrings(string(a), string(b));
            case OBJECT -> compareDocuments(a.asDocument(), b.asDocument());
            case ARRAY -> compareDocuments(indexed(a), indexed(b));
            case BINARY -> compareBinaries(a.asBinary(), b.asBinary());
            case OBJECT_ID -> a.asObjectId().getValue().compareTo(b.asObjectId().getValue());
            case BOOLEAN -> Boolean.compare(a.asBoolean().getValue(), b.asBoolean().getValue());
            case DATE -> Long.compare(a.asDateTime().getValue(), b.asDateTime().getValue());
            case TIMESTAMP -> Long.compareUnsigned(a.asTimestamp().getValue(), b.asTimestamp().getValue());
            case REGEX -> compareRegexes(a.asRegularExpression(), b.asRegularExpression());
            case DB_POINTER -> compareDbPointers(a.asDBPointer(), b.asDBPointer());
            case JAVASCRIPT -> compareStrings(a.asJavaScript().getCode(), b.asJavaScript().getCode());
            case JAVASCRIPT_WITH_SCOPE -> {
                int byCode = compareStrings(a.asJavaScriptWithScope().getCode(), b.asJavaScriptWithScope().getCode());
                yield byCode != 0 ? byCode
                        : compareDocuments(a.asJavaScriptWithScope().getScope(), b.asJavaScriptWithScope().getScope());
            }
        };
    }

    private int compareDocuments(BsonDocument a, BsonDocument b) {
        Iterator<Map.Entry<String, BsonValue>> itA = a.entrySet().iterator();
        Iterator<Map.Entry<String, BsonValue>> itB = b.entrySet().iterator();
        while (true) {
            if (!itA.hasNext()) {
                return itB.hasNext() ? -1 : 0;
            }
            if (!itB.hasNext()) {
                return 1;
            }
            Map.Entry<String, BsonValue> entryA = itA.next();
            Map.Entry<String, BsonValue> entryB = itB.next();
            int order = TypeBracket.of(entryA.getValue().getBsonType())
                    .compareTo(TypeBracket.of(entryB.getValue().getBsonType()));
            if (order == 0) {
                order = compareStrings(entryA.getKey(), entryB.getKey());
            }
            if (order == 0) {
                order = compare(entryA.getValue(), entryB.getValue());
            }
            if (order != 0) {
                return order;
            }
        }
    }

    /** An array as the document {@code {"0": ..., "1": ...}}, which is how BSON stores and MongoDB compares it. */
    private static BsonDocument indexed(BsonValue array) {
        BsonDocument document = new BsonDocument();
        int index = 0;
        for (BsonValue element : array.asArray()) {
            document.append(Integer.toString(index++), element);
        }
        return document;
    }

    /** Code point order, which equals the byte order of the UTF-8 encodings. */
    static int compareStrings(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int codePointA = a.codePointAt(i);
            int codePointB = b.codePointAt(j);
            if (codePointA != codePointB) {
                return Integer.compare(codePointA, codePointB);
            }
            i += Character.charCount(codePointA);
            j += Character.charCount(codePointB);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    private static String string(BsonValue value) {
        return value.isSymbol() ? value.asSymbol().getSymbol() : value.asString().getValue();
    }

    private static int compareBinaries(BsonBinary a, BsonBinary b) {
        byte[] dataA = a.getData();
        byte[] dataB = b.getData();
        if (dataA.length != dataB.length) {
            return Integer.compare(dataA.length, dataB.length);
        }
        if (a.getType() != b.getType()) {
            return Integer.compare(Byte.toUnsignedInt(a.getType()), Byte.toUnsignedInt(b.getType()));
        }
        return Arrays.compareUnsigned(dataA, dataB);
    }

    private static int compareRegexes(BsonRegularExpression a, BsonRegularExpression b) {
        int byPattern = compareStrings(a.getPattern(), b.getPattern());
        return byPattern != 0 ? byPattern : compareStrings(a.getOptions(), b.getOptions());
    }

    private static int compareDbPointers(BsonDbPointer a, BsonDbPointer b) {
        int byLength = Integer.compare(a.getNamespace().length(), b.getNamespace().length());
        if (byLength != 0) {
            return byLength;
        }
        int byNamespace = compareStrings(a.getNamespace(), b.getNamespace());
        return byNamespace != 0 ? byNamespace : a.getId().compareTo(b.getId());
    }

    // --- numbers ---

    /** Rank of special values: NaN < -Infinity < finite < +Infinity. */
    private static final int NAN = 0;
    private static final int NEGATIVE_INFINITY = 1;
    private static final int FINITE = 2;
    private static final int POSITIVE_INFINITY = 3;

    private static int compareNumbers(BsonValue a, BsonValue b) {
        int rankA = rank(a);
        int rankB = rank(b);
        if (rankA != rankB || rankA != FINITE) {
            return Integer.compare(rankA, rankB);
        }
        if (isIntegral(a) && isIntegral(b)) {
            return Long.compare(a.asNumber().longValue(), b.asNumber().longValue());
        }
        if (a.isDouble() && b.isDouble()) {
            double x = a.asDouble().getValue();
            double y = b.asDouble().getValue();
            return x < y ? -1 : x > y ? 1 : 0; // -0.0 == 0.0
        }
        boolean decimal = a.isDecimal128() || b.isDecimal128();
        return exact(a, decimal).compareTo(exact(b, decimal));
    }

    private static int rank(BsonValue number) {
        if (number.isDouble()) {
            double value = number.asDouble().getValue();
            return Double.isNaN(value) ? NAN
                    : value == Double.NEGATIVE_INFINITY ? NEGATIVE_INFINITY
                    : value == Double.POSITIVE_INFINITY ? POSITIVE_INFINITY
                    : FINITE;
        }
        if (number.isDecimal128()) {
            Decimal128 value = number.asDecimal128().getValue();
            return value.isNaN() ? NAN
                    : value.isInfinite() ? (value.isNegative() ? NEGATIVE_INFINITY : POSITIVE_INFINITY)
                    : FINITE;
        }
        return FINITE;
    }

    private static boolean isIntegral(BsonValue number) {
        return number.isInt32() || number.isInt64();
    }

    /**
     * Exact value of a finite number. When compared with a Decimal128, doubles are rounded to 34 significant digits
     * first, as the server does.
     */
    private static BigDecimal exact(BsonValue number, boolean againstDecimal) {
        return switch (number.getBsonType()) {
            case INT32 -> BigDecimal.valueOf(number.asInt32().getValue());
            case INT64 -> BigDecimal.valueOf(number.asInt64().getValue());
            case DOUBLE -> {
                BigDecimal value = new BigDecimal(number.asDouble().getValue());
                yield againstDecimal ? value.round(MathContext.DECIMAL128) : value;
            }
            case DECIMAL128 -> {
                try {
                    yield number.asDecimal128().getValue().bigDecimalValue();
                } catch (ArithmeticException negativeZero) {
                    yield BigDecimal.ZERO;
                }
            }
            default -> throw new IllegalArgumentException("not a number: " + number);
        };
    }
}
