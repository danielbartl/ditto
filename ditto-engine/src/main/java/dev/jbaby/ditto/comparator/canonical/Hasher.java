package dev.jbaby.ditto.comparator.canonical;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content hash: SHA-256 over the {@link CanonicalEncoder canonical encoding}, streamed into the digest without
 * materializing the encoding. Thread-safe.
 */
public final class Hasher {

    private final CanonicalEncoder encoder;
    private final MessageDigest prototype;

    public Hasher(CanonicalEncoder encoder) {
        this.encoder = encoder;
        try {
            this.prototype = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public ContentHash hash(CanonicalValue value) {
        MessageDigest digest = newDigest();
        encoder.encode(value, new ByteSink.Digest(digest));
        return new ContentHash(digest.digest());
    }

    private MessageDigest newDigest() {
        try {
            return (MessageDigest) prototype.clone();
        } catch (CloneNotSupportedException e) {
            try {
                return MessageDigest.getInstance(prototype.getAlgorithm());
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
