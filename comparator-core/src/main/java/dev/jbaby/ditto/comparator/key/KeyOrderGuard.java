package dev.jbaby.ditto.comparator.key;

import org.bson.BsonValue;
import org.jspecify.annotations.Nullable;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.KeyRef;

/**
 * Verifies that the keys of one cursor arrive strictly ascending according to {@link BsonKeyOrder}. A violation means
 * a duplicate key (non-unique key field) or a mismatch between the server's sort order and ours; either would make the
 * merge-join silently wrong, so it aborts the comparison.
 */
public final class KeyOrderGuard {

    private final String side;
    private @Nullable BsonValue previous;

    public KeyOrderGuard(String side) {
        this.side = side;
    }

    public void accept(BsonValue key) {
        if (previous != null) {
            int order = BsonKeyOrder.INSTANCE.compare(previous, key);
            if (order == 0) {
                throw new ComparisonException("Duplicate key " + KeyRef.of(key).value() + " in " + side
                        + ": the key field must be unique");
            }
            if (order > 0) {
                throw new ComparisonException("Key order mismatch in " + side + ": " + KeyRef.of(key).value()
                        + " (" + key.getBsonType() + ") arrived after " + KeyRef.of(previous).value()
                        + " (" + previous.getBsonType() + "). The server's sort order differs from the comparator's;"
                        + " check the key types and collation.");
            }
        }
        previous = key;
    }
}
