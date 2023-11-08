package com.helenaramyan.idempotency.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The part of an HTTP response that is persisted for a completed idempotent request
 * and replayed verbatim on subsequent calls with the same key.
 *
 * <p>Instances are immutable; headers are copied into an insertion-ordered, read-only map
 * and the body is defensively copied on the way in and out.</p>
 */
public final class StoredResponse {

    private static final byte[] EMPTY = new byte[0];

    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    public StoredResponse(int status, Map<String, List<String>> headers, byte[] body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("HTTP status out of range: " + status);
        }
        this.status = status;
        this.headers = copyHeaders(headers);
        this.body = body == null || body.length == 0 ? EMPTY : body.clone();
    }

    public int status() {
        return status;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public byte[] body() {
        return body.length == 0 ? EMPTY : body.clone();
    }

    public int bodyLength() {
        return body.length;
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            if (name != null && values != null) {
                copy.put(name, List.copyOf(new ArrayList<>(values)));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StoredResponse other)) {
            return false;
        }
        return status == other.status
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(status, headers) + Arrays.hashCode(body);
    }

    @Override
    public String toString() {
        return "StoredResponse{status=" + status + ", headers=" + headers.keySet()
                + ", bodyLength=" + body.length + '}';
    }
}
