package dev.jbaby.ditto.comparator.scan;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.key.BsonKeyOrder;

/**
 * One side of a comparison, as the scan algorithms read it: a MongoDB collection, an OpenSearch index, ... Keys are
 * BSON values; maps returned are ordered by {@link BsonKeyOrder}.
 */
public interface DocumentSource {

    /** "baseline" or "candidate", for messages. */
    String side();

    CollectionRef ref();

    /**
     * Whether {@link #scan} returns the documents in ascending {@link BsonKeyOrder} of their keys, so both sides can
     * be merge-joined in one pass. Otherwise every document is looked up on the other side.
     */
    boolean keyOrdered();

    /**
     * All documents, in key order if {@link #keyOrdered()}.
     *
     * @param batchSize documents fetched from the server at a time
     * @param keysOnly  documents only need their key; the source may leave out the rest
     * @throws ComparisonException if a document has no key
     */
    DocumentCursor scan(String keyField, int batchSize, boolean noCursorTimeout, boolean keysOnly);

    /** Up to {@code size} random documents by key, without duplicates. */
    NavigableMap<BsonValue, SourceDocument> randomByKey(String keyField, int size);

    /**
     * The documents with the given keys, by key.
     *
     * @param keysOnly documents only need their key; the source may leave out the rest
     * @throws ComparisonException if a key occurs twice
     */
    Map<BsonValue, SourceDocument> findByKeys(String keyField, List<BsonValue> keys, boolean keysOnly);

    /**
     * Fails if one of these keys, read from this source, belongs to more than one of its documents. Needed wherever
     * keys come from reading a side that a key-ordered scan doesn't check, e.g. a sample. Looks the keys up once more,
     * keys only; a source whose keys can't repeat (e.g. a document id) skips that.
     *
     * @throws ComparisonException if a key occurs twice
     */
    default void requireUniqueKeys(String keyField, List<BsonValue> keys) {
        if (!keys.isEmpty()) {
            findByKeys(keyField, keys, true);
        }
    }

    /** Up to {@code size} random documents, decoded, for a look at the structure. */
    List<BsonDocument> randomDocuments(int size);
}
