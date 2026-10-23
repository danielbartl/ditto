package dev.jbaby.ditto.comparator.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.MixedKeyPolicy;

class KeyInspectorTest {

    private final KeyInspector inspector = new KeyInspector();

    @Test
    void numericTypesShareABracket() {
        var baseline = new KeyInspector.KeyRange(new BsonInt32(1), new BsonInt64(100), false, false);
        var candidate = new KeyInspector.KeyRange(new BsonDouble(0.5), new BsonInt32(7), false, false);

        assertThat(inspector.check("_id", baseline, candidate, MixedKeyPolicy.REJECT)).isEmpty();
        assertThat(inspector.check("_id", KeyInspector.KeyRange.EMPTY, candidate, MixedKeyPolicy.REJECT)).isEmpty();
    }

    @Test
    void mixedBracketsAreRejectedOrWarned() {
        var baseline = new KeyInspector.KeyRange(new BsonString("a"), new BsonString("z"), false, false);
        var candidate = new KeyInspector.KeyRange(new BsonString("a"), new BsonObjectId(new ObjectId()), false, false);

        assertThatThrownBy(() -> inspector.check("_id", baseline, candidate, MixedKeyPolicy.REJECT))
                .isInstanceOf(ComparisonException.class)
                .hasMessageContaining("mixed types [STRING, OBJECT_ID]")
                .hasMessageContaining("ditto.mixed-key-types=COMPARE");
        assertThat(inspector.check("_id", baseline, candidate, MixedKeyPolicy.COMPARE))
                .singleElement().asString().contains("mixed types");
    }

    @Test
    void arrayAndMissingKeysAreAlwaysRejected() {
        var ok = new KeyInspector.KeyRange(new BsonInt32(1), new BsonInt32(2), false, false);

        assertThatThrownBy(() -> inspector.check("sku", ok,
                new KeyInspector.KeyRange(new BsonInt32(1), new BsonInt32(2), true, false), MixedKeyPolicy.COMPARE))
                .hasMessageContaining("holds arrays in the candidate");
        assertThatThrownBy(() -> inspector.check("sku",
                new KeyInspector.KeyRange(new BsonInt32(1), new BsonInt32(2), false, true), ok, MixedKeyPolicy.COMPARE))
                .hasMessageContaining("missing in some documents of the baseline");
    }

    @Test
    void guardRejectsDuplicatesAndDescendingKeys() {
        var guard = new KeyOrderGuard("baseline");
        guard.accept(new BsonInt32(1));
        guard.accept(new BsonInt32(2));

        assertThatThrownBy(() -> guard.accept(new BsonDouble(2.0))).hasMessageContaining("Duplicate key 2.0");
        var candidateGuard = new KeyOrderGuard("candidate");
        candidateGuard.accept(new BsonString("b"));
        assertThatThrownBy(() -> candidateGuard.accept(new BsonString("a")))
                .hasMessageContaining("Key order mismatch in candidate");
    }
}
