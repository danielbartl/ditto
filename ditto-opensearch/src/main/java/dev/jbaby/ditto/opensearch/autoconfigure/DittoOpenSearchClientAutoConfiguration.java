package dev.jbaby.ditto.opensearch.autoconfigure;

import java.net.URI;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Creates an {@link OpenSearchClient} from {@code ditto.opensearch.uris} (plus {@code username}/{@code password}), if
 * the application has none, e.g. for the CLI. Applications that talk to OpenSearch already have a client (from
 * spring-data-opensearch or their own bean), which ditto then uses.
 */
@AutoConfiguration(afterName = {"org.opensearch.spring.boot.autoconfigure.OpenSearchClientAutoConfiguration",
        "org.opensearch.spring.boot.autoconfigure.OpenSearchRestClientAutoConfiguration"})
@ConditionalOnClass({OpenSearchClient.class, ApacheHttpClient5TransportBuilder.class})
@ConditionalOnMissingBean(OpenSearchClient.class)
@ConditionalOnProperty(prefix = "ditto.opensearch", name = "uris")
@EnableConfigurationProperties(OpenSearchComparatorProperties.class)
public class DittoOpenSearchClientAutoConfiguration {

    /** Closed with the application context. */
    @Bean
    public OpenSearchTransport dittoOpenSearchTransport(OpenSearchComparatorProperties properties) {
        HttpHost[] hosts = properties.getUris().stream().map(URI::create)
                .map(uri -> new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort()))
                .toArray(HttpHost[]::new);
        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(hosts)
                .setMapper(new JacksonJsonpMapper());
        String username = properties.getUsername();
        if (username != null) {
            String password = properties.getPassword();
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            for (HttpHost host : hosts) {
                credentials.setCredentials(new AuthScope(host), new UsernamePasswordCredentials(username,
                        password == null ? new char[0] : password.toCharArray()));
            }
            builder.setHttpClientConfigCallback(client -> client.setDefaultCredentialsProvider(credentials));
        }
        return builder.build();
    }

    @Bean
    public OpenSearchClient dittoOpenSearchClient(OpenSearchTransport dittoOpenSearchTransport) {
        return new OpenSearchClient(dittoOpenSearchTransport);
    }
}
