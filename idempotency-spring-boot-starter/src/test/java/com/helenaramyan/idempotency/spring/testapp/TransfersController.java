package com.helenaramyan.idempotency.spring.testapp;

import com.helenaramyan.idempotency.spring.Idempotent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Class-level annotation: every handler in here is protected. */
@RestController
@RequestMapping("/transfers")
@Idempotent
public class TransfersController {

    private final AtomicInteger sequence = new AtomicInteger();

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return Map.of("id", "trf_" + sequence.incrementAndGet());
    }
}
