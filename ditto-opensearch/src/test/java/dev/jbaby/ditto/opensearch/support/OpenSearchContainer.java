package dev.jbaby.ditto.opensearch.support;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One single-node OpenSearch container (security off) shared by all integration tests of the module, started on
 * first use.
 */
public final class OpenSearchContainer {

    public static final String IMAGE = "opensearchproject/opensearch:3.9.0";

    private static GenericContainer<?> container;

    private OpenSearchContainer() {
    }

    /** {@code http://host:port} of the running container. */
    public static synchronized String uri() {
        if (container == null) {
            container = new GenericContainer<>(System.getProperty("ditto.opensearch.image", IMAGE))
                    .withEnv("discovery.type", "single-node")
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withExposedPorts(9200)
                    .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow&timeout=60s")
                            .forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));
            container.start();
        }
        return "http://" + container.getHost() + ":" + container.getMappedPort(9200);
    }
}
