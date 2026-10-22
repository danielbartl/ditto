package dev.jbaby.ditto.opensearch;

/**
 * OpenSearch could not be reached or answered a request with an error.
 */
public class OpenSearchAccessException extends RuntimeException {

    private final int status;

    /** @param status HTTP status of the error response, 0 if there was none (I/O error) */
    public OpenSearchAccessException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public OpenSearchAccessException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** HTTP status of the error response, 0 if there was none (I/O error). */
    public int status() {
        return status;
    }
}
