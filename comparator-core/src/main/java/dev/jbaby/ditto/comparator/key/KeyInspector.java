package dev.jbaby.ditto.comparator.key;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.jspecify.annotations.Nullable;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.KeyRef;
import dev.jbaby.ditto.comparator.api.MixedKeyPolicy;

/**
 * Checks before the merge-join that the key field can be merge-joined: no array or missing keys, and all keys in one
 * {@link TypeBracket} unless {@link MixedKeyPolicy#COMPARE}.
 * <p>
 * The bracket check costs two indexed queries per collection: MongoDB sorts by bracket first, so if the smallest and
 * the largest key share a bracket, every key in between does too.
 */
public final class KeyInspector {

    public static final Collation SIMPLE = Collation.builder().locale("simple").build();

    /**
     * Smallest and largest key of a collection plus disqualifying conditions.
     *
     * @param min            smallest key, {@code null} if the collection is empty
     * @param max            largest key, {@code null} if the collection is empty
     * @param hasArrayKeys   some document has an array as key
     * @param hasMissingKeys some document lacks the key field
     */
    public record KeyRange(@Nullable BsonValue min, @Nullable BsonValue max, boolean hasArrayKeys,
                           boolean hasMissingKeys) {

        static final KeyRange EMPTY = new KeyRange(null, null, false, false);

        Set<TypeBracket> brackets() {
            Set<TypeBracket> brackets = EnumSet.noneOf(TypeBracket.class);
            if (min != null) {
                brackets.add(TypeBracket.of(min.getBsonType()));
            }
            if (max != null) {
                brackets.add(TypeBracket.of(max.getBsonType()));
            }
            return brackets;
        }
    }

    public KeyRange inspect(MongoCollection<BsonDocument> collection, String keyField) {
        BsonValue min = boundary(collection, keyField, Sorts.ascending(keyField));
        if (min == null) {
            return KeyRange.EMPTY;
        }
        BsonValue max = boundary(collection, keyField, Sorts.descending(keyField));
        // _id always exists and can never be an array
        boolean custom = !keyField.equals("_id");
        boolean hasArrayKeys = custom && exists(collection, Filters.type(keyField, "array"));
        boolean hasMissingKeys = custom && exists(collection, Filters.exists(keyField, false));
        return new KeyRange(min, max, hasArrayKeys, hasMissingKeys);
    }

    /**
     * Validates the key ranges of both collections.
     *
     * @return warnings for the report
     * @throws ComparisonException if the keys cannot be merge-joined
     */
    public List<String> check(String keyField, KeyRange baseline, KeyRange candidate, MixedKeyPolicy policy) {
        for (var side : List.of(new Side("baseline", baseline), new Side("candidate", candidate))) {
            if (side.range.hasArrayKeys()) {
                throw new ComparisonException("Key field '" + keyField + "' holds arrays in the " + side.name
                        + " collection; arrays cannot be used as keys");
            }
            if (side.range.hasMissingKeys()) {
                throw new ComparisonException("Key field '" + keyField + "' is missing in some documents of the "
                        + side.name + " collection");
            }
        }
        Set<TypeBracket> brackets = EnumSet.noneOf(TypeBracket.class);
        brackets.addAll(baseline.brackets());
        brackets.addAll(candidate.brackets());
        if (brackets.size() <= 1) {
            return List.of();
        }
        String message = "Key field '" + keyField + "' has mixed types " + brackets + " (baseline "
                + describe(baseline) + ", candidate " + describe(candidate) + ")";
        if (policy == MixedKeyPolicy.REJECT) {
            throw new ComparisonException(message
                    + ". Set comparator.mixed-key-types=COMPARE to compare using MongoDB's cross-type order.");
        }
        List<String> warnings = new ArrayList<>();
        warnings.add(message + "; compared using MongoDB's cross-type order");
        return warnings;
    }

    private static @Nullable BsonValue boundary(MongoCollection<BsonDocument> collection, String keyField, Bson sort) {
        BsonDocument document = collection.find()
                .projection(Projections.include(keyField))
                .sort(sort)
                .collation(SIMPLE)
                .limit(1)
                .first();
        if (document == null) {
            return null;
        }
        // a missing key field sorts like null
        return document.containsKey(keyField) ? document.get(keyField) : org.bson.BsonNull.VALUE;
    }

    private static boolean exists(MongoCollection<BsonDocument> collection, Bson filter) {
        return collection.find(filter).projection(Projections.include("_id")).limit(1).first() != null;
    }

    private static String describe(KeyRange range) {
        if (range.min() == null || range.max() == null) {
            return "empty";
        }
        return "from " + KeyRef.of(range.min()).value() + " to " + KeyRef.of(range.max()).value();
    }

    private record Side(String name, KeyRange range) {
    }
}
