package com.helenaramyan.idempotency.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helenaramyan.idempotency.spring.testapp.TestApplication;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApplication.class, properties = "idempotency.header-name=X-Request-Key")
@AutoConfigureMockMvc
class CustomHeaderNameTest {

    @Autowired
    MockMvc mvc;

    @Test
    void usesConfiguredHeaderName() throws Exception {
        String body = "{\"amount\":1,\"currency\":\"EUR\"}";

        mvc.perform(post("/payments").header("Idempotency-Key", "ignored")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("X-Request-Key")));

        mvc.perform(post("/payments").header("X-Request-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/payments").header("X-Request-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));
    }
}
