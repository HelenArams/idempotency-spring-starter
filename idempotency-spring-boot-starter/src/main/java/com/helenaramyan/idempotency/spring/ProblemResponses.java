package com.helenaramyan.idempotency.spring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Writes RFC 7807 problem documents straight to the servlet response. The filter runs before the
 * {@code DispatcherServlet}, so controller advice and message converters are not available here.
 */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String title, String detail) throws IOException {
        String json = "{\"type\":\"about:blank\""
                + ",\"title\":\"" + escape(title) + '"'
                + ",\"status\":" + status.value()
                + ",\"detail\":\"" + escape(detail) + '"'
                + ",\"instance\":\"" + escape(request.getRequestURI()) + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        response.resetBuffer();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
