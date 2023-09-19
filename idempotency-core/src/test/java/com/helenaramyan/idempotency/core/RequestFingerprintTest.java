package com.helenaramyan.idempotency.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private static final byte[] BODY = "{\"amount\":1000,\"currency\":\"EUR\"}".getBytes(UTF_8);

    @Test
    void isDeterministic() {
        String first = RequestFingerprint.of("POST", "/payments", BODY);
        String second = RequestFingerprint.of("POST", "/payments", BODY);

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void methodIsCaseInsensitive() {
        assertThat(RequestFingerprint.of("post", "/payments", BODY))
                .isEqualTo(RequestFingerprint.of("POST", "/payments", BODY));
    }

    @Test
    void changesWhenBodyChanges() {
        byte[] otherAmount = "{\"amount\":1001,\"currency\":\"EUR\"}".getBytes(UTF_8);

        assertThat(RequestFingerprint.of("POST", "/payments", BODY))
                .isNotEqualTo(RequestFingerprint.of("POST", "/payments", otherAmount));
    }

    @Test
    void changesWhenPathOrQueryChanges() {
        String base = RequestFingerprint.of("POST", "/payments", BODY);

        assertThat(RequestFingerprint.of("POST", "/refunds", BODY)).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("POST", "/payments?capture=false", BODY)).isNotEqualTo(base);
    }

    @Test
    void changesWhenMethodChanges() {
        assertThat(RequestFingerprint.of("PUT", "/payments/1", BODY))
                .isNotEqualTo(RequestFingerprint.of("PATCH", "/payments/1", BODY));
    }

    @Test
    void nullAndEmptyBodyAreEquivalent() {
        assertThat(RequestFingerprint.of("DELETE", "/payments/1", null))
                .isEqualTo(RequestFingerprint.of("DELETE", "/payments/1", new byte[0]));
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        // Without separators these would hash the same byte sequence "POST/ab".
        assertThat(RequestFingerprint.of("POST", "/a", "b".getBytes(UTF_8)))
                .isNotEqualTo(RequestFingerprint.of("POST", "/ab", new byte[0]));
    }

    @Test
    void matchesKnownVector() {
        // SHA-256("POST" 0x00 "/payments" 0x00 "{}") computed independently.
        assertThat(RequestFingerprint.of("POST", "/payments", "{}".getBytes(UTF_8)))
                .isEqualTo(sha256Hex("POST\u0000/payments\u0000{}"));
    }

    @Test
    void rejectsBlankMethodAndNullTarget() {
        assertThatIllegalArgumentException().isThrownBy(() -> RequestFingerprint.of(" ", "/x", null));
        assertThatIllegalArgumentException().isThrownBy(() -> RequestFingerprint.of("GET", null, null));
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
