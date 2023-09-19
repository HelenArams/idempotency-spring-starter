package com.helenaramyan.idempotency.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest extends IdempotencyStoreContract {

    private final MutableClock clock = MutableClock.at("2023-09-19T20:00:00Z");
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);

    @Override
    protected IdempotencyStore store() {
        return store;
    }

    @Test
    void inFlightMarkerExpiresAfterTtl() {
        String key = newKey();
        store.putIfAbsent(key, FP_A, Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(29));
        assertThat(store.get(key)).isPresent();

        clock.advance(Duration.ofSeconds(1));
        assertThat(store.get(key)).isEmpty();
        assertThat(store.putIfAbsent(key, FP_B, TTL)).as("expired key can be claimed again").isEmpty();
    }

    @Test
    void completedRecordExpiresAfterItsOwnTtl() {
        String key = newKey();
        store.putIfAbsent(key, FP_A, Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(9));

        store.complete(key, FP_A, sampleResponse(), Duration.ofHours(24));

        clock.advance(Duration.ofHours(23));
        assertThat(store.get(key)).hasValueSatisfying(r -> assertThat(r.isCompleted()).isTrue());

        clock.advance(Duration.ofHours(1));
        assertThat(store.get(key)).isEmpty();
    }

    @Test
    void completingAnExpiredClaimDoesNotResurrectIt() {
        String key = newKey();
        store.putIfAbsent(key, FP_A, Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));

        store.complete(key, FP_A, sampleResponse(), TTL);

        assertThat(store.get(key)).isEmpty();
    }

    @Test
    void evictExpiredDropsOnlyStaleEntries() {
        store.putIfAbsent("stale", FP_A, Duration.ofSeconds(1));
        store.putIfAbsent("fresh", FP_A, Duration.ofHours(1));
        clock.advance(Duration.ofSeconds(5));

        int evicted = store.evictExpired();

        assertThat(evicted).isEqualTo(1);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.get("fresh")).isPresent();
    }

    @Test
    void rejectsNonPositiveTtlAndBlankKeys() {
        assertThatIllegalArgumentException().isThrownBy(() -> store.putIfAbsent("k", FP_A, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> store.putIfAbsent("k", FP_A, Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> store.putIfAbsent(" ", FP_A, TTL));
        assertThatIllegalArgumentException().isThrownBy(() -> store.get(""));
    }

    @Test
    void exactlyOneConcurrentClaimWins() throws Exception {
        String key = newKey();
        int threads = 32;
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
                if (f.get(5, TimeUnit.SECONDS).isEmpty()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
