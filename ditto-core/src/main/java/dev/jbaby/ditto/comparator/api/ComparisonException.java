package dev.jbaby.ditto.comparator.api;

/**
 * The comparison could not be carried out or its result would be meaningless, e.g. because of mixed-type or
 * duplicate keys. Distinct from a RED verdict, which is a valid result.
 */
public class ComparisonException extends RuntimeException {

    public ComparisonException(String message) {
        super(message);
    }

    public ComparisonException(String message, Throwable cause) {
        super(message, cause);
    }
}
