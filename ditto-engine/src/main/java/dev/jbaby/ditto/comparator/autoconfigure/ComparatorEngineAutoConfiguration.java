package dev.jbaby.ditto.comparator.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import dev.jbaby.ditto.comparator.canonical.CanonicalEncoder;
import dev.jbaby.ditto.comparator.canonical.Hasher;
import dev.jbaby.ditto.comparator.report.ReportHtml;
import dev.jbaby.ditto.comparator.report.ReportJson;
import dev.jbaby.ditto.comparator.verdict.VerdictEvaluator;

/**
 * The store-neutral beans every comparator uses: the {@code ditto.*} properties, hashing, the verdict and the report
 * formats. Every bean backs off if the application defines its own.
 */
@AutoConfiguration
@EnableConfigurationProperties(ComparatorProperties.class)
public class ComparatorEngineAutoConfiguration {

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
}
