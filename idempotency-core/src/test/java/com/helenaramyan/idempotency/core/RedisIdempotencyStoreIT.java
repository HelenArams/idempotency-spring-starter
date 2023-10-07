package com.helenaramyan.idempotency.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the store contract against a real Redis, plus the behaviour that only Redis can show:
 * server-side expiry and atomicity across independent client connections.
 */
@Testcontainers
class RedisIdempotencyStoreIT extends IdempotencyStoreContract {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate template;
    static RedisIdempotencyStore store;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        store = new RedisIdempotencyStore(template);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @Override
    protected IdempotencyStore store() {
        return store;
    }

    @Test
    void claimSetsTtlAtomicallyWithTheMarker() {
        String key = newKey();

        store.putIfAbsent(key, FP_A, Duration.ofSeconds(30));

        Long ttlSeconds = template.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isBetween(25L, 30L);
        assertThat(template.opsForValue().get(key)).startsWith("{\"fingerprint\":\"" + FP_A + "\"");
    }

    @Test
    void inFlightMarkerExpiresServerSide() throws InterruptedException {
        String key = newKey();
        store.putIfAbsent(key, FP_A, Duration.ofMillis(300));

        Thread.sleep(600);

        assertThat(store.get(key)).isEmpty();
        assertThat(store.putIfAbsent(key, FP_B, TTL)).isEmpty();
    }

    @Test
    void completeRestartsTheTtl() throws InterruptedException {
        String key = newKey();
        store.putIfAbsent(key, FP_A, Duration.ofMillis(400));

        store.complete(key, FP_A, sampleResponse(), Duration.ofSeconds(30));
        Thread.sleep(600);

        assertThat(store.get(key)).hasValueSatisfying(r -> assertThat(r.isCompleted()).isTrue());
        assertThat(template.getExpire(key, TimeUnit.SECONDS)).isBetween(25L, 30L);
    }

    @Test
    void completeAfterOurMarkerExpiredDoesNotStealANewerClaim() throws InterruptedException {
        String key = newKey();
        store.putIfAbsent(key, FP_A, Duration.ofMillis(200));
        Thread.sleep(400);
        assertThat(store.putIfAbsent(key, FP_B, TTL)).as("second request claims the expired key").isEmpty();

        store.complete(key, FP_A, sampleResponse(), TTL);

        assertThat(store.get(key)).hasValueSatisfying(record -> {
            assertThat(record.fingerprint()).isEqualTo(FP_B);
            assertThat(record.isInFlight()).isTrue();
        });
    }

    @Test
    void exactlyOneConcurrentClaimWinsAcrossConnections() throws Exception {
        String key = newKey();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<IdempotencyRecord>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return store.putIfAbsent(key, FP_A, TTL);
                }));
            }
            start.countDown();

            long winners = 0;
            for (Future<Optional<IdempotencyRecord>> f : results) {
                if (f.get(10, TimeUnit.SECONDS).isEmpty()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
