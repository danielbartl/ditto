package dev.jbaby.ditto.comparator.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import dev.jbaby.ditto.comparator.api.ComparisonReport;
import dev.jbaby.ditto.comparator.api.Labels;
import dev.jbaby.ditto.comparator.api.Level;
import dev.jbaby.ditto.comparator.report.ReportRepository;

/**
 * Actuator endpoint {@code comparisons}, read-only: {@code GET /actuator/comparisons} lists the most recent stored
 * reports, {@code GET /actuator/comparisons?label=batchJobId:4711} those with a label (several as
 * {@code label=a:1,b:2}), {@code GET /actuator/comparisons/{id}} returns one report. Needs persistence, and like every endpoint it
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
     * @param labels        the report's labels
     */
    public record Summary(String id, Instant finishedAt, String baseline, String candidate, Level verdict,
                          @Nullable Double keySimilarity, @Nullable Double unchangedRate,
                          Map<String, String> labels) {

        static Summary of(ComparisonReport report) {
            return new Summary(report.id(), report.run().finishedAt(), report.run().settings().baseline().toString(),
                    report.run().settings().candidate().toString(), report.verdict(),
                    report.keys().keySimilarity().value(), report.content().unchangedRate().value(), report.labels());
        }
    }

    /**
     * The most recent reports, newest first.
     *
     * @param label only reports with these labels, {@code key:value} pairs separated by commas; all if not given
     */
    @ReadOperation
    public List<Summary> recent(@Nullable String label) {
        List<ComparisonReport> reports;
        if (label == null || label.isBlank()) {
            reports = repository.findRecent(null, LIMIT);
        } else {
            Map<String, String> labels;
            try {
                labels = Labels.parse(label);
            } catch (IllegalArgumentException e) {
                throw new InvalidEndpointRequestException(e.getMessage(), e.getMessage());
            }
            reports = repository.findByLabels(labels, LIMIT);
        }
        return reports.stream().map(Summary::of).toList();
    }

    /** One report; {@code null} (404) if unknown. */
    @ReadOperation
    public @Nullable ComparisonReport report(@Selector String id) {
        return repository.findById(id).orElse(null);
    }
}
