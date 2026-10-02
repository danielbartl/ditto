package dev.jbaby.ditto.comparator.observability;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import dev.jbaby.ditto.comparator.api.ComparisonCompletedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonFailedEvent;
import dev.jbaby.ditto.comparator.api.ComparisonMode;
import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.ComparisonReport.RuleResult;
import dev.jbaby.ditto.comparator.api.ComparisonSettings;

/**
 * Records Micrometer metrics for every comparison, tagged with {@code baseline} and {@code candidate}
 * ({@code db.collection}):
 * <ul>
 * <li>{@code ditto.comparison} (timer): duration, additionally tagged with {@code mode} and {@code verdict}</li>
 * <li>{@code ditto.comparison.errors} (counter): failed comparisons, additionally tagged with {@code exception}</li>
 * <li>gauges with the values of the latest comparison: {@code ditto.comparison.verdict} (0 GREEN, 1 YELLOW, 2 RED),
 * {@code ditto.comparison.key.similarity}, {@code ditto.comparison.unchanged.rate},
 * {@code ditto.comparison.max.path.change.rate}; NaN where undefined</li>
 * </ul>
 * The registry is looked up on the first event, so the bean does not depend on the metrics configuration order.
 */
public final class ComparisonMetrics {

    /** Meter names, for tests and dashboards. */
    public static final List<String> GAUGES = List.of("ditto.comparison.verdict", "ditto.comparison.key.similarity",
            "ditto.comparison.unchanged.rate", "ditto.comparison.max.path.change.rate");

    private final ObjectProvider<MeterRegistry> registry;
    private final Map<Tags, AtomicReference<ComparisonReport>> latest = new ConcurrentHashMap<>();

    public ComparisonMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    @EventListener
    public void onCompleted(ComparisonCompletedEvent event) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) {
            return;
        }
        ComparisonReport report = event.report();
        Tags tags = tags(report.run().settings());
        Timer.builder("ditto.comparison")
                .description("Duration of collection comparisons")
                .tags(tags.and("mode", mode(report.run().settings().mode()), "verdict", report.verdict().name()))
                .register(meters)
                .record(Duration.ofMillis(report.run().durationMillis()));
        latest.computeIfAbsent(tags, key -> registerGauges(meters, key)).set(report);
    }

    @EventListener
    public void onFailed(ComparisonFailedEvent event) {
        MeterRegistry meters = registry.getIfAvailable();
        if (meters == null) {
            return;
        }
        Counter.builder("ditto.comparison.errors")
                .description("Comparisons that could not be carried out")
                .tags(tags(event.settings()).and("exception", event.exception().getClass().getSimpleName()))
                .register(meters)
                .increment();
    }

    private static AtomicReference<ComparisonReport> registerGauges(MeterRegistry meters, Tags tags) {
        AtomicReference<ComparisonReport> holder = new AtomicReference<>();
        gauge(meters, "ditto.comparison.verdict", "Verdict of the latest comparison: 0 GREEN, 1 YELLOW, 2 RED", tags,
                holder, report -> report.verdict().ordinal());
        gauge(meters, "ditto.comparison.key.similarity", "Key similarity of the latest comparison", tags, holder,
                report -> orNaN(report.keys().keySimilarity().value()));
        gauge(meters, "ditto.comparison.unchanged.rate", "Unchanged rate of the latest comparison", tags, holder,
                report -> orNaN(report.content().unchangedRate().value()));
        gauge(meters, "ditto.comparison.max.path.change.rate",
                "Highest change rate of an unexpected path in the latest comparison", tags, holder,
                report -> orNaN(observed(report, "maxPathChangeRate")));
        return holder;
    }

    private static void gauge(MeterRegistry meters, String name, String description, Tags tags,
                              AtomicReference<ComparisonReport> holder, ToDoubleFunction<ComparisonReport> value) {
        Gauge.builder(name, holder, h -> h.get() == null ? Double.NaN : value.applyAsDouble(h.get()))
                .description(description)
                .tags(tags)
                .strongReference(true)
                .register(meters);
    }

    private static @Nullable Double observed(ComparisonReport report, String rule) {
        return report.rules().stream().filter(result -> result.rule().equals(rule)).map(RuleResult::observed)
                .findFirst().orElse(null);
    }

    private static double orNaN(@Nullable Double value) {
        return value == null ? Double.NaN : value;
    }

    private static Tags tags(ComparisonSettings settings) {
        return Tags.of("baseline", settings.baseline().toString(), "candidate", settings.candidate().toString());
    }

    private static String mode(ComparisonMode mode) {
        return switch (mode) {
            case ComparisonMode.Full _ -> "FULL";
            case ComparisonMode.Sample _ -> "SAMPLE";
            case ComparisonMode.Auto _ -> "AUTO";
        };
    }
}
