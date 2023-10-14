package com.helenaramyan.idempotency.example;

import com.helenaramyan.idempotency.spring.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentsController {

    public record CreatePayment(
            @Positive long amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank String cardToken) {
    }

    public record Payment(String id, long amount, String currency, String status, Instant createdAt) {
    }

    // pretend ledger, good enough to show that a retry doesn't create a second row
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    @PostMapping
    @Idempotent(ttl = "24h")
    public ResponseEntity<Payment> create(@Valid @RequestBody CreatePayment request) {
        Payment payment = new Payment("pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                request.amount(), request.currency(), "AUTHORISED", Instant.now());
        payments.put(payment.id(), payment);
        return ResponseEntity.created(URI.create("/payments/" + payment.id())).body(payment);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> get(@PathVariable String id) {
        Payment payment = payments.get(id);
        return payment == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(payment);
    }

    @GetMapping
    public Collection<Payment> list() {
        return payments.values();
    }
}
