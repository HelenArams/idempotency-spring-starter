package com.helenaramyan.idempotency.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * An {@link IdempotencyStore} shared across application instances through Redis.
 *
 * <p>Each key maps to a single string holding the JSON-encoded record, so claiming a key and
 * attaching its TTL is one atomic {@code SET key value NX PX ttl}. Completion is a server-side
 * compare-and-set: a short Lua script replaces the value only if the stored fingerprint still
 * matches, so a request whose in-flight marker expired cannot clobber a newer claim.</p>
 *
 * <p>Requires {@code spring-data-redis} and {@code jackson-databind} on the classpath.</p>
 */
public final class RedisIdempotencyStore implements IdempotencyStore {

    private static final int CLAIM_ATTEMPTS = 3;

    private static final RedisScript<Long> COMPLETE_IF_SAME_FINGERPRINT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
              return 0
            end
            if cjson.decode(current)['fingerprint'] ~= ARGV[1] then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RedisRecordCodec codec = new RedisRecordCodec();

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public Optional<IdempotencyRecord> putIfAbsent(String key, String fingerprint, Duration ttl) {
        requireKey(key);
        Objects.requireNonNull(fingerprint, "fingerprint");
        requirePositive(ttl);
        String marker = codec.encode(IdempotencyRecord.inFlight(fingerprint));

        // SET NX can lose to a concurrent writer whose key then expires before our GET; in that
        // narrow window the key is simply free again, so try to claim it once more.
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, marker, ttl))) {
                return Optional.empty();
            }
            String existing = redis.opsForValue().get(key);
            if (existing != null) {
                return Optional.of(codec.decode(existing));
            }
        }
        throw new IllegalStateException("could not claim or read idempotency key after " + CLAIM_ATTEMPTS + " attempts: " + key);
    }

    @Override
    public void complete(String key, String fingerprint, StoredResponse response, Duration ttl) {
        requireKey(key);
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(response, "response");
        requirePositive(ttl);
        String value = codec.encode(IdempotencyRecord.completed(fingerprint, response));
        redis.execute(COMPLETE_IF_SAME_FINGERPRINT, List.of(key), fingerprint, value, Long.toString(ttl.toMillis()));
    }

    @Override
    public Optional<IdempotencyRecord> get(String key) {
        requireKey(key);
        return Optional.ofNullable(redis.opsForValue().get(key)).map(codec::decode);
    }

    @Override
    public void remove(String key) {
        requireKey(key);
        redis.delete(key);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }

    private static void requirePositive(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
    }
}
