package dev.jbaby.ditto.comparator.scan;

import org.bson.BsonDocument;

/**
 * A document as read from one side, in the store's own representation (raw BSON, JSON source, ...).
 */
public interface SourceDocument {

    /** The document decoded into a {@link BsonDocument}; decodes again on every call. */
    BsonDocument decode();

    /**
     * Whether both documents have byte-identical representations, which always means unchanged content. A cheap
     * shortcut before normalizing; {@code false} if it can't tell.
     */
    boolean sameBytes(SourceDocument other);
}
