package dev.jbaby.ditto.opensearch.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenSearch-specific settings ({@code ditto.opensearch.*}); everything else is configured with the shared
 * {@code ditto.*} properties.
 */
@ConfigurationProperties("ditto.opensearch")
public class OpenSearchComparatorProperties {

    /**
     * OpenSearch nodes, e.g. {@code http://localhost:9200}. Only used if the application has no
     * {@code OpenSearchClient} bean: ditto then creates one.
     */
    private List<String> uris = new ArrayList<>();

    /** User for basic authentication of the client ditto creates; none if not set. */
    private @Nullable String username;

    /** Password for basic authentication of the client ditto creates. */
    private @Nullable String password;

    /** How long OpenSearch keeps a scroll between two pages of a full read. */
    private Duration scrollKeepAlive = Duration.ofMinutes(5);

    public List<String> getUris() {
        return uris;
    }

    public void setUris(List<String> uris) {
        this.uris = uris;
    }

    public @Nullable String getUsername() {
        return username;
    }

    public void setUsername(@Nullable String username) {
        this.username = username;
    }

    public @Nullable String getPassword() {
        return password;
    }

    public void setPassword(@Nullable String password) {
        this.password = password;
    }

    public Duration getScrollKeepAlive() {
        return scrollKeepAlive;
    }

    public void setScrollKeepAlive(Duration scrollKeepAlive) {
        if (scrollKeepAlive.toSeconds() < 1) {
            throw new IllegalArgumentException("scroll-keep-alive must be at least one second, was "
                    + scrollKeepAlive);
        }
        this.scrollKeepAlive = scrollKeepAlive;
    }
}
