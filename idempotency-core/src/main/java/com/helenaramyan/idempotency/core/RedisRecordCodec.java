package com.helenaramyan.idempotency.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Serialises {@link IdempotencyRecord}s to a compact JSON document for Redis. The fingerprint is a
 * top-level field so the server-side compare-and-set in {@link RedisIdempotencyStore} can read it
 * with {@code cjson} without knowing anything else about the layout.
 */
final class RedisRecordCodec {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Envelope(String fingerprint, String state, ResponseEnvelope response) {
    }

    record ResponseEnvelope(int status, Map<String, List<String>> headers, String body) {
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    String encode(IdempotencyRecord record) {
        ResponseEnvelope response = record.response() == null ? null : new ResponseEnvelope(
                record.response().status(),
                record.response().headers(),
                Base64.getEncoder().encodeToString(record.response().body()));
        try {
            return mapper.writeValueAsString(new Envelope(record.fingerprint(), record.state().name(), response));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("cannot encode idempotency record", e);
        }
    }

    IdempotencyRecord decode(String json) {
        try {
            Envelope envelope = mapper.readValue(json, Envelope.class);
            IdempotencyRecord.State state = IdempotencyRecord.State.valueOf(envelope.state());
            if (state == IdempotencyRecord.State.IN_FLIGHT) {
                return IdempotencyRecord.inFlight(envelope.fingerprint());
            }
            ResponseEnvelope r = envelope.response();
            byte[] body = r.body() == null ? new byte[0] : Base64.getDecoder().decode(r.body());
            return IdempotencyRecord.completed(envelope.fingerprint(), new StoredResponse(r.status(), r.headers(), body));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("cannot decode idempotency record: " + json, e);
        }
    }
}
