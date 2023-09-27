package com.helenaramyan.idempotency.spring.testapp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that only moves when a test tells it to. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    private MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public static MutableClock at(String isoInstant) {
        return new MutableClock(Instant.parse(isoInstant));
    }

    public void advance(Duration amount) {
        now.updateAndGet(t -> t.plus(amount));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
