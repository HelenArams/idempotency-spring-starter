package com.helenaramyan.idempotency.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single-node {@link IdempotencyStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>Entries expire lazily: an expired entry is treated as absent by every operation and is
 * physically removed when it is next touched or when {@link #evictExpired()} runs. The clock is
 * injectable so expiry can be tested without sleeping.</p>
 *
 * <p>Suitable for tests, local development and single-instance deployments. Anything running more
 * than one replica should use a shared store such as {@code RedisIdempotencyStore}.</p>
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Entry(IdempotencyRecord record, Instant expiresAt) {
        boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<IdempotencyRecord> putIfAbsent(String key, String fingerprint, Duration ttl) {
        requireKey(key);
        Objects.requireNonNull(fingerprint, "fingerprint");
        Instant now = clock.instant();
        Entry fresh = new Entry(IdempotencyRecord.inFlight(fingerprint), expiry(now, ttl));

        // NOTE: compute() locks the bin, so check-and-claim is atomic per key
        Entry result = entries.compute(key, (k, existing) ->
                existing == null || existing.isExpiredAt(now) ? fresh : existing);

        return result == fresh ? Optional.empty() : Optional.of(result.record());
    }

    @Override
    public void complete(String key, String fingerprint, StoredResponse response, Duration ttl) {
        requireKey(key);
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(response, "response");
        Instant now = clock.instant();

        entries.computeIfPresent(key, (k, existing) -> {
            if (existing.isExpiredAt(now)) {
                return null;
            }
            if (!existing.record().matches(fingerprint)) {
                return existing;
            }
            return new Entry(IdempotencyRecord.completed(fingerprint, response), expiry(now, ttl));
        });
    }

    @Override
    public Optional<IdempotencyRecord> get(String key) {
        requireKey(key);
        Instant now = clock.instant();
        Entry entry = entries.computeIfPresent(key, (k, existing) -> existing.isExpiredAt(now) ? null : existing);
        return Optional.ofNullable(entry).map(Entry::record);
    }

    @Override
    public void remove(String key) {
        requireKey(key);
        entries.remove(key);
    }

    /** Drops every expired entry. Optional housekeeping; correctness does not depend on it. */
    public int evictExpired() {
        Instant now = clock.instant();
        int before = entries.size();
        entries.entrySet().removeIf(e -> e.getValue().isExpiredAt(now));
        return before - entries.size();
    }

    // includes expired-but-not-yet-evicted entries
    public int size() {
        return entries.size();
    }

    private static Instant expiry(Instant now, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        return now.plus(ttl);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
