package com.helenaramyan.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helenaramyan.idempotency.spring.testapp.PaymentsController;
import com.helenaramyan.idempotency.spring.testapp.TestApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(classes = TestApplication.class, properties = "idempotency.store-failures=true")
@AutoConfigureMockMvc
class StoreFailuresTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaymentsController controller;

    @Test
    void declinedResponseIsReplayedWhenStoringFailuresIsEnabled() throws Exception {
        controller.reset();
        controller.declineNext(1);
        MockHttpServletRequestBuilder request = post("/payments/declinable")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"currency\":\"EUR\"}");

        mvc.perform(request).andExpect(status().isPaymentRequired());
        mvc.perform(request)
                .andExpect(status().isPaymentRequired())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.error").value("insufficient_funds"));

        assertThat(controller.invocations()).isEqualTo(1);
    }
}
