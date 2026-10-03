package dev.jbaby.ditto.comparator.canonical;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.BigDecimal;

import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CArray;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CBinary;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CBoolean;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CDate;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CDocument;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CNonFinite;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CNull;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CNumber;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CObjectId;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.COther;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CString;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.Field;

/**
 * Deterministic byte encoding of {@link CanonicalValue}s: a one-byte type tag followed by a tag-specific payload,
 * with every variable-length part length-prefixed, so distinct values never share an encoding.
 * All numeric types share the {@code NUMBER} tag: type differences are left to the structure profile.
 */
public final class CanonicalEncoder {

    private static final byte NULL = 0x01;
    private static final byte FALSE = 0x02;
    private static final byte TRUE = 0x03;
    private static final byte NUMBER = 0x04;
    private static final byte NON_FINITE = 0x05;
    private static final byte STRING = 0x06;
    private static final byte DATE = 0x07;
    private static final byte OBJECT_ID = 0x08;
    private static final byte BINARY = 0x09;
    private static final byte OTHER = 0x0A;
    private static final byte DOCUMENT = 0x0B;
    private static final byte ARRAY = 0x0C;

    public byte[] encode(CanonicalValue value) {
        ByteSink.Buffer buffer = new ByteSink.Buffer();
        encode(value, buffer);
        return buffer.toByteArray();
    }

    void encode(CanonicalValue value, ByteSink sink) {
        switch (value) {
            case CNull cnull -> sink.write(NULL);
            case CBoolean(boolean bool) -> sink.write(bool ? TRUE : FALSE);
            case CNumber(BigDecimal number) -> {
                sink.write(NUMBER);
                sink.writeInt(number.scale());
                sink.writeSized(number.unscaledValue().toByteArray());
            }
            case CNonFinite(CNonFinite.Kind kind) -> {
                sink.write(NON_FINITE);
                sink.write((byte) kind.ordinal());
            }
            case CString(String string) -> {
                sink.write(STRING);
                sink.writeSized(string.getBytes(UTF_8));
            }
            case CDate(long millis) -> {
                sink.write(DATE);
                sink.writeLong(millis);
            }
            case CObjectId(var objectId) -> {
                sink.write(OBJECT_ID);
                sink.write(objectId.toByteArray());
            }
            case CBinary binary -> {
                sink.write(BINARY);
                sink.write(binary.subtype());
                sink.writeSized(binary.dataUnsafe());
            }
            case COther(String type, String representation) -> {
                sink.write(OTHER);
                sink.writeSized(type.getBytes(UTF_8));
                sink.writeSized(representation.getBytes(UTF_8));
            }
            case CDocument(var fields) -> {
                sink.write(DOCUMENT);
                sink.writeInt(fields.size());
                for (Field field : fields) {
                    sink.writeSized(field.name().getBytes(UTF_8));
                    encode(field.value(), sink);
                }
            }
            case CArray(var elements) -> {
                sink.write(ARRAY);
                sink.writeInt(elements.size());
                for (CanonicalValue element : elements) {
                    encode(element, sink);
                }
            }
        }
    }
}
