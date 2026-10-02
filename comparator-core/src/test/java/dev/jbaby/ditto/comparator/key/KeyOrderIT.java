package dev.jbaby.ditto.comparator.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

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
import org.bson.UuidRepresentation;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Sorts;

import dev.jbaby.ditto.comparator.api.MixedKeyPolicy;
import dev.jbaby.ditto.comparator.support.MongoContainer;

/**
 * The merge-join is only correct if {@link BsonKeyOrder} agrees with the server's sort order; this checks it on
 * randomly generated keys of all supported types.
 */
class KeyOrderIT {

    private static final String[] NAMES = {"a", "b", "B", "ä", "a0", "_id"};
    private static final String[] STRINGS = {"", "a", "A", "b", "ab", "a b", "ä", "é", "", "�", "😀",
            "😀a", "\u0000x", "z\u0000"};

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void serverSortMatchesBsonKeyOrder(long seed) {
        MongoCollection<BsonDocument> collection = freshCollection("keys_" + seed);
        Random random = new Random(seed);
        List<BsonDocument> documents = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            BsonValue key = randomKey(random, 0);
            documents.add(new BsonDocument("_id", key).append("k", key).append("i", new BsonInt32(i)));
        }
        insertIgnoringDuplicates(collection, documents);

        assertAscending(collection, "_id", true);
        assertAscending(collection, "k", false);   // unindexed: in-memory sort
        collection.createIndex(Indexes.ascending("k"));
        assertAscending(collection, "k", false);
    }

    @Test
    void inspectorFindsBoundariesArraysAndMissingKeys() {
        MongoCollection<BsonDocument> collection = freshCollection("inspect");
        var inspector = new KeyInspector();
        assertThat(inspector.inspect(collection, "_id")).isEqualTo(KeyInspector.KeyRange.EMPTY);

        collection.insertMany(List.of(
                BsonDocument.parse("{_id: 3, sku: 'b'}"),
                BsonDocument.parse("{_id: 1, sku: 'a'}"),
                BsonDocument.parse("{_id: 2.5, sku: 'c'}")));
        var range = inspector.inspect(collection, "_id");
        assertThat(range.min()).isEqualTo(new BsonInt32(1));
        assertThat(range.max()).isEqualTo(new BsonInt32(3));
        assertThat(inspector.check("_id", range, range, MixedKeyPolicy.REJECT)).isEmpty();
        assertThat(inspector.inspect(collection, "sku").hasArrayKeys()).isFalse();
        assertThat(inspector.inspect(collection, "sku").hasMissingKeys()).isFalse();

        collection.insertOne(BsonDocument.parse("{_id: 4, sku: ['x', 'y']}"));
        collection.insertOne(BsonDocument.parse("{_id: 5}"));
        assertThat(inspector.inspect(collection, "sku").hasArrayKeys()).isTrue();
        assertThat(inspector.inspect(collection, "sku").hasMissingKeys()).isTrue();

        collection.insertOne(BsonDocument.parse("{_id: 'text'}"));
        var mixed = inspector.inspect(collection, "_id");
        assertThat(TypeBracket.of(mixed.max().getBsonType())).isEqualTo(TypeBracket.STRING);
        assertThat(mixed.brackets()).containsExactly(TypeBracket.NUMBER, TypeBracket.STRING);
    }

    @Test
    void uniqueIndexOnCustomKeyHonoursNumericEquality() {
        MongoCollection<BsonDocument> collection = freshCollection("unique");
        collection.createIndex(Indexes.ascending("sku"), new IndexOptions().unique(true));
        collection.insertOne(BsonDocument.parse("{sku: 1}"));
        insertIgnoringDuplicates(collection, List.of(BsonDocument.parse("{sku: 1.0}")));

        assertThat(collection.countDocuments(Filters.eq("sku", 1))).isEqualTo(1);
    }

    private static void assertAscending(MongoCollection<BsonDocument> collection, String field, boolean strict) {
        List<BsonValue> keys = new ArrayList<>();
        collection.find().sort(Sorts.ascending(field)).collation(KeyInspector.SIMPLE).batchSize(500)
                .forEach(document -> keys.add(document.get(field)));
        assertThat(keys).hasSizeGreaterThan(100);
        for (int i = 1; i < keys.size(); i++) {
            int order = BsonKeyOrder.INSTANCE.compare(keys.get(i - 1), keys.get(i));
            assertThat(strict ? order < 0 : order <= 0)
                    .as("sorted by %s: %s (%s) before %s (%s)", field, keys.get(i - 1), keys.get(i - 1).getBsonType(),
                            keys.get(i), keys.get(i).getBsonType())
                    .isTrue();
        }
    }

    private static BsonValue randomKey(Random random, int depth) {
        int kind = random.nextInt(depth < 2 ? 17 : 15);
        return switch (kind) {
            case 0 -> new BsonInt32(random.nextInt(201) - 100);
            case 1 -> new BsonInt64(random.nextBoolean() ? random.nextLong() : 9_007_199_254_740_990L + random.nextInt(6));
            case 2 -> new BsonDouble(switch (random.nextInt(8)) {
                case 0 -> Double.NaN;
                case 1 -> random.nextBoolean() ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
                case 2 -> -0.0;
                case 3 -> 9_007_199_254_740_992.0 + random.nextInt(4) * 2;
                default -> Math.round((random.nextDouble() * 200 - 100) * 4) / 4.0;
            });
            case 3 -> new BsonDecimal128(switch (random.nextInt(8)) {
                case 0 -> Decimal128.NaN;
                case 1 -> random.nextBoolean() ? Decimal128.POSITIVE_INFINITY : Decimal128.NEGATIVE_INFINITY;
                case 2 -> Decimal128.parse("-0");
                case 3 -> Decimal128.parse("0.1000000000000000055511151231257827");
                default -> Decimal128.parse((random.nextInt(2001) - 1000) / 8.0 + "");
            });
            case 4, 5 -> new BsonString(STRINGS[random.nextInt(STRINGS.length)]
                    + (random.nextBoolean() ? "" : STRINGS[random.nextInt(STRINGS.length)]));
            case 6 -> new BsonObjectId(new ObjectId(random.nextInt(), random.nextInt(1 << 24)));
            case 7 -> new BsonDateTime(random.nextLong(-10_000_000_000_000L, 10_000_000_000_000L));
            case 8 -> BsonBoolean.valueOf(random.nextBoolean());
            case 9 -> {
                byte[] data = new byte[random.nextInt(4)];
                random.nextBytes(data);
                yield new BsonBinary((byte) (random.nextBoolean() ? 0 : 0x80 + random.nextInt(2)), data);
            }
            case 10 -> new BsonBinary(new UUID(random.nextLong(), random.nextLong()), UuidRepresentation.STANDARD);
            case 11 -> new BsonTimestamp(random.nextInt(), random.nextInt(3));
            case 12 -> random.nextBoolean() ? new BsonMinKey() : new BsonMaxKey();
            case 13 -> BsonNull.VALUE;
            case 14 -> new BsonString("k" + random.nextInt(1000));
            default -> {
                BsonDocument document = new BsonDocument();
                int size = random.nextInt(3);
                for (int i = 0; i < size; i++) {
                    document.put(NAMES[random.nextInt(NAMES.length)], randomKey(random, depth + 1));
                }
                yield document;
            }
        };
    }

    private static void insertIgnoringDuplicates(MongoCollection<BsonDocument> collection, List<BsonDocument> documents) {
        try {
            collection.insertMany(documents, new InsertManyOptions().ordered(false));
        } catch (MongoBulkWriteException e) {
            assertThat(e.getWriteErrors()).allSatisfy(error -> assertThat(error.getCode()).isEqualTo(11000));
        }
    }

    private static MongoCollection<BsonDocument> freshCollection(String name) {
        MongoCollection<BsonDocument> collection = MongoContainer.client().getDatabase("key_order")
                .getCollection(name, BsonDocument.class);
        collection.drop();
        return collection;
    }
}
