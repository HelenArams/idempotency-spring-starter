package com.helenaramyan.idempotency.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DoubleSubmitTest {

    private static final String BODY = """
            {"amount": 2599, "currency": "EUR", "cardToken": "tok_visa_4242"}""";

    @Autowired
    MockMvc mvc;

    @Test
    void customerDoubleClickCreatesOnePayment() throws Exception {
        int before = paymentCount();

        String first = mvc.perform(post("/payments").header("Idempotency-Key", "order-1001-attempt")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/payments").header("Idempotency-Key", "order-1001-attempt")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(paymentCount()).isEqualTo(before + 1);
    }

    @Test
    void invalidPaymentIsNotCachedSoTheClientCanFixAndRetry() throws Exception {
        mvc.perform(post("/payments").header("Idempotency-Key", "order-2002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": -1, \"currency\": \"EUR\", \"cardToken\": \"tok\"}"))
                .andExpect(status().isBadRequest());

        // same key, different body: allowed because the 400 released the key
        mvc.perform(post("/payments").header("Idempotency-Key", "order-2002")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
    }

    private int paymentCount() throws Exception {
        String json = mvc.perform(get("/payments")).andReturn().getResponse().getContentAsString();
        return JsonPath.<Integer>read(json, "$.length()");
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Missing idempotency key"));
    }
}
