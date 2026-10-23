package dev.jbaby.ditto.opensearch.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Body;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Requests;
import org.opensearch.client.opensearch.generic.Response;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.jbaby.ditto.opensearch.OpenSearchAccessException;

/**
 * JSON requests to OpenSearch through the application's {@link OpenSearchClient}, so whatever transport it uses
 * (Apache HttpClient, AWS SigV4, ...) applies. Response bodies come back as raw bytes, so documents can be read without
 * any mapping. Thread-safe.
 */
public final class OpenSearchHttp {

    private static final String JSON = "application/json";

    private final OpenSearchGenericClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenSearchHttp(OpenSearchClient client) {
        this.client = client.generic();
    }

    /**
     * A response.
     *
     * @param status HTTP status
     * @param body   response body, empty if there was none
     */
    public record Reply(int status, byte[] body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * Sends a request and returns the response, whatever its status.
     *
     * @param body JSON body, {@code null} for none
     * @throws OpenSearchAccessException on I/O errors
     */
    public Reply execute(String method, String endpoint, Map<String, String> parameters, @Nullable JsonNode body) {
        return execute(method, endpoint, parameters, body == null ? null : mapper.writeValueAsBytes(body), JSON);
    }

    /**
     * Sends a request with a body of any content type, e.g. {@code application/x-ndjson} for {@code _bulk}, and
     * returns the response, whatever its status.
     *
     * @param body request body, {@code null} for none
     * @throws OpenSearchAccessException on I/O errors
     */
    public Reply execute(String method, String endpoint, Map<String, String> parameters, byte @Nullable [] body,
                         String contentType) {
        var builder = Requests.builder().method(method).endpoint(endpoint).query(parameters);
        if (body != null) {
            builder.body(Body.from(body, contentType));
        }
        Request request = builder.build();
        try (Response response = client.execute(request)) {
            byte[] bytes = response.getBody().map(Body::bodyAsBytes).orElse(new byte[0]);
            return new Reply(response.getStatus(), bytes);
        } catch (IOException | RuntimeException e) {
            if (interrupted(e)) {
                // the transports clear the flag when they turn the InterruptedException into another exception
                Thread.currentThread().interrupt();
                throw new OpenSearchAccessException(method + " " + endpoint + " was interrupted", 0, e);
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new OpenSearchAccessException(method + " " + endpoint + " failed: " + e.getMessage(), 0, e);
        }
    }

    /**
     * Whether a request failed because its thread was interrupted. Timeouts are {@link InterruptedIOException}s too
     * ({@link java.net.SocketTimeoutException}, HttpClient's {@code ConnectTimeoutException}), but no interruption: the
     * caller's thread must not be marked interrupted for them.
     */
    static boolean interrupted(Exception e) {
        if (Thread.currentThread().isInterrupted() || e.getClass() == InterruptedIOException.class) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends a request that must succeed.
     *
     * @return the response body
     * @throws OpenSearchAccessException on I/O errors and error responses
     */
    public byte[] require(String method, String endpoint, Map<String, String> parameters, @Nullable JsonNode body) {
        Reply reply = execute(method, endpoint, parameters, body);
        if (!reply.ok()) {
            throw error(method, endpoint, reply);
        }
        return reply.body();
    }

    /** An exception for an error response, with OpenSearch's error type and reason. */
    public OpenSearchAccessException error(String method, String endpoint, Reply reply) {
        String detail;
        try {
            JsonNode error = mapper.readTree(reply.body()).path("error");
            detail = error.isObject()
                    ? error.path("type").asString("") + ": " + error.path("reason").asString("")
                    : error.asString("");
        } catch (JacksonException e) {
            detail = "";
        }
        return new OpenSearchAccessException("OpenSearch answered " + method + " " + endpoint + " with status "
                + reply.status() + (detail.isBlank() ? "" : " (" + detail + ")"), reply.status());
    }

    public JsonNode tree(byte[] json) {
        return mapper.readTree(json);
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
