package dev.jbaby.ditto.comparator.canonical;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * SHA-256 of a canonical encoding.
 */
public final class ContentHash {

    private final byte[] bytes;

    ContentHash(byte[] bytes) {
        this.bytes = bytes;
    }

    public String toHex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContentHash hash && Arrays.equals(bytes, hash.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }
}
