package com.helenaramyan.idempotency.spring;

import com.helenaramyan.idempotency.core.IdempotencyStore;
import com.helenaramyan.idempotency.core.InMemoryIdempotencyStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Wires the {@link IdempotencyFilter} into any servlet web application on the classpath.
 * Define your own {@link IdempotencyStore} bean to replace the auto-configured one.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "idempotency", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    /**
     * Late enough to run after Spring Security (so unauthenticated calls never touch the store) and
     * after request logging, but before the dispatcher.
     */
    public static final int FILTER_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    @ConditionalOnProperty(prefix = "idempotency", name = "store", havingValue = "memory", matchIfMissing = true)
    public InMemoryIdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyFilter idempotencyFilter(IdempotencyStore store, IdempotencyProperties properties,
            ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        return new IdempotencyFilter(store, properties, handlerMapping);
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("idempotencyFilter");
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
