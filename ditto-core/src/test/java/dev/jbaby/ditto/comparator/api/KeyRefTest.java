package dev.jbaby.ditto.comparator.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.bson.BsonBinary;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.UuidRepresentation;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class KeyRefTest {

    @Test
    void rendersKeysAsRelaxedExtendedJson() {
        var id = new ObjectId("65f0a1b2c3d4e5f601234567");

        assertThat(KeyRef.of(new BsonObjectId(id)))
                .isEqualTo(new KeyRef("OBJECT_ID", "{\"$oid\": \"65f0a1b2c3d4e5f601234567\"}"));
        assertThat(KeyRef.of(new BsonString("A:1,\"b\"")))
                .isEqualTo(new KeyRef("STRING", "\"A:1,\\\"b\\\"\""));
        assertThat(KeyRef.of(new BsonInt64(42))).isEqualTo(new KeyRef("INT64", "42"));
    }

    @Test
    void parsesBackToTheSameValue() {
        var keys = new org.bson.BsonValue[] {
                new BsonObjectId(new ObjectId()),
                new BsonString("ä/€/😀"),
                new BsonInt32(7),
                new BsonDateTime(1_700_000_000_000L),
                new BsonBinary(UUID.randomUUID(), UuidRepresentation.STANDARD),
                new BsonDocument("a", new BsonInt32(1)).append("b", new BsonString("x"))
        };
        for (var key : keys) {
            assertThat(KeyRef.of(key).toBsonValue()).isEqualTo(key);
        }
    }
}
