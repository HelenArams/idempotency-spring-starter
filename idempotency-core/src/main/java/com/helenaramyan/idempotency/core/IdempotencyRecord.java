package com.helenaramyan.idempotency.core;

import java.util.Objects;
import java.util.Optional;

/**
 * What a store knows about an idempotency key: the fingerprint of the request that first used it,
 * whether that request is still running, and (once finished) the response to replay.
 */
public record IdempotencyRecord(String fingerprint, State state, StoredResponse response) {

    public enum State {
        /** The first request with this key has been accepted but has not produced a response yet. */
        IN_FLIGHT,
        /** The first request finished and its response is available for replay. */
        COMPLETED
    }

    public IdempotencyRecord {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(state, "state");
        if (state == State.COMPLETED && response == null) {
            throw new IllegalArgumentException("a COMPLETED record must carry a response");
        }
        if (state == State.IN_FLIGHT && response != null) {
            throw new IllegalArgumentException("an IN_FLIGHT record cannot carry a response");
        }
    }

    public static IdempotencyRecord inFlight(String fingerprint) {
        return new IdempotencyRecord(fingerprint, State.IN_FLIGHT, null);
    }

    public static IdempotencyRecord completed(String fingerprint, StoredResponse response) {
        return new IdempotencyRecord(fingerprint, State.COMPLETED, Objects.requireNonNull(response, "response"));
    }

    public boolean isInFlight() {
        return state == State.IN_FLIGHT;
    }

    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    public boolean matches(String otherFingerprint) {
        return fingerprint.equals(otherFingerprint);
    }

    public Optional<StoredResponse> storedResponse() {
        return Optional.ofNullable(response);
    }
}
