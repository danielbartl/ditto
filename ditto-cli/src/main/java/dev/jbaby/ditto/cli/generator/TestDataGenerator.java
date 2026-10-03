package dev.jbaby.ditto.cli.generator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.jspecify.annotations.Nullable;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

/**
 * Writes a baseline collection and a candidate copy with the configured changes, in batches.
 */
public final class TestDataGenerator {

    private static final int BATCH = 1000;

    private final MongoDatabase database;

    public TestDataGenerator(MongoDatabase database) {
        this.database = database;
    }

    /**
     * @param baseline   baseline collection (dropped and recreated)
     * @param candidate  candidate collection (dropped and recreated)
     * @param docs       documents in the baseline
     * @param seed       random seed; equal settings produce equal data
     * @param changes    changes applied to the candidate
     * @param touchSync  set a new {@code meta.syncedAt} in every candidate document, like a real sync run would
     */
    public record Settings(String baseline, String candidate, int docs, long seed, List<ChangeSpec> changes,
                           boolean touchSync) {

        public Settings {
            if (docs < 0) {
                throw new IllegalArgumentException("docs must not be negative");
            }
            if (baseline.equals(candidate)) {
                throw new IllegalArgumentException("baseline and candidate must differ");
            }
            changes = List.copyOf(changes);
        }
    }

    /**
     * @param baselineDocs  documents written to the baseline
     * @param candidateDocs documents written to the candidate
     * @param applied       documents affected per change
     */
    public record Stats(long baselineDocs, long candidateDocs, Map<String, Long> applied) {
    }

    public Stats generate(Settings settings) {
        MongoCollection<BsonDocument> baseline = recreate(settings.baseline());
        MongoCollection<BsonDocument> candidate = recreate(settings.candidate());
        DocumentFactory factory = new DocumentFactory(settings.seed());
        Mutator mutator = new Mutator(settings.seed() + 1);
        Map<String, Long> applied = new LinkedHashMap<>();
        settings.changes().forEach(change -> applied.put(change.toString(), 0L));
        BsonDateTime syncedAt = new BsonDateTime(System.currentTimeMillis());

        List<BsonDocument> baselineBatch = new ArrayList<>(BATCH);
        List<BsonDocument> candidateBatch = new ArrayList<>(BATCH);
        long baselineDocs = 0;
        long candidateDocs = 0;
        for (int i = 0; i < settings.docs(); i++) {
            BsonDocument document = factory.create(i);
            baselineBatch.add(document);
            baselineDocs++;
            BsonDocument copy = mutate(document.clone(), settings, mutator, applied, syncedAt);
            if (copy != null) {
                candidateBatch.add(copy);
                candidateDocs++;
            }
            flushIfFull(baseline, baselineBatch);
            flushIfFull(candidate, candidateBatch);
        }
        for (ChangeSpec change : settings.changes()) {
            if (change.kind() == ChangeSpec.Kind.ADD_DOCS) {
                long count = Math.round(settings.docs() * change.fraction());
                for (int i = 0; i < count; i++) {
                    BsonDocument added = factory.create(settings.docs() + i);
                    if (settings.touchSync()) {
                        added.getDocument("meta").put("syncedAt", syncedAt);
                    }
                    candidateBatch.add(added);
                    candidateDocs++;
                    flushIfFull(candidate, candidateBatch);
                }
                applied.merge(change.toString(), count, Long::sum);
            }
        }
        flush(baseline, baselineBatch);
        flush(candidate, candidateBatch);
        return new Stats(baselineDocs, candidateDocs, applied);
    }

    /** The candidate version of a document, {@code null} if it is deleted. */
    private static @Nullable BsonDocument mutate(BsonDocument document, Settings settings, Mutator mutator,
                                       Map<String, Long> applied, BsonDateTime syncedAt) {
        for (ChangeSpec change : settings.changes()) {
            if (change.kind() == ChangeSpec.Kind.ADD_DOCS || !mutator.hits(change.fraction())) {
                continue;
            }
            if (change.kind() == ChangeSpec.Kind.DELETE_DOCS) {
                applied.merge(change.toString(), 1L, Long::sum);
                return null;
            }
            if (mutator.apply(change, document)) {
                applied.merge(change.toString(), 1L, Long::sum);
            }
        }
        if (settings.touchSync()) {
            document.getDocument("meta").put("syncedAt", syncedAt);
        }
        return document;
    }

    private MongoCollection<BsonDocument> recreate(String name) {
        MongoCollection<BsonDocument> collection = database.getCollection(name, BsonDocument.class);
        collection.drop();
        database.createCollection(name);
        return collection;
    }

    private static void flushIfFull(MongoCollection<BsonDocument> collection, List<BsonDocument> batch) {
        if (batch.size() >= BATCH) {
            flush(collection, batch);
        }
    }

    private static void flush(MongoCollection<BsonDocument> collection, List<BsonDocument> batch) {
        if (!batch.isEmpty()) {
            collection.insertMany(batch);
            batch.clear();
        }
    }
}
