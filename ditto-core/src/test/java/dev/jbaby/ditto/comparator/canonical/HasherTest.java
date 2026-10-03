package dev.jbaby.ditto.comparator.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CArray;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CDocument;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CNull;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CNumber;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.CString;
import dev.jbaby.ditto.comparator.canonical.CanonicalValue.Field;

class HasherTest {

    private final CanonicalEncoder encoder = new CanonicalEncoder();
    private final Hasher hasher = new Hasher(encoder);

    @Test
    void hashIsSha256OfTheEncoding() throws Exception {
        var value = new CDocument(List.of(new Field("a", CNumber.of(1)), new Field("b", new CString("x"))));
        var expected = java.security.MessageDigest.getInstance("SHA-256").digest(encoder.encode(value));

        assertThat(hasher.hash(value).toHex()).isEqualTo(java.util.HexFormat.of().formatHex(expected));
        assertThat(hasher.hash(value)).isEqualTo(hasher.hash(value));
    }

    @Test
    void encodingIsStableAcrossReleases() {
        // pins the byte format: changing it invalidates hashes stored in historical reports
        var value = new CDocument(List.of(new Field("a", new CArray(List.of(CNumber.of(1), CNull.INSTANCE)))));

        assertThat(java.util.HexFormat.of().formatHex(encoder.encode(value)))
                .isEqualTo("0b00000001" + "0000000161" + "0c00000002" + "04" + "00000000" + "00000001" + "01" + "01");
    }

    @Test
    void lengthPrefixesKeepConcatenationsApart() {
        assertDifferent(
                new CArray(List.of(new CString("ab"))),
                new CArray(List.of(new CString("a"), new CString("b"))));
        assertDifferent(
                new CDocument(List.of(new Field("a", new CString("b.c")))),
                new CDocument(List.of(new Field("a.b", new CString("c")))));
        assertDifferent(CDocument.EMPTY, CArray.EMPTY);
        assertDifferent(CNull.INSTANCE, new CString(""));
    }

    private void assertDifferent(CanonicalValue a, CanonicalValue b) {
        assertThat(hasher.hash(a)).isNotEqualTo(hasher.hash(b));
    }
}
