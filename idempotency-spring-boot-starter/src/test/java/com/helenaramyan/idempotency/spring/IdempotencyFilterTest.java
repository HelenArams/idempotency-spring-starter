package com.helenaramyan.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helenaramyan.idempotency.core.IdempotencyStore;
import com.helenaramyan.idempotency.core.RequestFingerprint;
import com.helenaramyan.idempotency.spring.testapp.MutableClock;
import com.helenaramyan.idempotency.spring.testapp.PaymentsController;
import com.helenaramyan.idempotency.spring.testapp.TestApplication;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class IdempotencyFilterTest {

    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String PAYMENT = "{\"amount\":1000,\"currency\":\"EUR\"}";
    private static final String OTHER_PAYMENT = "{\"amount\":2000,\"currency\":\"EUR\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentsController controller;

    @Autowired
    IdempotencyStore store;

    @Autowired
    IdempotencyProperties properties;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void resetCounters() {
        controller.reset();
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static MockHttpServletRequestBuilder payment(String key, String body) {
        MockHttpServletRequestBuilder builder = post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return key == null ? builder : builder.header(KEY_HEADER, key);
    }

    @Nested
    class MissingOrInvalidKey {

        @Test
        void missingKeyIsRejectedWith400Problem() throws Exception {
            mvc.perform(payment(null, PAYMENT))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Missing idempotency key"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(KEY_HEADER)))
                    .andExpect(jsonPath("$.instance").value("/payments"));

            assertThat(controller.invocations()).isZero();
        }

        @Test
        void blankKeyCountsAsMissing() throws Exception {
            mvc.perform(payment("   ", PAYMENT)).andExpect(status().isBadRequest());

            assertThat(controller.invocations()).isZero();
        }

        @Test
        void overlongKeyIsRejected() throws Exception {
            mvc.perform(payment("k".repeat(IdempotencyFilter.MAX_KEY_LENGTH + 1), PAYMENT))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid idempotency key"));

            assertThat(controller.invocations()).isZero();
        }

        @Test
        void surroundingWhitespaceIsIgnored() throws Exception {
            String key = newKey();
            mvc.perform(payment(key, PAYMENT)).andExpect(status().isCreated());

            mvc.perform(payment("  " + key + " ", PAYMENT))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

            assertThat(controller.invocations()).isEqualTo(1);
        }
    }

    @Nested
    class FirstCallAndReplay {

        @Test
        void firstCallExecutesHandlerAndIsNotMarkedReplayed() throws Exception {
            mvc.perform(payment(newKey(), PAYMENT))
                    .andExpect(status().isCreated())
                    .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER))
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.amount").value(1000));

            assertThat(controller.invocations()).isEqualTo(1);
        }

        @Test
        void replayReturnsStoredStatusHeadersAndBodyWithoutInvokingHandler() throws Exception {
            String key = newKey();
            MvcResult first = mvc.perform(payment(key, PAYMENT)).andExpect(status().isCreated()).andReturn();
            String firstBody = first.getResponse().getContentAsString();
            String firstLocation = first.getResponse().getHeader("Location");

            MvcResult replay = mvc.perform(payment(key, PAYMENT))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                    .andExpect(header().string("Location", firstLocation))
                    .andExpect(header().string("X-Request-Id", first.getResponse().getHeader("X-Request-Id")))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
            assertThat(replay.getResponse().getContentLength()).isEqualTo(firstBody.getBytes(StandardCharsets.UTF_8).length);
            assertThat(controller.invocations()).isEqualTo(1);
        }

        @Test
        void differentKeysAreIndependent() throws Exception {
            MvcResult a = mvc.perform(payment(newKey(), PAYMENT)).andReturn();
            MvcResult b = mvc.perform(payment(newKey(), PAYMENT)).andReturn();

            assertThat(a.getResponse().getContentAsString()).isNotEqualTo(b.getResponse().getContentAsString());
            assertThat(controller.invocations()).isEqualTo(2);
        }

        @Test
        void storedResponseExpiresAfterHandlerTtl() throws Exception {
            String key = newKey();
            MockHttpServletRequestBuilder request = post("/payments/short-ttl")
                    .header(KEY_HEADER, key).contentType(MediaType.APPLICATION_JSON).content(PAYMENT);

            mvc.perform(request).andExpect(status().isOk());
            clock.advance(Duration.ofSeconds(1));
            mvc.perform(request).andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

            clock.advance(Duration.ofSeconds(2));
            mvc.perform(request).andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

            assertThat(controller.invocations()).isEqualTo(2);
        }

        @Test
        void storedResponseHonoursGlobalTtlWhenHandlerDoesNotOverride() throws Exception {
            String key = newKey();
            mvc.perform(payment(key, PAYMENT)).andExpect(status().isCreated());

            clock.advance(properties.getTtl().minusSeconds(1));
            mvc.perform(payment(key, PAYMENT)).andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

            clock.advance(Duration.ofSeconds(2));
            mvc.perform(payment(key, PAYMENT)).andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

            assertThat(controller.invocations()).isEqualTo(2);
        }
    }

    @Nested
    class ConflictsAndMismatches {

        @Test
        void sameKeyWithDifferentBodyIs422() throws Exception {
            String key = newKey();
            mvc.perform(payment(key, PAYMENT)).andExpect(status().isCreated());

            mvc.perform(payment(key, OTHER_PAYMENT))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Idempotency key reused"))
                    .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

            assertThat(controller.invocations()).isEqualTo(1);
        }

        @Test
        void sameKeyOnDifferentEndpointIs422() throws Exception {
            String key = newKey();
            mvc.perform(payment(key, PAYMENT)).andExpect(status().isCreated());

            mvc.perform(put("/payments/pay_1").header(KEY_HEADER, key)
                            .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                    .andExpect(status().isUnprocessableEntity());

            assertThat(controller.invocations()).isEqualTo(1);
        }

        @Test
        void sameKeyWithDifferentQueryStringIs422() throws Exception {
            String key = newKey();
            mvc.perform(payment(key, PAYMENT)).andExpect(status().isCreated());

            mvc.perform(post("/payments").queryParam("capture", "false").header(KEY_HEADER, key)
                            .contentType(MediaType.APPLICATION_JSON).content(PAYMENT))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void requestStillInFlightIs409() throws Exception {
            String key = newKey();
            String fingerprint = RequestFingerprint.of("POST", "/payments", PAYMENT.getBytes(StandardCharsets.UTF_8));
            store.putIfAbsent(properties.getKeyPrefix() + key, fingerprint, Duration.ofMinutes(1));

            mvc.perform(payment(key, PAYMENT))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Request in progress"));

            assertThat(controller.invocations()).isZero();
        }

        @Test
        void inFlightWithDifferentBodyIsStill422() throws Exception {
            String key = newKey();
            String fingerprint = RequestFingerprint.of("POST", "/payments", PAYMENT.getBytes(StandardCharsets.UTF_8));
            store.putIfAbsent(properties.getKeyPrefix() + key, fingerprint, Duration.ofMinutes(1));

            mvc.perform(payment(key, OTHER_PAYMENT)).andExpect(status().isUnprocessableEntity());
        }
    }

    @Nested
    class Failures {

        @Test
        void handlerExceptionReleasesTheKeySoTheClientCanRetry() throws Exception {
            String key = newKey();
            controller.failNext(1);
            MockHttpServletRequestBuilder request = post("/payments/flaky")
                    .header(KEY_HEADER, key).contentType(MediaType.APPLICATION_JSON).content(PAYMENT);

            assertThatThrownBy(() -> mvc.perform(request)).hasRootCauseMessage("acquirer connection reset");
            assertThat(store.get(properties.getKeyPrefix() + key)).as("no stuck in-flight marker").isEmpty();

            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));
            mvc.perform(request).andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

            assertThat(controller.invocations()).isEqualTo(2);
        }
    }

    @Nested
    class Scope {

        @Test
        void unannotatedEndpointIgnoresTheHeader() throws Exception {
            String key = newKey();
            MockHttpServletRequestBuilder request = post("/payments/unprotected")
                    .header(KEY_HEADER, key).contentType(MediaType.APPLICATION_JSON).content(PAYMENT);

            mvc.perform(request).andExpect(status().isOk());
            mvc.perform(request).andExpect(status().isOk()).andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

            assertThat(controller.invocations()).isEqualTo(2);
            assertThat(store.get(properties.getKeyPrefix() + key)).isEmpty();
        }

        @Test
        void optionalEndpointRunsUnprotectedWithoutKey() throws Exception {
            MockHttpServletRequestBuilder request = post("/payments/optional")
                    .contentType(MediaType.APPLICATION_JSON).content(PAYMENT);

            mvc.perform(request).andExpect(status().isOk());
            mvc.perform(request).andExpect(status().isOk());

            assertThat(controller.invocations()).isEqualTo(2);
        }

        @Test
        void optionalEndpointIsProtectedWhenKeyIsPresent() throws Exception {
            String key = newKey();
            MockHttpServletRequestBuilder request = post("/payments/optional")
                    .header(KEY_HEADER, key).contentType(MediaType.APPLICATION_JSON).content(PAYMENT);

            mvc.perform(request).andExpect(status().isOk());
            mvc.perform(request).andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

            assertThat(controller.invocations()).isEqualTo(1);
        }

        @Test
        void classLevelAnnotationProtectsEveryHandler() throws Exception {
            String key = newKey();
            MockHttpServletRequestBuilder request = post("/transfers")
                    .header(KEY_HEADER, key).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5}");

            mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
            String first = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String second = mvc.perform(request)
                    .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(second).isEqualTo(first);
        }

        @Test
        void unknownRoutesPassThroughUntouched() throws Exception {
            mvc.perform(post("/nowhere").header(KEY_HEADER, newKey())).andExpect(status().isNotFound());
        }
    }
}
