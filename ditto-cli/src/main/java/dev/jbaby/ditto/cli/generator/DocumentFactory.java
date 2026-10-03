package dev.jbaby.ditto.cli.generator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;

import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDecimal128;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

/**
 * Creates product-like documents with nested objects, arrays of objects, a map with dynamic keys, an ordered event
 * list and a mix of BSON types. Deterministic for a given seed.
 */
public final class DocumentFactory {

    private static final long EPOCH = 1_700_000_000_000L;
    private static final List<String> CATEGORIES = List.of("tools", "garden", "kitchen", "toys", "books", "sports");
    private static final List<String> TAGS = List.of("new", "sale", "eco", "bestseller", "limited", "imported");
    private static final List<String> ATTRIBUTES = List.of("color", "size", "material", "weight", "brand", "origin");
    private static final List<String> VALUES = List.of("red", "blue", "XL", "steel", "wood", "1.5kg", "acme", "EU");
    private static final List<String> EVENTS = List.of("created", "priced", "stocked", "published", "reviewed");

    private final Random random;

    public DocumentFactory(long seed) {
        this.random = new Random(seed);
    }

    public BsonDocument create(int index) {
        var document = new BsonDocument("_id", new BsonObjectId(new ObjectId((int) (EPOCH / 1000) + index,
                random.nextInt(1 << 24))))
                .append("sku", new BsonString(String.format("SKU-%07d", index)))
                .append("name", new BsonString("Product " + index))
                .append("price", new BsonDecimal128(new Decimal128(
                        BigDecimal.valueOf(random.nextInt(100_000), 2).setScale(2, RoundingMode.UNNECESSARY))))
                .append("qty", new BsonInt32(random.nextInt(500)))
                .append("active", BsonBoolean.valueOf(random.nextInt(10) > 0))
                .append("createdAt", new BsonDateTime(EPOCH + random.nextLong(100_000_000_000L)))
                .append("category", category())
                .append("tags", tags())
                .append("attributes", attributes())
                .append("variants", variants(index))
                .append("history", history())
                .append("legacyCode", new BsonString("L" + Integer.toHexString(index)))
                .append("note", random.nextInt(4) == 0 ? BsonNull.VALUE : new BsonString("note " + index))
                .append("meta", new BsonDocument("syncedAt", new BsonDateTime(EPOCH))
                        .append("source", new BsonString("erp"))
                        .append("version", new BsonInt32(1)));
        if (random.nextInt(20) == 0) {
            document.append("discontinuedAt", new BsonDateTime(EPOCH + random.nextLong(1_000_000_000L)));
        }
        return document;
    }

    private BsonDocument category() {
        int id = random.nextInt(CATEGORIES.size());
        return new BsonDocument("id", new BsonInt32(id))
                .append("name", new BsonString(CATEGORIES.get(id)))
                .append("path", new BsonArray(List.of(new BsonString("root"), new BsonString(CATEGORIES.get(id)))));
    }

    private BsonArray tags() {
        var tags = new BsonArray();
        TAGS.stream().filter(candidate -> random.nextInt(4) == 0).forEach(tag -> tags.add(new BsonString(tag)));
        return tags;
    }

    private BsonDocument attributes() {
        var attributes = new BsonDocument();
        for (String name : ATTRIBUTES) {
            if (random.nextBoolean()) {
                attributes.append(name, new BsonString(VALUES.get(random.nextInt(VALUES.size()))));
            }
        }
        return attributes;
    }

    private BsonArray variants(int index) {
        var variants = new BsonArray();
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++) {
            variants.add(new BsonDocument("sku", new BsonString(String.format("SKU-%07d-%d", index, i)))
                    .append("price", new BsonDouble(Math.round(random.nextDouble() * 10_000) / 100.0))
                    .append("stock", new BsonInt64(random.nextInt(1000)))
                    .append("dims", new BsonDocument("w", new BsonInt32(1 + random.nextInt(100)))
                            .append("h", new BsonInt32(1 + random.nextInt(100)))));
        }
        return variants;
    }

    private BsonArray history() {
        var history = new BsonArray();
        int count = 1 + random.nextInt(EVENTS.size());
        for (int i = 0; i < count; i++) {
            history.add(new BsonDocument("event", new BsonString(EVENTS.get(i)))
                    .append("at", new BsonDateTime(EPOCH + i * 86_400_000L)));
        }
        return history;
    }
}
