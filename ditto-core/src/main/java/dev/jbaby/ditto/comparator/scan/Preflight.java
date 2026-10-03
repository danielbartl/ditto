package dev.jbaby.ditto.comparator.scan;

import java.util.ArrayList;
import java.util.List;

import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.jspecify.annotations.Nullable;

import com.mongodb.client.model.Filters;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.key.KeyInspector;

/**
 * Checks before reading: both collections exist, the keys can be merge-joined, and whether the key field has an index
 * usable for a simple-collation sort. Also provides the document counts.
 */
public final class Preflight {

    private final KeyInspector keyInspector;

    public Preflight(KeyInspector keyInspector) {
        this.keyInspector = keyInspector;
    }

    /**
     * @param baselineCount  documents in the baseline (from collection metadata)
     * @param candidateCount documents in the candidate (from collection metadata)
     * @param warnings       conditions to mention in the report
     */
    public record Result(long baselineCount, long candidateCount, List<String> warnings) {

        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * @throws ComparisonException if a collection does not exist or the keys cannot be merge-joined
     */
    public Result run(CollectionHandle baseline, CollectionHandle candidate, ComparisonSettings settings) {
        String keyField = settings.keyField();
        List<String> warnings = new ArrayList<>();
        for (CollectionHandle handle : List.of(baseline, candidate)) {
            BsonDocument info = collectionInfo(handle);
            String collation = collationLocale(info.getDocument("options", new BsonDocument()));
            if (keyField.equals("_id") && collation != null) {
                warnings.add(handle + " has default collation '" + collation + "', so its _id index cannot serve the"
                        + " simple-collation sort; the server sorts the whole collection (slow for large collections)");
            }
            if (!keyField.equals("_id") && !hasUsableIndex(handle, keyField)) {
                warnings.add(handle + " has no simple-collation index starting with '" + keyField
                        + "'; the server sorts the whole collection (slow for large collections)");
            }
        }
        var baselineRange = keyInspector.inspect(baseline.asBsonDocuments(), keyField);
        var candidateRange = keyInspector.inspect(candidate.asBsonDocuments(), keyField);
        warnings.addAll(keyInspector.check(keyField, baselineRange, candidateRange, settings.mixedKeyPolicy()));
        return new Result(baseline.collection().estimatedDocumentCount(),
                candidate.collection().estimatedDocumentCount(), warnings);
    }

    private static BsonDocument collectionInfo(CollectionHandle handle) {
        BsonDocument info = handle.database()
                .listCollections(BsonDocument.class)
                .filter(Filters.eq("name", handle.ref().collection()))
                .first();
        if (info == null) {
            throw new ComparisonException("The " + handle.side() + " collection " + handle.ref() + " does not exist");
        }
        return info;
    }

    private static boolean hasUsableIndex(CollectionHandle handle, String keyField) {
        for (BsonDocument index : handle.collection().listIndexes(BsonDocument.class)) {
            BsonDocument key = index.getDocument("key", new BsonDocument());
            if (!key.isEmpty() && key.getFirstKey().equals(keyField) && collationLocale(index) == null) {
                return true;
            }
        }
        return false;
    }

    /** Locale of a non-simple collation in {@code holder.collation}, {@code null} for none or simple. */
    private static @Nullable String collationLocale(BsonDocument holder) {
        BsonValue collation = holder.get("collation");
        if (collation == null || !collation.isDocument()) {
            return null;
        }
        String locale = collation.asDocument().getString("locale", new BsonString("simple")).getValue();
        return locale.equals("simple") ? null : locale;
    }
}
