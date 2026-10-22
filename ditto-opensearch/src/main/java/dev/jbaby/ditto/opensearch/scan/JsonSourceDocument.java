package dev.jbaby.ditto.opensearch.scan;

import org.bson.BsonDocument;

import dev.jbaby.ditto.comparator.scan.SourceDocument;
import dev.jbaby.ditto.opensearch.client.Slice;
import dev.jbaby.ditto.opensearch.client.SourceParser;

/**
 * A document's {@code _source} as returned by OpenSearch, parsed only when needed. Byte-identical sources are
 * unchanged content.
 */
record JsonSourceDocument(SourceParser parser, Slice source) implements SourceDocument {

    @Override
    public BsonDocument decode() {
        return parser.parse(source);
    }

    @Override
    public boolean sameBytes(SourceDocument other) {
        return other instanceof JsonSourceDocument document && source.sameBytes(document.source());
    }
}
