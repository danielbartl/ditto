package dev.jbaby.ditto.opensearch.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Minimal Boot application for the integration tests: ditto creates its OpenSearch client from
 * {@code ditto.opensearch.uris}. MongoDB (on the test classpath for the parity test) stays off unless a test enables
 * it.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {

    public static final String WITHOUT_MONGO =
            "spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration";

    public static void openSearchProperties(DynamicPropertyRegistry registry) {
        registry.add("ditto.opensearch.uris", OpenSearchContainer::uri);
    }
}
