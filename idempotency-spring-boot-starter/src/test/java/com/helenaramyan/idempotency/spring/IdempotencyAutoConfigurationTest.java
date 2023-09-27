package com.helenaramyan.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.helenaramyan.idempotency.core.IdempotencyStore;
import com.helenaramyan.idempotency.core.InMemoryIdempotencyStore;
import com.helenaramyan.idempotency.core.RedisIdempotencyStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class IdempotencyAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, IdempotencyAutoConfiguration.class));

    @Test
    void registersFilterAndInMemoryStoreByDefault() {
        webRunner.run(context -> {
            assertThat(context).hasSingleBean(IdempotencyFilter.class);
            assertThat(context).hasSingleBean(InMemoryIdempotencyStore.class);
            assertThat(context.getBean(FilterRegistrationBean.class).getOrder())
                    .isEqualTo(IdempotencyAutoConfiguration.FILTER_ORDER);

            IdempotencyProperties properties = context.getBean(IdempotencyProperties.class);
            assertThat(properties.getHeaderName()).isEqualTo("Idempotency-Key");
            assertThat(properties.getTtl()).isEqualTo(Duration.ofHours(24));
            assertThat(properties.getKeyPrefix()).isEqualTo("idempotency:");
            assertThat(properties.getStore()).isEqualTo(IdempotencyProperties.Store.MEMORY);
            assertThat(properties.isStoreFailures()).isFalse();
        });
    }

    @Test
    void bindsProperties() {
        webRunner.withPropertyValues(
                        "idempotency.header-name=X-Idempotency-Key",
                        "idempotency.ttl=12h",
                        "idempotency.key-prefix=payments:idem:",
                        "idempotency.store-failures=true")
                .run(context -> {
                    IdempotencyProperties properties = context.getBean(IdempotencyProperties.class);
                    assertThat(properties.getHeaderName()).isEqualTo("X-Idempotency-Key");
                    assertThat(properties.getTtl()).isEqualTo(Duration.ofHours(12));
                    assertThat(properties.getKeyPrefix()).isEqualTo("payments:idem:");
                    assertThat(properties.isStoreFailures()).isTrue();
                });
    }

    @Test
    void canBeDisabled() {
        webRunner.withPropertyValues("idempotency.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyFilter.class);
            assertThat(context).doesNotHaveBean(IdempotencyStore.class);
        });
    }

    @Test
    void backsOffWhenUserDefinesAStore() {
        webRunner.withUserConfiguration(CustomStoreConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context.getBean(IdempotencyStore.class)).isSameAs(CustomStoreConfiguration.STORE);
        });
    }

    @Test
    void usesRedisStoreWhenConfiguredAndTemplateIsAvailable() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, RedisAutoConfiguration.class,
                        IdempotencyAutoConfiguration.class))
                .withPropertyValues("idempotency.store=redis")
                .run(context -> {
                    assertThat(context).hasSingleBean(RedisIdempotencyStore.class);
                    assertThat(context).doesNotHaveBean(InMemoryIdempotencyStore.class);
                    assertThat(context).hasSingleBean(IdempotencyFilter.class);
                });
    }

    @Test
    void failsClearlyWhenRedisStoreIsRequestedWithoutRedis() {
        webRunner.withPropertyValues("idempotency.store=redis").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause()
                    .hasMessageContaining("idempotency.store=REDIS")
                    .hasMessageContaining("spring-boot-starter-data-redis");
        });
    }

    @Test
    void doesNothingOutsideServletWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyFilter.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomStoreConfiguration {

        static final IdempotencyStore STORE = new InMemoryIdempotencyStore();

        @Bean
        IdempotencyStore customStore() {
            return STORE;
        }
    }
}
