package dev.jbaby.ditto.comparator.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import org.bson.BsonDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import dev.jbaby.ditto.comparator.CollectionComparator;
import dev.jbaby.ditto.comparator.api.ComparisonCompletedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonException;
import dev.jbaby.ditto.comparator.api.ComparisonFailedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.api.ThresholdSource;
import dev.jbaby.ditto.comparator.api.Thresholds;
import dev.jbaby.ditto.comparator.observability.ComparisonsEndpoint;
import dev.jbaby.ditto.comparator.support.Collections;
import dev.jbaby.ditto.comparator.support.TestApplication;

/**
 * Thresholds learned from history, events, Micrometer metrics and the Actuator endpoint, in a host-like context.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "ditto.persistence.enabled=true",
        "ditto.persistence.collection=history_it_reports",
        "ditto.adaptive-thresholds.min-history=3",
        "management.endpoints.web.exposure.include=comparisons"})
@Import(HistoryAndObservabilityIT.Listeners.class)
class HistoryAndObservabilityIT {

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        TestApplication.mongoProperties(registry);
    }

    @TestConfiguration
    static class Listeners {

        final List<Object> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ComparisonCompletedEvent event) {
            events.add(event);
        }

        @EventListener
        void on(ComparisonFailedEvent event) {
            events.add(event);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    CollectionComparator comparator;

    @Autowired
    Listeners listeners;

    @Autowired
    MeterRegistry meters;

    @Autowired
    ComparisonsEndpoint endpoint;

    @BeforeEach
    void clean() {
        Collections.database(TestApplication.DATABASE).getCollection("history_it_reports").drop();
        listeners.events.clear();
    }

    @Test
    void learnsTheNormalChurnOfACollection() {
        // every run changes ~10% of the documents: YELLOW against the configured unchangedRate (GREEN >= 0.95)
        for (int run = 0; run < 3; run++) {
            createChurn(run, 10);
            ComparisonReport report = compare();
            assertThat(report.verdict()).isEqualTo(Level.YELLOW);
            assertThat(report.run().thresholdSource().kind()).isEqualTo(ThresholdSource.Kind.CONFIGURED);
        }

        createChurn(3, 10);
        ComparisonReport usual = compare();
        assertThat(usual.run().thresholdSource().kind()).isEqualTo(ThresholdSource.Kind.HISTORY);
        assertThat(usual.run().thresholdSource().historyRuns()).isEqualTo(3);
        assertThat(usual.run().settings().thresholds().unchangedRate().green()).isLessThan(0.9);
        assertThat(usual.verdict()).isEqualTo(Level.GREEN);

        createChurn(4, 2);  // half the documents change: unusual
        ComparisonReport unusual = compare();
        assertThat(unusual.verdict()).isEqualTo(Level.RED);

        // thresholds on the request win over history
        createChurn(5, 10);
        ComparisonReport explicit = comparator.compare(ComparisonRequest.builder("hist_base", "hist_cand")
                .thresholds(Thresholds.DEFAULTS).build());
        assertThat(explicit.run().thresholdSource().kind()).isEqualTo(ThresholdSource.Kind.REQUEST);
        assertThat(explicit.verdict()).isEqualTo(Level.YELLOW);
    }

    @Test
    void publishesEventsAndMetricsAndServesReports() {
        createChurn(0, 10);
        ComparisonReport report = compare();
        Collections.database(TestApplication.DATABASE).getCollection("missing_cand").drop();

        assertThatThrownBy(() -> comparator.compare(ComparisonRequest.of("hist_base", "missing_cand")))
                .isInstanceOf(ComparisonException.class);

        assertThat(listeners.events).hasSize(2);
        assertThat(listeners.events.get(0)).isEqualTo(new ComparisonCompletedEvent(report));
        assertThat(listeners.events.get(1)).isInstanceOfSatisfying(ComparisonFailedEvent.class,
                failed -> assertThat(failed.exception()).isInstanceOf(ComparisonException.class));

        String candidate = TestApplication.DATABASE + ".hist_cand";
        assertThat(meters.get("ditto.comparison").tag("candidate", candidate).tag("verdict", "YELLOW").timer()
                .count()).isEqualTo(1);
        assertThat(meters.get("ditto.comparison.verdict").tag("candidate", candidate).gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("ditto.comparison.unchanged.rate").tag("candidate", candidate).gauge().value())
                .isEqualTo(0.9);
        assertThat(meters.get("ditto.comparison.errors").tag("exception", "ComparisonException").counter().count())
                .isEqualTo(1);

        assertThat(endpoint.recent()).first().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(report.id());
            assertThat(summary.verdict()).isEqualTo(Level.YELLOW);
            assertThat(summary.candidate()).isEqualTo(candidate);
        });
        assertThat(endpoint.report(report.id())).isEqualTo(report);
        assertThat(endpoint.report("unknown")).isNull();
    }

    private ComparisonReport compare() {
        return comparator.compare(ComparisonRequest.of("hist_base", "hist_cand"));
    }

    /** 200 documents; every {@code everyNth} document changes its value in this run. */
    private static void createChurn(int run, int everyNth) {
        var db = Collections.database(TestApplication.DATABASE);
        Collections.create(db, "hist_base", IntStream.range(0, 200)
                .mapToObj(i -> BsonDocument.parse("{_id: " + i + ", v: " + run + "}")).toList());
        Collections.create(db, "hist_cand", IntStream.range(0, 200)
                .mapToObj(i -> BsonDocument.parse("{_id: " + i + ", v: " + (i % everyNth == 0 ? run + 1 : run) + "}"))
                .toList());
    }
}
