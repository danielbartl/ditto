package dev.jbaby.ditto.comparator.scan;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.key.KeyInspector;

/**
 * One side of the comparison: where it lives and how it is read. Documents are read as {@link RawBsonDocument}, so
 * exact BSON types are preserved and no object mapping takes place.
 *
 * @param side       "baseline" or "candidate", for messages
 * @param ref        the collection
 * @param database   its database
 * @param collection the collection, reading raw BSON
 */
public record CollectionHandle(String side, CollectionRef ref, MongoDatabase database,
                               MongoCollection<RawBsonDocument> collection) {

    public static CollectionHandle of(String side, CollectionRef ref, MongoDatabase database) {
        return new CollectionHandle(side, ref, database,
                database.getCollection(ref.collection(), RawBsonDocument.class));
    }

    public MongoCollection<BsonDocument> asBsonDocuments() {
        return collection.withDocumentClass(BsonDocument.class);
    }

    /** All documents sorted by key under the simple collation, as the merge-join needs them. */
    public FindIterable<RawBsonDocument> sortedByKey(String keyField, int batchSize, boolean noCursorTimeout) {
        return collection.find()
                .sort(Sorts.ascending(keyField))
                .collation(KeyInspector.SIMPLE)
                .batchSize(batchSize)
                .noCursorTimeout(noCursorTimeout)
                .allowDiskUse(true);
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
}
