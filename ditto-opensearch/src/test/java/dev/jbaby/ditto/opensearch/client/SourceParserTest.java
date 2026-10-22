package dev.jbaby.ditto.opensearch.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.bson.BsonDocument;
import org.bson.BsonType;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class SourceParserTest {

    private final SourceParser parser = new SourceParser(JsonMapper.builder().build());

    @Test
    void mapsJsonTypesToBson() {
        BsonDocument document = parse("""
                {"s": "ä€😀", "i": 7, "l": 9000000000, "big": 123456789012345678901234567890,
                 "d": 19.99, "exact": 0.1000000000000000000001, "t": true, "f": false, "n": null,
                 "o": {"b": 1, "a": [1, "x", {"y": null}]}, "e": [], "eo": {}, "date": "2026-10-22T08:00:00Z"}
                """);

        assertThat(document.keySet()).containsExactly("s", "i", "l", "big", "d", "exact", "t", "f", "n", "o", "e",
                "eo", "date");
        assertThat(document.getString("s").getValue()).isEqualTo("ä€😀");
        assertThat(document.get("i").getBsonType()).isEqualTo(BsonType.INT32);
        assertThat(document.get("l").getBsonType()).isEqualTo(BsonType.INT64);
        assertThat(document.get("big").asDecimal128().getValue().bigDecimalValue())
                .isEqualTo(new BigDecimal("123456789012345678901234567890"));
        assertThat(document.getDouble("d").getValue()).isEqualTo(19.99);
        assertThat(document.get("exact").asDecimal128().getValue().bigDecimalValue())
                .isEqualByComparingTo("0.1000000000000000000001");
        assertThat(document.getBoolean("t").getValue()).isTrue();
        assertThat(document.get("n").isNull()).isTrue();
        assertThat(document.getDocument("o").toJson()).isEqualTo("{\"b\": 1, \"a\": [1, \"x\", {\"y\": null}]}");
        assertThat(document.getArray("e")).isEmpty();
        assertThat(document.getDocument("eo")).isEmpty();
        assertThat(document.get("date").getBsonType()).isEqualTo(BsonType.STRING);
    }

    @Test
    void decimalsThatDoublesHoldStayDoubles() {
        assertThat(SourceParser.decimal(new BigDecimal("1.0")).getBsonType()).isEqualTo(BsonType.DOUBLE);
        assertThat(SourceParser.decimal(new BigDecimal("0.30000000000000004")).getBsonType())
                .isEqualTo(BsonType.DOUBLE);
        assertThat(SourceParser.decimal(new BigDecimal("1e400")).getBsonType()).isEqualTo(BsonType.DECIMAL128);
        assertThat(SourceParser.decimal(new BigDecimal("1.10000000000000001")).getBsonType())
                .isEqualTo(BsonType.DECIMAL128);
    }

    private BsonDocument parse(String json) {
        return parser.parse(Slice.of(json.getBytes(StandardCharsets.UTF_8)));
    }
}
