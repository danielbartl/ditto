package dev.jbaby.ditto.comparator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.api.CollectionRef;
import dev.jbaby.ditto.comparator.api.ComparisonFailedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonRequest;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;
import dev.jbaby.ditto.comparator.api.Store;
import dev.jbaby.ditto.comparator.autoconfigure.ComparatorProperties;
import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.report.ReportAssembler;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;

class ComparisonEngineTest {

    private final List<Object> events = new CopyOnWriteArrayList<>();

    @Test
    void aStoreThatCannotBeReachedStillPublishesTheFailure() {
        var unreachable = new IllegalStateException("cluster unreachable");
        var translated = new IllegalStateException("translated");
        DocumentStore store = new DocumentStore() {
            @Override
            public Store kind() {
                return Store.OPENSEARCH;
            }

            @Override
            public String defaultDatabase() {
                throw unreachable;
            }

            @Override
            public Sides open(ComparisonSettings settings) {
                throw new AssertionError("not reached");
            }

            @Override
            public RuntimeException translate(RuntimeException e, ComparisonSettings settings) {
                return e == unreachable ? translated : e;
            }
        };
        var engine = new ComparisonEngine(store, new ComparatorProperties(), new Hasher(new CanonicalEncoder()),
                new ReportAssembler(new VerdictEvaluator()), null, null, events::add);

        assertThatThrownBy(() -> engine.compare(ComparisonRequest.builder("products_backup", "products")
                .label("batchJobId", "4711").build(), progress -> { }))
                .isSameAs(translated);

        // the store could not say which cluster it is, so the names say which store
        assertThat(events).singleElement().isInstanceOfSatisfying(ComparisonFailedEvent.class, failed -> {
            assertThat(failed.exception()).isSameAs(translated);
            assertThat(failed.settings().baseline()).isEqualTo(CollectionRef.of("opensearch", "products_backup"));
            assertThat(failed.settings().candidate()).isEqualTo(CollectionRef.of("opensearch", "products"));
            assertThat(failed.settings().store()).isEqualTo(Store.OPENSEARCH);
            assertThat(failed.labels()).isEqualTo(Map.of("batchJobId", "4711"));
        });
    }
}
