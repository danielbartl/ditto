package dev.jbaby.ditto.comparator.api;

/**
 * What to do when key values of different BSON type brackets (e.g. strings and ObjectIds) are found.
 */
public enum MixedKeyPolicy {
    /** Fail the comparison with a clear error. */
    REJECT,
    /** Compare anyway, using MongoDB's cross-type sort order. */
    COMPARE
}
