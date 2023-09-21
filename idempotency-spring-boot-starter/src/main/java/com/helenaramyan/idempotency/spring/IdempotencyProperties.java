package com.helenaramyan.idempotency.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under the {@code idempotency.*} prefix. */
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    public enum Store {
        /** Single-node in-memory store; the default. */
        MEMORY,
        /** Shared store on top of Spring Data Redis. */
        REDIS
    }

    /** Switches the whole feature on or off without touching the controllers. */
    private boolean enabled = true;

    /** Name of the request header carrying the key. */
    private String headerName = "Idempotency-Key";

    /** Default retention of completed responses, overridable per handler via {@code @Idempotent(ttl)}. */
    private Duration ttl = Duration.ofHours(24);

    /** Which store implementation to auto-configure when no {@code IdempotencyStore} bean is defined. */
    private Store store = Store.MEMORY;

    /** Prefix prepended to every key before it reaches the store, handy for shared Redis instances. */
    private String keyPrefix = "idempotency:";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
