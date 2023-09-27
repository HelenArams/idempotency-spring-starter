package com.helenaramyan.idempotency.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisRecordCodecTest {

    private final RedisRecordCodec codec = new RedisRecordCodec();

    @Test
    void inFlightRecordRoundTripsAndOmitsResponse() {
        IdempotencyRecord record = IdempotencyRecord.inFlight("abc123");

        String json = codec.encode(record);

        assertThat(json).isEqualTo("{\"fingerprint\":\"abc123\",\"state\":\"IN_FLIGHT\"}");
        assertThat(codec.decode(json)).isEqualTo(record);
    }

    @Test
    void completedRecordRoundTripsWithBinaryBodyAndMultiValueHeaders() {
        byte[] body = {0, 1, 2, (byte) 0xFF, 10, 13, 34, 92};
        StoredResponse response = new StoredResponse(201,
                Map.of("Location", List.of("/payments/1"), "X-Trace", List.of("a", "b")), body);
        IdempotencyRecord record = IdempotencyRecord.completed("fp", response);

        IdempotencyRecord decoded = codec.decode(codec.encode(record));

        assertThat(decoded.state()).isEqualTo(IdempotencyRecord.State.COMPLETED);
        assertThat(decoded.fingerprint()).isEqualTo("fp");
        assertThat(decoded.response().status()).isEqualTo(201);
        assertThat(decoded.response().headers()).isEqualTo(response.headers());
        assertThat(decoded.response().body()).containsExactly(body);
    }

    @Test
    void fingerprintIsATopLevelFieldForTheLuaScript() {
        String json = codec.encode(IdempotencyRecord.completed("fp-1",
                new StoredResponse(200, Map.of(), "{}".getBytes(UTF_8))));

        assertThat(json).startsWith("{\"fingerprint\":\"fp-1\"");
    }

    @Test
    void toleratesUnknownFieldsFromNewerVersions() {
        IdempotencyRecord decoded = codec.decode(
                "{\"fingerprint\":\"fp\",\"state\":\"IN_FLIGHT\",\"owner\":\"node-7\"}");

        assertThat(decoded).isEqualTo(IdempotencyRecord.inFlight("fp"));
    }
}
