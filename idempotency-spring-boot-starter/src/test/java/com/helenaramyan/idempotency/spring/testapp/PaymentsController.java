package com.helenaramyan.idempotency.spring.testapp;

import com.helenaramyan.idempotency.spring.Idempotent;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentsController {

    public record PaymentRequest(long amount, String currency) {
    }

    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();

    public int invocations() {
        return invocations.get();
    }

    public void reset() {
        invocations.set(0);
    }

    @PostMapping
    @Idempotent
    public ResponseEntity<Map<String, Object>> create(@RequestBody PaymentRequest request) {
        invocations.incrementAndGet();
        String id = "pay_" + sequence.incrementAndGet();
        return ResponseEntity.created(URI.create("/payments/" + id))
                .header("X-Request-Id", "req-" + id)
                .body(Map.of("id", id, "amount", request.amount(), "currency", request.currency()));
    }

    @PutMapping("/{id}")
    @Idempotent
    public Map<String, Object> update(@PathVariable String id, @RequestBody PaymentRequest request) {
        invocations.incrementAndGet();
        return Map.of("id", id, "amount", request.amount());
    }

    @PostMapping("/optional")
    @Idempotent(optional = true)
    public Map<String, Object> createOptional(@RequestBody PaymentRequest request) {
        invocations.incrementAndGet();
        return Map.of("id", "pay_" + sequence.incrementAndGet());
    }

    @PostMapping("/short-ttl")
    @Idempotent(ttl = "2s")
    public Map<String, Object> createShortLived(@RequestBody PaymentRequest request) {
        invocations.incrementAndGet();
        return Map.of("id", "pay_" + sequence.incrementAndGet());
    }

    @PostMapping("/unprotected")
    public Map<String, Object> createUnprotected(@RequestBody PaymentRequest request) {
        invocations.incrementAndGet();
        return Map.of("id", "pay_" + sequence.incrementAndGet());
    }
}
