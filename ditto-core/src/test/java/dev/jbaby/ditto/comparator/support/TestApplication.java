package dev.jbaby.ditto.comparator.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Minimal host application for integration tests: just auto-configuration, like an application embedding the library.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestApplication {

    public static final String DATABASE = "comparator_it";

    public static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MongoContainer::connectionString);
        registry.add("spring.mongodb.database", () -> DATABASE);
    }
}
