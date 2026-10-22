package dev.jbaby.ditto.comparator.canonical;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Target of the canonical encoding: a growable byte array or a message digest.
 */
interface ByteSink {

    void write(byte value);

    void write(byte[] bytes);

    default void writeInt(int value) {
        write((byte) (value >>> 24));
        write((byte) (value >>> 16));
        write((byte) (value >>> 8));
        write((byte) value);
    }

    default void writeLong(long value) {
        writeInt((int) (value >>> 32));
        writeInt((int) value);
    }

    /** Length-prefixed bytes, so concatenations stay unambiguous. */
    default void writeSized(byte[] bytes) {
        writeInt(bytes.length);
        write(bytes);
    }

    final class Buffer implements ByteSink {

        private byte[] bytes = new byte[64];
        private int size;

        @Override
        public void write(byte value) {
            ensure(1);
            bytes[size++] = value;
        }

        @Override
        public void write(byte[] values) {
            ensure(values.length);
            System.arraycopy(values, 0, bytes, size, values.length);
            size += values.length;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(bytes, size);
        }

        private void ensure(int extra) {
            if (size + extra > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + extra));
            }
        }
    }

    record Digest(MessageDigest digest) implements ByteSink {

        @Override
        public void write(byte value) {
            digest.update(value);
        }

        @Override
        public void write(byte[] values) {
            digest.update(values);
        }
    }
}
