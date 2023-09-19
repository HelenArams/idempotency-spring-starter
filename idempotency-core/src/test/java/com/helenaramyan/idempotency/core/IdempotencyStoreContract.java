package com.helenaramyan.idempotency.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link IdempotencyStore} must exhibit, independent of how it persists data.
 * Concrete store tests extend this and add implementation-specific cases (expiry, encoding...).
 */
abstract class IdempotencyStoreContract {

    static final Duration TTL = Duration.ofMinutes(5);
    static final String FP_A = RequestFingerprint.of("POST", "/payments", "{\"amount\":100}".getBytes(UTF_8));
    static final String FP_B = RequestFingerprint.of("POST", "/payments", "{\"amount\":999}".getBytes(UTF_8));

    protected abstract IdempotencyStore store();

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    protected static StoredResponse sampleResponse() {
        return new StoredResponse(201,
                Map.of("Content-Type", List.of("application/json"), "Location", List.of("/payments/42")),
                "{\"id\":\"42\",\"status\":\"AUTHORISED\"}".getBytes(UTF_8));
    }

    @Test
    void firstClaimSucceedsAndLeavesAnInFlightMarker() {
        String key = newKey();

        Optional<IdempotencyRecord> existing = store().putIfAbsent(key, FP_A, TTL);

        assertThat(existing).isEmpty();
        assertThat(store().get(key)).hasValueSatisfying(record -> {
            assertThat(record.isInFlight()).isTrue();
            assertThat(record.fingerprint()).isEqualTo(FP_A);
            assertThat(record.storedResponse()).isEmpty();
        });
    }

    @Test
    void secondClaimReturnsTheInFlightMarker() {
        String key = newKey();
        store().putIfAbsent(key, FP_A, TTL);

        Optional<IdempotencyRecord> existing = store().putIfAbsent(key, FP_A, TTL);

        assertThat(existing).hasValueSatisfying(record -> {
            assertThat(record.isInFlight()).isTrue();
            assertThat(record.matches(FP_A)).isTrue();
        });
    }

    @Test
    void claimWithDifferentFingerprintStillReturnsOriginalRecord() {
        String key = newKey();
        store().putIfAbsent(key, FP_A, TTL);

        Optional<IdempotencyRecord> existing = store().putIfAbsent(key, FP_B, TTL);

        assertThat(existing).hasValueSatisfying(record -> assertThat(record.matches(FP_B)).isFalse());
        assertThat(store().get(key)).hasValueSatisfying(record -> assertThat(record.fingerprint()).isEqualTo(FP_A));
    }

    @Test
    void completeStoresResponseForReplay() {
        String key = newKey();
        StoredResponse response = sampleResponse();
        store().putIfAbsent(key, FP_A, TTL);

        store().complete(key, FP_A, response, TTL);

        assertThat(store().get(key)).hasValueSatisfying(record -> {
            assertThat(record.isCompleted()).isTrue();
            assertThat(record.storedResponse()).contains(response);
        });
        assertThat(store().putIfAbsent(key, FP_A, TTL))
                .hasValueSatisfying(record -> assertThat(record.isCompleted()).isTrue());
    }

    @Test
    void completeWithForeignFingerprintDoesNotOverwrite() {
        String key = newKey();
        store().putIfAbsent(key, FP_A, TTL);

        store().complete(key, FP_B, sampleResponse(), TTL);

        assertThat(store().get(key)).hasValueSatisfying(record -> {
            assertThat(record.isInFlight()).isTrue();
            assertThat(record.fingerprint()).isEqualTo(FP_A);
        });
    }

    @Test
    void completeOnUnknownKeyIsIgnored() {
        String key = newKey();

        store().complete(key, FP_A, sampleResponse(), TTL);

        assertThat(store().get(key)).isEmpty();
    }

    @Test
    void removeReleasesTheKey() {
        String key = newKey();
        store().putIfAbsent(key, FP_A, TTL);

        store().remove(key);

        assertThat(store().get(key)).isEmpty();
        assertThat(store().putIfAbsent(key, FP_B, TTL)).isEmpty();
    }

    @Test
    void removingUnknownKeyIsNotAnError() {
        store().remove(newKey());
    }

    @Test
    void storedResponsePreservesStatusHeadersAndBinaryBody() {
        String key = newKey();
        byte[] binary = {0, 1, 2, (byte) 0xFF, (byte) 0xFE, 10, 13};
        StoredResponse response = new StoredResponse(200,
                Map.of("X-Trace", List.of("a", "b")), binary);
        store().putIfAbsent(key, FP_A, TTL);

        store().complete(key, FP_A, response, TTL);

        StoredResponse replayed = store().get(key).orElseThrow().storedResponse().orElseThrow();
        assertThat(replayed.status()).isEqualTo(200);
        assertThat(replayed.headers()).containsEntry("X-Trace", List.of("a", "b"));
        assertThat(replayed.body()).containsExactly(binary);
    }
}
