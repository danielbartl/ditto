package dev.jbaby.ditto.comparator.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.report.FileReportStore;
import dev.jbaby.ditto.comparator.report.ReportHtml;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;

/**
 * The store-neutral beans every comparator uses: the {@code ditto.*} properties, hashing, the verdict and the report
 * formats, and with {@code ditto.persistence.enabled=true} the {@link FileReportStore} (unless
 * {@code ditto.persistence.storage=database}). Every bean backs off if the application defines its own.
 */
@AutoConfiguration
@EnableConfigurationProperties(ComparatorProperties.class)
public class ComparatorEngineAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ComparatorEngineAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public Hasher comparatorHasher() {
        return new Hasher(new CanonicalEncoder());
    }

    @Bean
    @ConditionalOnMissingBean
    public VerdictEvaluator comparatorVerdictEvaluator() {
        return new VerdictEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportJson comparatorReportJson() {
        return new ReportJson();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportHtml comparatorReportHtml(ReportJson reportJson) {
        return new ReportHtml(reportJson);
    }

    /** Report files for every comparator, MongoDB and OpenSearch alike. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ditto.persistence", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "ditto.persistence", name = "storage", havingValue = "file", matchIfMissing = true)
    public FileReportStore comparatorFileReportStore(ComparatorProperties properties, ReportJson reportJson) {
        var persistence = properties.getPersistence();
        var store = new FileReportStore(persistence.getDirectory(), persistence.getRetention(), reportJson);
        if (persistence.getDatabase() != null
                || !persistence.getCollection().equals(new ComparatorProperties.Persistence().getCollection())) {
            // up to 0.5.0 reports went to the database, so this is likely an upgraded configuration
            log.warn("ditto.persistence.collection and .database have no effect: reports are stored as files in {}."
                    + " To store them in the database as before 0.6.0, set ditto.persistence.storage=database",
                    store.directory());
        }
        return store;
    }
}
