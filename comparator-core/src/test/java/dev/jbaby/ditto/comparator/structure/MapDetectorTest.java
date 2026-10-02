package dev.jbaby.ditto.comparator.structure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class MapDetectorTest {

    private final MapDetector detector = new MapDetector(20);
    private final Random random = new Random(1);

    @Test
    void detectsObjectsWithDynamicKeys() {
        List<BsonDocument> documents = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            documents.add(BsonDocument.parse("{_id: " + i
                    + ", name: 'n', meta: {a: 1, b: 2, c: 3}"                        // record: same fields
                    + ", attributes: {k" + random.nextInt(100) + ": 1, k" + random.nextInt(100) + ": 2}"
                    + ", items: [{sku: 'x', props: {p" + random.nextInt(50) + ": 1}}]"  // map inside array elements
                    + ", optional: {" + optionalFields(i) + "}}"));                    // record with optional fields
        }

        var detected = detector.detect(documents, List.of(), List.of(), List.of());

        assertThat(detected).extracting(MapDetector.DetectedMap::pattern)
                .containsExactly("attributes.*", "items[].props.*");
        assertThat(detected.getFirst().distinctKeys()).isGreaterThan(80);
        assertThat(detected.getFirst().averageKeys()).isBetween(1.9, 2.0);
        assertThat(detected.getFirst().documents()).isEqualTo(200);
    }

    @Test
    void findsMapsInsideMapsInALaterRound() {
        List<BsonDocument> documents = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            documents.add(BsonDocument.parse("{locales: {l" + random.nextInt(60) + ": {labels: {t"
                    + random.nextInt(60) + ": 'x', t" + random.nextInt(60) + ": 'y'}}}}"));
        }

        assertThat(detector.detect(documents, List.of(), List.of(), List.of()))
                .extracting(MapDetector.DetectedMap::pattern)
                .containsExactly("locales.*", "locales.*.labels.*");
    }

    @Test
    void respectsConfiguredAndIgnoredPaths() {
        List<BsonDocument> documents = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            documents.add(BsonDocument.parse("{a: {k" + i + ": 1}, b: {k" + i + ": 1}}"));
        }

        assertThat(detector.detect(documents, List.of("b"), List.of(), List.of("a.*"))).isEmpty();
        assertThat(detector.detect(documents.subList(0, 10), List.of(), List.of(), List.of()))
                .as("too few distinct keys in a small sample").isEmpty();
    }

    /** 25 possible fields, each document has 20 of them: many distinct names, but not a map. */
    private String optionalFields(int i) {
        List<String> fields = new ArrayList<>();
        for (int f = 0; f < 25; f++) {
            if ((f + i) % 5 != 0) {
                fields.add("f" + f + ": 1");
            }
        }
        return String.join(", ", fields);
    }
}
