package com.helenaramyan.idempotency.spring;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Thin alias over Spring's {@link ContentCachingResponseWrapper} so the filter's intent reads
 * clearly and the wrapper type can evolve without touching the filter.
 */
final class CapturingResponseWrapper extends ContentCachingResponseWrapper {

    CapturingResponseWrapper(HttpServletResponse response) {
        super(response);
    }
}
