package dev.jbaby.ditto.opensearch.client;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A range of a byte array, e.g. one document's {@code _source} inside a search response. Not copied, so the array must
 * not change.
 */
public record Slice(byte[] bytes, int offset, int length) {

    public static final Slice EMPTY_OBJECT = of("{}".getBytes(StandardCharsets.UTF_8));

    public static Slice of(byte[] bytes) {
        return new Slice(bytes, 0, bytes.length);
    }

    /** Whether both ranges hold the same bytes. */
    public boolean sameBytes(Slice other) {
        return Arrays.equals(bytes, offset, offset + length, other.bytes, other.offset, other.offset + other.length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Slice slice && sameBytes(slice);
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (int i = offset; i < offset + length; i++) {
            hash = 31 * hash + bytes[i];
        }
        return hash;
    }

    @Override
    public String toString() {
        return new String(bytes, offset, length, StandardCharsets.UTF_8);
    }
}
