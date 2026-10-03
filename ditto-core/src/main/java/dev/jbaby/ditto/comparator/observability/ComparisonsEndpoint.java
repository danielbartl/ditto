package dev.jbaby.ditto.comparator.observability;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.report.ReportRepository;

/**
 * Actuator endpoint {@code comparisons}, read-only: {@code GET /actuator/comparisons} lists the most recent stored
 * reports, {@code GET /actuator/comparisons/{id}} returns one report. Needs persistence, and like every endpoint it
 * must be exposed explicitly ({@code management.endpoints.web.exposure.include=comparisons}).
 */
@Endpoint(id = "comparisons")
public class ComparisonsEndpoint {

    static final int LIMIT = 50;

    private final ReportRepository repository;

    public ComparisonsEndpoint(ReportRepository repository) {
        this.repository = repository;
    }

    /**
     * @param id            report id
     * @param finishedAt    end of the comparison
     * @param baseline      {@code db.collection}
     * @param candidate     {@code db.collection}
     * @param verdict       overall verdict
     * @param keySimilarity key similarity (point value)
     * @param unchangedRate unchanged rate (point value)
     */
    public record Summary(String id, Instant finishedAt, String baseline, String candidate, Level verdict,
                          @Nullable Double keySimilarity, @Nullable Double unchangedRate) {

        static Summary of(ComparisonReport report) {
            return new Summary(report.id(), report.run().finishedAt(), report.run().settings().baseline().toString(),
                    report.run().settings().candidate().toString(), report.verdict(),
                    report.keys().keySimilarity().value(), report.content().unchangedRate().value());
        }
    }

    /** The most recent reports, newest first. */
    @ReadOperation
    public List<Summary> recent() {
        return repository.findRecent(null, LIMIT).stream().map(Summary::of).toList();
    }

    /** One report; {@code null} (404) if unknown. */
    @ReadOperation
    public @Nullable ComparisonReport report(@Selector String id) {
        return repository.findById(id).orElse(null);
    }
}
