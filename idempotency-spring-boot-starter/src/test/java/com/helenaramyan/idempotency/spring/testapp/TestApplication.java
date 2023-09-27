package com.helenaramyan.idempotency.spring.testapp;

import com.helenaramyan.idempotency.core.IdempotencyStore;
import com.helenaramyan.idempotency.core.InMemoryIdempotencyStore;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TestApplication {

    @Bean
    public MutableClock mutableClock() {
        return MutableClock.at("2023-09-27T18:00:00Z");
    }

    @Bean
    public IdempotencyStore idempotencyStore(MutableClock clock) {
        return new InMemoryIdempotencyStore(clock);
    }
}
