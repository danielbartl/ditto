package dev.jbaby.ditto.opensearch.scan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.key.BsonKeyOrder;
import dev.jbaby.ditto.comparator.scan.DocumentCursor;
import dev.jbaby.ditto.comparator.scan.DocumentSource;
import dev.jbaby.ditto.comparator.scan.KeyedDocument;
import dev.jbaby.ditto.comparator.scan.SourceDocument;
import dev.jbaby.ditto.opensearch.client.OpenSearchHttp;
import dev.jbaby.ditto.opensearch.client.SearchPage;
import dev.jbaby.ditto.opensearch.client.Slice;
import dev.jbaby.ditto.opensearch.client.SourceParser;

/**
 * One side of the comparison in OpenSearch: an index or an alias of one index. Documents are read as the raw JSON of
 * their {@code _source}. OpenSearch cannot return all documents sorted by {@code _id}, so a full scan scrolls in
 * index order and the other side is looked up by key. Lookups are searches ({@code ids} or {@code terms} query), not
 * multi-gets, so they find documents with custom routing, and reveal duplicate keys. Only refreshed documents are
 * visible.
 */
public final class OpenSearchIndexSource implements DocumentSource {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexSource.class);
    /** Documents per scroll page for samples. */
    private static final int SAMPLE_PAGE_SIZE = 1000;
    /** OpenSearch's default {@code index.max_result_window}. */
    private static final int MAX_RESULT_WINDOW = 10_000;
    private static final SourceDocument KEY_ONLY = new SourceDocument() {
        @Override
        public BsonDocument decode() {
            return new BsonDocument();
        }

        @Override
        public boolean sameBytes(SourceDocument other) {
            return false;
        }
    };

    private final String side;
    private final CollectionRef ref;
    private final KeyField key;
    private final OpenSearchHttp http;
    private final SourceParser parser;
    private final String keepAlive;

    /**
     * @param ref             the index; its database is the cluster name
     * @param scrollKeepAlive how long OpenSearch keeps a scroll between two pages
     */
    public OpenSearchIndexSource(String side, CollectionRef ref, KeyField key, OpenSearchHttp http,
                                 SourceParser parser, Duration scrollKeepAlive) {
        this.side = side;
        this.ref = ref;
        this.key = key;
        this.http = http;
        this.parser = parser;
        this.keepAlive = Math.max(1, scrollKeepAlive.toSeconds()) + "s";
    }

    @Override
    public String side() {
        return side;
    }

    @Override
    public CollectionRef ref() {
        return ref;
    }

    public KeyField key() {
        return key;
    }

    @Override
    public boolean keyOrdered() {
        return false;
    }

    /** All documents in index order ({@code _doc}), the cheapest order to scroll. */
    @Override
    public DocumentCursor scan(String keyField, int batchSize, boolean noCursorTimeout, boolean keysOnly) {
        ObjectNode body = searchBody(Math.min(batchSize, MAX_RESULT_WINDOW), keysOnly);
        body.putArray("sort").add("_doc");
        body.putObject("query").putObject("match_all");
        return new ScrollCursor(body, keysOnly);
    }

    /** The first {@code size} documents by a random score, i.e. a sample without replacement. */
    @Override
    public NavigableMap<BsonValue, SourceDocument> randomByKey(String keyField, int size) {
        NavigableMap<BsonValue, SourceDocument> sample = new TreeMap<>(BsonKeyOrder.INSTANCE);
        try (ScrollCursor cursor = random(size)) {
            for (int read = 0; read < size && cursor.hasNext(); read++) {
                KeyedDocument document = cursor.next();
                sample.put(document.key(), document.document());
            }
        }
        return sample;
    }

    @Override
    public List<BsonDocument> randomDocuments(int size) {
        List<BsonDocument> documents = new ArrayList<>(Math.min(size, SAMPLE_PAGE_SIZE));
        try (ScrollCursor cursor = random(size)) {
            while (documents.size() < size && cursor.hasNext()) {
                documents.add(cursor.next().document().decode());
            }
        }
        return documents;
    }

    /**
     * Documents with the given keys, by key.
     *
     * @throws ComparisonException if a key occurs twice
     */
    @Override
    public Map<BsonValue, SourceDocument> findByKeys(String keyField, List<BsonValue> keys, boolean keysOnly) {
        Map<BsonValue, SourceDocument> found = new TreeMap<>(BsonKeyOrder.INSTANCE);
        if (keys.isEmpty()) {
            return found;
        }
        // room for duplicates, so they show up
        ObjectNode body = searchBody(Math.min(2 * keys.size(), MAX_RESULT_WINDOW), keysOnly);
        body.put("track_total_hits", true);
        ObjectNode query = body.putObject("query");
        ArrayNode values = key.isId() ? query.putObject("ids").putArray("values")
                : query.putObject("terms").putArray(key.lookupField());
        keys.forEach(value -> add(values, value));
        SearchPage page = search(body);
        for (SearchPage.Hit hit : page.hits()) {
            KeyedDocument document = keyed(hit, keysOnly);
            if (found.put(document.key(), document.document()) != null) {
                throw duplicate(document.key());
            }
        }
        if (page.total() > page.hits().size()) {
            throw new ComparisonException(page.total() + " documents in " + this + " have one of " + keys.size()
                    + " keys looked up, starting with " + KeyRef.of(keys.getFirst()).value()
                    + ": the key field must be unique");
        }
        return found;
    }

    private ComparisonException duplicate(BsonValue duplicate) {
        return new ComparisonException("Duplicate key " + KeyRef.of(duplicate).value() + " in " + this
                + ": the key field must be unique");
    }

    private ScrollCursor random(int size) {
        ObjectNode body = searchBody(Math.min(size, SAMPLE_PAGE_SIZE), false);
        ObjectNode functionScore = body.putObject("query").putObject("function_score");
        functionScore.putObject("query").putObject("match_all");
        functionScore.putObject("random_score")
                .put("seed", ThreadLocalRandom.current().nextInt())
                .put("field", "_seq_no");
        functionScore.put("boost_mode", "replace");
        return new ScrollCursor(body, false);
    }

    private ObjectNode searchBody(int size, boolean keysOnly) {
        ObjectNode body = http.object();
        body.put("size", size);
        body.put("_source", !keysOnly);
        if (!key.isId()) {
            body.putArray("docvalue_fields").add(key.lookupField());
        }
        return body;
    }

    private SearchPage search(ObjectNode body) {
        byte[] response = http.require("POST", "/" + ref.collection() + "/_search", Map.of(), body);
        return SearchPage.read(http.mapper(), response, key.isId() ? null : key.lookupField());
    }

    private KeyedDocument keyed(SearchPage.Hit hit, boolean keysOnly) {
        return new KeyedDocument(keyOf(hit), keysOnly ? KEY_ONLY
                : new JsonSourceDocument(parser, hit.source() != null ? hit.source() : Slice.EMPTY_OBJECT));
    }

    /** The key of a hit: its {@code _id} or the doc value of the key field. */
    private BsonValue keyOf(SearchPage.Hit hit) {
        if (key.isId()) {
            return new BsonString(hit.id());
        }
        if (hit.values().isEmpty()) {
            throw new ComparisonException("Document without key field '" + key.name() + "' in " + this + ": _id "
                    + hit.id());
        }
        if (hit.values().size() > 1) {
            throw new ComparisonException("Key field '" + key.name() + "' holds several values in " + this
                    + " (_id " + hit.id() + "); arrays cannot be used as keys");
        }
        return hit.values().getFirst();
    }

    private static void add(ArrayNode values, BsonValue value) {
        switch (value.getBsonType()) {
            case STRING -> values.add(value.asString().getValue());
            case INT32 -> values.add(value.asInt32().getValue());
            case INT64 -> values.add(value.asInt64().getValue());
            case DOUBLE -> values.add(value.asDouble().getValue());
            case DECIMAL128 -> values.add(value.asDecimal128().getValue().bigDecimalValue());
            default -> values.add(KeyRef.of(value).value());
        }
    }

    @Override
    public String toString() {
        return side + " index " + ref.collection();
    }

    /** Pages through a scroll; {@link #close()} releases it on the server. */
    private final class ScrollCursor implements DocumentCursor {

        private final ObjectNode firstRequest;
        private final boolean keysOnly;
        private @Nullable String scrollId;
        private Iterator<SearchPage.Hit> page = List.<SearchPage.Hit>of().iterator();
        private boolean exhausted;

        ScrollCursor(ObjectNode firstRequest, boolean keysOnly) {
            this.firstRequest = firstRequest;
            this.keysOnly = keysOnly;
        }

        @Override
        public boolean hasNext() {
            while (!page.hasNext() && !exhausted) {
                fetch();
            }
            return page.hasNext();
        }

        @Override
        public KeyedDocument next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return keyed(page.next(), keysOnly);
        }

        private void fetch() {
            byte[] response;
            if (scrollId == null) {
                response = http.require("POST", "/" + ref.collection() + "/_search", Map.of("scroll", keepAlive),
                        firstRequest);
            } else {
                ObjectNode next = http.object();
                next.put("scroll", keepAlive);
                next.put("scroll_id", scrollId);
                response = http.require("POST", "/_search/scroll", Map.of(), next);
            }
            SearchPage result = SearchPage.read(http.mapper(), response, key.isId() ? null : key.lookupField());
            scrollId = result.scrollId();
            exhausted = result.hits().isEmpty();
            page = result.hits().iterator();
        }

        @Override
        public void close() {
            exhausted = true;
            if (scrollId == null) {
                return;
            }
            ObjectNode clear = http.object();
            clear.putArray("scroll_id").add(scrollId);
            scrollId = null;
            try {
                http.execute("DELETE", "/_search/scroll", Map.of(), clear);
            } catch (RuntimeException e) {
                // the scroll expires on its own after the keep-alive
                log.debug("Clearing a scroll of {} failed: {}", OpenSearchIndexSource.this, e.getMessage());
            }
        }
    }
}
