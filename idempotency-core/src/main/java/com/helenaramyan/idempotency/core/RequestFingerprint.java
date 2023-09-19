package com.helenaramyan.idempotency.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Computes a stable SHA-256 fingerprint of an HTTP request so a store can tell whether a key is
 * being reused for the <em>same</em> request (a legitimate retry) or for a <em>different</em>
 * one (a client bug, or an attempt to smuggle a new payment under an old key).
 *
 * <p>The digest covers the upper-cased method, the request target (path plus query string, exactly
 * as received) and the raw body bytes. Each field is terminated by a NUL byte so that
 * {@code ("POST", "/a", "b")} and {@code ("POST", "/ab", "")} cannot collide. Headers are
 * deliberately excluded: tracing and auth headers legitimately change between retries.</p>
 */
public final class RequestFingerprint {

    private static final String ALGORITHM = "SHA-256";
    private static final byte SEPARATOR = 0;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RequestFingerprint() {
    }

    /**
     * @param method HTTP method, case-insensitive
     * @param target request path including the query string if any, e.g. {@code /payments?dry=1}
     * @param body   raw request body, may be {@code null} or empty
     * @return 64 lower-case hex characters
     */
    public static String of(String method, String target, byte[] body) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        MessageDigest digest = sha256();
        digest.update(method.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
        digest.update(SEPARATOR);
        digest.update(target.getBytes(StandardCharsets.UTF_8));
        digest.update(SEPARATOR);
        if (body != null && body.length > 0) {
            digest.update(body);
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Every compliant JVM ships SHA-256; this is unreachable in practice.
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
