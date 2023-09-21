package com.helenaramyan.idempotency.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring MVC handler as idempotent: the caller must send an {@code Idempotency-Key}
 * header, and repeated calls with the same key (and the same request) receive the response that
 * was produced the first time instead of executing the handler again.
 *
 * <pre>{@code
 * @PostMapping("/payments")
 * @Idempotent(ttl = "24h")
 * public ResponseEntity<Payment> create(@RequestBody PaymentRequest request) { ... }
 * }</pre>
 *
 * <p>May also be placed on a controller class to cover every handler in it; a method-level
 * annotation takes precedence.</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * How long a completed response is kept for replay. Accepts ISO-8601 ({@code PT24H}) or the
     * simple style Spring Boot uses for properties ({@code 24h}, {@code 30m}, {@code 90s}).
     * Empty means "use {@code idempotency.ttl}".
     */
    String ttl() default "";

    /**
     * When {@code true}, requests without the key header are executed normally (unprotected) instead
     * of being rejected with {@code 400 Bad Request}. Useful while migrating clients.
     */
    boolean optional() default false;
}
