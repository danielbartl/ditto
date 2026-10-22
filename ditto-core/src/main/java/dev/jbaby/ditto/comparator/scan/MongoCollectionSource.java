package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.key.BsonKeyOrder;
import dev.jbaby.ditto.comparator.key.KeyInspector;

/**
 * One side of the comparison in MongoDB: where it lives and how it is read. Documents are read as
 * {@link RawBsonDocument}, so exact BSON types are preserved and no object mapping takes place. A full scan is sorted
 * by key under the simple collation, so both sides can be merge-joined.
 *
 * @param side       "baseline" or "candidate", for messages
 * @param ref        the collection
 * @param database   its database
 * @param collection the collection, reading raw BSON
 */
public record MongoCollectionSource(String side, CollectionRef ref, MongoDatabase database,
                                    MongoCollection<RawBsonDocument> collection) implements DocumentSource {

    private static final BsonDocumentCodec CODEC = new BsonDocumentCodec();

    public static MongoCollectionSource of(String side, CollectionRef ref, MongoDatabase database) {
        return new MongoCollectionSource(side, ref, database,
                database.getCollection(ref.collection(), RawBsonDocument.class));
    }

    public MongoCollection<BsonDocument> asBsonDocuments() {
        return collection.withDocumentClass(BsonDocument.class);
    }

    @Override
    public boolean keyOrdered() {
        return true;
    }

    /** All documents sorted by key under the simple collation, as the merge-join needs them. */
    @Override
    public DocumentCursor scan(String keyField, int batchSize, boolean noCursorTimeout, boolean keysOnly) {
        FindIterable<RawBsonDocument> find = collection.find()
                .sort(Sorts.ascending(keyField))
                .collation(KeyInspector.SIMPLE)
                .batchSize(batchSize)
                .noCursorTimeout(noCursorTimeout)
                .allowDiskUse(true);
        if (keysOnly) {
            find = find.projection(Projections.include(keyField));
        }
        MongoCursor<RawBsonDocument> cursor = find.cursor();
        return new DocumentCursor() {
            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public KeyedDocument next() {
                RawBsonDocument document = cursor.next();
                return new KeyedDocument(keyOf(document, keyField), new RawDocument(document));
            }

            @Override
            public void close() {
                cursor.close();
            }
        };
    }

    /** Up to {@code size} random documents ({@code $sample}), decoded. */
    @Override
    public List<BsonDocument> randomDocuments(int size) {
        List<BsonDocument> documents = new ArrayList<>(Math.min(size, 1024));
        collection.aggregate(List.of(Aggregates.sample(size))).allowDiskUse(true)
                .forEach(document -> documents.add(document.decode(CODEC)));
        return documents;
    }

    /** Up to {@code size} random documents by key ({@code $sample}), without the duplicates it may return. */
    @Override
    public NavigableMap<BsonValue, SourceDocument> randomByKey(String keyField, int size) {
        NavigableMap<BsonValue, SourceDocument> sample = new TreeMap<>(BsonKeyOrder.INSTANCE);
        collection.aggregate(List.of(Aggregates.sample(size))).allowDiskUse(true)
                .forEach(document -> sample.put(keyOf(document, keyField), new RawDocument(document)));
        return sample;
    }

    /** Documents with the given keys ({@code $in}), by key. */
    @Override
    public Map<BsonValue, SourceDocument> findByKeys(String keyField, List<BsonValue> keys, boolean keysOnly) {
        Map<BsonValue, SourceDocument> found = new TreeMap<>(BsonKeyOrder.INSTANCE);
        var find = collection.find(Filters.in(keyField, keys)).collation(KeyInspector.SIMPLE);
        if (keysOnly) {
            find = find.projection(Projections.include(keyField));
        }
        find.forEach(document -> {
            BsonValue key = keyOf(document, keyField);
            if (found.put(key, new RawDocument(document)) != null) {
                throw new ComparisonException("Duplicate key " + KeyRef.of(key).value() + " in " + this
                        + ": the key field must be unique");
            }
        });
        return found;
    }

    /** The key of a document read from this side. */
    public BsonValue keyOf(RawBsonDocument document, String keyField) {
        BsonValue key = document.get(keyField);
        if (key == null) {
            throw new ComparisonException("Document without key field '" + keyField + "' in " + side + " " + ref
                    + (document.containsKey("_id") ? ": _id " + KeyRef.of(document.get("_id")).value() : ""));
        }
        return key;
    }

    @Override
    public String toString() {
        return side + " " + ref;
    }

    /** A raw BSON document; byte-identical BSON is unchanged content. */
    public record RawDocument(RawBsonDocument raw) implements SourceDocument {

        @Override
        public BsonDocument decode() {
            return raw.decode(CODEC);
        }

        @Override
        public boolean sameBytes(SourceDocument other) {
            return other instanceof RawDocument(RawBsonDocument otherRaw)
                    && raw.getByteBuffer().asNIO().equals(otherRaw.getByteBuffer().asNIO());
        }
    }
}
