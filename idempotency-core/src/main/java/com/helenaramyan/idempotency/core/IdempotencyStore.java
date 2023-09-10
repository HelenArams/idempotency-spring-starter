package com.helenaramyan.idempotency.core;

import java.time.Duration;
import java.util.Optional;

/**
 * Storage for idempotency keys.
 *
 * <p>The life-cycle of a key is:</p>
 * <ol>
 *   <li>{@link #putIfAbsent} atomically claims the key with an in-flight marker, or returns the
 *       record already held under the key so the caller can decide between replay and conflict;</li>
 *   <li>{@link #complete} swaps the marker for the final response, which is then replayed to
 *       every subsequent request carrying the same key and fingerprint until the TTL elapses;</li>
 *   <li>{@link #remove} releases a key early, typically because the handler failed and the client
 *       should be allowed to retry with the same key.</li>
 * </ol>
 *
 * <p>Implementations must be safe for concurrent use. Keys are opaque strings that have already
 * been validated and, if configured, prefixed by the caller.</p>
 */
public interface IdempotencyStore {

    /**
     * Claims {@code key} for a request with the given fingerprint if nobody holds it yet.
     *
     * @param key         the idempotency key
     * @param fingerprint a digest of the request, see {@code RequestFingerprint}
     * @param ttl         how long the in-flight marker may live before it is considered abandoned
     * @return {@link Optional#empty()} if this call claimed the key, otherwise the record that
     *         already existed (in flight or completed)
     */
    Optional<IdempotencyRecord> putIfAbsent(String key, String fingerprint, Duration ttl);

    /**
     * Stores the response produced by the request that claimed {@code key} and (re)starts the TTL.
     * Must be a no-op if the key is not held with the same fingerprint, so a late writer cannot
     * overwrite a record claimed by a different request.
     */
    void complete(String key, String fingerprint, StoredResponse response, Duration ttl);

    /** The current record for {@code key}, or empty if unknown or expired. */
    Optional<IdempotencyRecord> get(String key);

    /** Releases {@code key} regardless of its state. Removing an unknown key is not an error. */
    void remove(String key);
}
