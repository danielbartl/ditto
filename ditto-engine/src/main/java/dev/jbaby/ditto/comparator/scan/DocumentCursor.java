package dev.jbaby.ditto.comparator.scan;

import java.util.Iterator;

/**
 * Documents read one by one from a server-side cursor or scroll, which {@link #close()} releases.
 */
public interface DocumentCursor extends Iterator<KeyedDocument>, AutoCloseable {

    @Override
    void close();
}
