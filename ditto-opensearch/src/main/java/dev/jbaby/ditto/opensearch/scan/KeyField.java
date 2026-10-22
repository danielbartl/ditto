package dev.jbaby.ditto.opensearch.scan;

/**
 * How the documents of one index are identified.
 *
 * @param name        the key field as configured: {@code _id} or a field of the documents
 * @param lookupField the indexed field keys are read from (doc values) and looked up by: {@code _id}, the field itself
 *                    or its keyword subfield
 * @param kind        what the key field holds
 */
public record KeyField(String name, String lookupField, Kind kind) {

    public static final KeyField ID = new KeyField("_id", "_id", Kind.ID);

    public enum Kind {
        /** The document id, a string. */
        ID,
        /** A keyword field: keys are strings as indexed. */
        KEYWORD,
        /** A long, integer, short or byte field. */
        INTEGER
    }

    public boolean isId() {
        return kind == Kind.ID;
    }
}
