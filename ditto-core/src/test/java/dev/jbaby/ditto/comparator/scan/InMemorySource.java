package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.key.BsonKeyOrder;

/**
 * A {@link DocumentSource} over a list of documents, scanned in list order (not key order). For tests.
 */
public record InMemorySource(String side, CollectionRef ref, List<BsonDocument> documents)
        implements DocumentSource {

    public static InMemorySource of(String side, String... json) {
        return new InMemorySource(side, CollectionRef.of("db", side),
                java.util.Arrays.stream(json).map(BsonDocument::parse).toList());
    }

    @Override
    public boolean keyOrdered() {
        return false;
    }

    @Override
    public DocumentCursor scan(String keyField, int batchSize, boolean noCursorTimeout, boolean keysOnly) {
        Iterator<BsonDocument> iterator = documents.iterator();
        return new DocumentCursor() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public KeyedDocument next() {
                BsonDocument document = iterator.next();
                return new KeyedDocument(keyOf(document, keyField),
                        new Doc(keysOnly ? new BsonDocument(keyField, keyOf(document, keyField)) : document));
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public NavigableMap<BsonValue, SourceDocument> randomByKey(String keyField, int size) {
        NavigableMap<BsonValue, SourceDocument> sample = new TreeMap<>(BsonKeyOrder.INSTANCE);
        documents.stream().limit(size).forEach(document -> sample.put(keyOf(document, keyField), new Doc(document)));
        return sample;
    }

    @Override
    public Map<BsonValue, SourceDocument> findByKeys(String keyField, List<BsonValue> keys, boolean keysOnly) {
        Map<BsonValue, SourceDocument> found = new TreeMap<>(BsonKeyOrder.INSTANCE);
        NavigableMap<BsonValue, Boolean> wanted = new TreeMap<>(BsonKeyOrder.INSTANCE);
        keys.forEach(key -> wanted.put(key, true));
        for (BsonDocument document : documents) {
            BsonValue key = keyOf(document, keyField);
            if (wanted.containsKey(key) && found.put(key, new Doc(document)) != null) {
                throw new ComparisonException("Duplicate key " + key + " in " + this);
            }
        }
        return found;
    }

    @Override
    public List<BsonDocument> randomDocuments(int size) {
        return new ArrayList<>(documents.subList(0, Math.min(size, documents.size())));
    }

    private static BsonValue keyOf(BsonDocument document, String keyField) {
        BsonValue key = document.get(keyField);
        if (key == null) {
            throw new ComparisonException("Document without key field " + keyField);
        }
        return key;
    }

    @Override
    public String toString() {
        return side + " " + ref;
    }

    /** Never byte-identical, so every pair is normalized. */
    record Doc(BsonDocument document) implements SourceDocument {

        @Override
        public BsonDocument decode() {
            return document.clone();
        }

        @Override
        public boolean sameBytes(SourceDocument other) {
            return false;
        }
    }
}
