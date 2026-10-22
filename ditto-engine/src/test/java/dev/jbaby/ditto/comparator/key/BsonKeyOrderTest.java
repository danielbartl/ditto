package dev.jbaby.ditto.comparator.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.bson.BsonBinary;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonMaxKey;
import org.bson.BsonMinKey;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonTimestamp;
import org.bson.BsonValue;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class BsonKeyOrderTest {

    private final BsonKeyOrder order = BsonKeyOrder.INSTANCE;

    @Test
    void bracketsSortInMongoOrder() {
        List<BsonValue> ascending = List.of(
                new BsonMinKey(), BsonNull.VALUE, new BsonInt32(5), new BsonString("a"),
                new BsonDocument("a", new BsonInt32(1)), new BsonBinary(new byte[] {1}),
                new BsonObjectId(new ObjectId()), BsonBoolean.FALSE, new BsonDateTime(0),
                new BsonTimestamp(1, 1), new BsonMaxKey());
        assertStrictlyAscending(ascending);
    }

    @Test
    void numbersCompareByExactValueAcrossTypes() {
        assertThat(order.compare(new BsonInt32(1), new BsonDouble(1.0))).isZero();
        assertThat(order.compare(new BsonInt64(1), dec("1.000"))).isZero();
        assertThat(order.compare(new BsonDouble(-0.0), new BsonInt32(0))).isZero();
        assertThat(order.compare(dec("-0"), new BsonDouble(0.0))).isZero();
        // 2^53 + 1 is not representable as double
        assertThat(order.compare(new BsonInt64(9_007_199_254_740_993L), new BsonDouble(9_007_199_254_740_992.0)))
                .isPositive();
        assertStrictlyAscending(List.of(
                new BsonDouble(Double.NaN), new BsonDouble(Double.NEGATIVE_INFINITY), new BsonInt64(Long.MIN_VALUE),
                new BsonDouble(-1.5), new BsonInt32(-1), dec("-0.5"), new BsonInt32(0), new BsonDouble(0.1),
                dec("0.2"), new BsonInt32(1), new BsonInt64(Long.MAX_VALUE), dec("1E+40"),
                new BsonDouble(Double.POSITIVE_INFINITY)));
        assertThat(order.compare(new BsonDouble(Double.NaN), dec("NaN"))).isZero();
    }

    @Test
    void stringsCompareByCodePointNotUtf16Unit() {
        // U+FFFD is greater than U+1F600 in UTF-16 units (0xFFFD > 0xD83D) but smaller as code point / UTF-8
        assertThat("�".compareTo("😀")).isPositive();
        assertThat(order.compare(new BsonString("�"), new BsonString("😀"))).isNegative();
        assertStrictlyAscending(List.of(new BsonString(""), new BsonString("A"), new BsonString("B"),
                new BsonString("a"), new BsonString("aa"), new BsonString("ä"), new BsonString("😀")));
    }

    @Test
    void documentsCompareByTypeThenNameThenValue() {
        assertStrictlyAscending(List.of(
                BsonDocument.parse("{}"),
                BsonDocument.parse("{a: 1}"),
                BsonDocument.parse("{a: 1, b: 1}"),
                BsonDocument.parse("{a: 2}"),
                BsonDocument.parse("{b: 1}"),         // number bracket before string bracket, then name
                BsonDocument.parse("{a: 'x'}")));
        assertThat(order.compare(BsonDocument.parse("{a: 1, b: 1}"), BsonDocument.parse("{a: 2}"))).isNegative();
        assertThat(order.compare(BsonDocument.parse("{a: 2}"), BsonDocument.parse("{a: 2, b: 1}"))).isNegative();
        assertThat(order.compare(BsonDocument.parse("{b: 1}"), BsonDocument.parse("{a: 'x'}"))).isNegative();
        assertThat(order.compare(BsonDocument.parse("{a: 1}"), BsonDocument.parse("{b: 1}"))).isNegative();
        assertThat(order.compare(BsonDocument.parse("{a: 1}"), BsonDocument.parse("{a: 1.0}"))).isZero();
    }

    @Test
    void binariesCompareByLengthThenSubtypeThenBytes() {
        assertStrictlyAscending(List.of(
                new BsonBinary((byte) 5, new byte[] {9}),
                new BsonBinary((byte) 0, new byte[] {0, 0}),
                new BsonBinary((byte) 0, new byte[] {0, (byte) 0xFF}),
                new BsonBinary((byte) 4, new byte[] {0, 0})));
    }

    @Test
    void datesAreSignedAndTimestampsUnsigned() {
        assertThat(order.compare(new BsonDateTime(-1), new BsonDateTime(1))).isNegative();
        assertThat(order.compare(new BsonTimestamp(1, 0), new BsonTimestamp(0xFFFF_FFFF, 0))).isNegative();
    }

    private void assertStrictlyAscending(List<? extends BsonValue> values) {
        for (int i = 0; i < values.size(); i++) {
            for (int j = 0; j < values.size(); j++) {
                assertThat(Integer.signum(order.compare(values.get(i), values.get(j))))
                        .as("%s vs %s", values.get(i), values.get(j))
                        .isEqualTo(Integer.compare(i, j));
            }
        }
    }

    private static BsonDecimal128 dec(String value) {
        return new BsonDecimal128(Decimal128.parse(value));
    }
}
