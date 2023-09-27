package com.helenaramyan.idempotency.spring;

import com.helenaramyan.idempotency.core.IdempotencyRecord;
import com.helenaramyan.idempotency.core.IdempotencyStore;
import com.helenaramyan.idempotency.core.RequestFingerprint;
import com.helenaramyan.idempotency.core.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Enforces idempotency-key semantics for handlers annotated with {@link Idempotent}:
 *
 * <ul>
 *   <li>no key on a protected endpoint: {@code 400 Bad Request} (unless the handler opted in to
 *       {@link Idempotent#optional()});</li>
 *   <li>first request with a key: the handler runs and its response is stored;</li>
 *   <li>same key, same request, while the first is still running: {@code 409 Conflict};</li>
 *   <li>same key, different method/path/body: {@code 422 Unprocessable Entity};</li>
 *   <li>same key, same request, first one finished: the stored status, headers and body are
 *       replayed with an {@code Idempotent-Replayed: true} header and the handler is not invoked.</li>
 * </ul>
 *
 * <p>Only successful (2xx) responses are stored unless {@code idempotency.store-failures} is on;
 * a failed attempt releases the key so the same request may be retried with the same key.</p>
 *
 * <p>Runs as a servlet filter rather than a {@code HandlerInterceptor} because the response body
 * has to be captured, which only a filter wrapping the response can do. The target handler is
 * resolved through the {@link RequestMappingHandlerMapping} so the annotation can be read before
 * the {@code DispatcherServlet} gets involved.</p>
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    static final int MAX_KEY_LENGTH = 255;

    /** Headers that describe the transport of one specific response and must not be replayed. */
    private static final Set<String> NON_REPLAYABLE_HEADERS = Set.of(
            "content-length", "transfer-encoding", "connection", "keep-alive", "set-cookie",
            REPLAYED_HEADER.toLowerCase(Locale.ROOT));

    private final Log log = LogFactory.getLog(getClass());

    private final IdempotencyStore store;
    private final IdempotencyProperties properties;
    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider;
    private final Map<Method, Duration> ttlCache = new ConcurrentHashMap<>();

    private volatile RequestMappingHandlerMapping handlerMapping;

    public IdempotencyFilter(IdempotencyStore store, IdempotencyProperties properties,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) {
        this.store = store;
        this.properties = properties;
        this.handlerMappingProvider = handlerMappingProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HandlerMethod handler = resolveHandler(request);
        Idempotent idempotent = handler != null ? findAnnotation(handler) : null;
        if (idempotent == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getHeader(properties.getHeaderName());
        if (key == null || key.isBlank()) {
            if (idempotent.optional()) {
                chain.doFilter(request, response);
                return;
            }
            ProblemResponses.write(request, response, HttpStatus.BAD_REQUEST, "Missing idempotency key",
                    "The " + properties.getHeaderName() + " header is required for this operation");
            return;
        }
        key = key.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            ProblemResponses.write(request, response, HttpStatus.BAD_REQUEST, "Invalid idempotency key",
                    "The " + properties.getHeaderName() + " header must be at most " + MAX_KEY_LENGTH + " characters");
            return;
        }

        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        String fingerprint = RequestFingerprint.of(request.getMethod(), requestTarget(request), body);
        String storeKey = properties.getKeyPrefix() + key;
        Duration ttl = ttlFor(handler.getMethod(), idempotent);

        Optional<IdempotencyRecord> existing = store.putIfAbsent(storeKey, fingerprint, ttl);
        if (existing.isPresent()) {
            handleExisting(existing.get(), fingerprint, request, response);
            return;
        }

        CachedBodyHttpServletRequest replayableRequest = new CachedBodyHttpServletRequest(request, body);
        CapturingResponseWrapper capturingResponse = new CapturingResponseWrapper(response);
        try {
            chain.doFilter(replayableRequest, capturingResponse);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            // The handler never produced a response, so there is nothing worth replaying: release
            // the key instead of leaving an in-flight marker that would turn every retry into a 409.
            store.remove(storeKey);
            throw e;
        }

        if (request.isAsyncStarted()) {
            // The response will be produced on another thread later on; we cannot capture it here.
            log.warn("Async handlers are not supported by @Idempotent; releasing key for " + requestTarget(request));
            store.remove(storeKey);
            capturingResponse.copyBodyToResponse();
            return;
        }

        if (isStorable(capturingResponse.getStatus())) {
            store.complete(storeKey, fingerprint, capture(capturingResponse), ttl);
        } else {
            store.remove(storeKey);
        }
        capturingResponse.copyBodyToResponse();
    }

    private boolean isStorable(int status) {
        return properties.isStoreFailures() || HttpStatus.Series.resolve(status) == HttpStatus.Series.SUCCESSFUL;
    }

    private void handleExisting(IdempotencyRecord record, String fingerprint, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        if (!record.matches(fingerprint)) {
            ProblemResponses.write(request, response, HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused",
                    "This " + properties.getHeaderName() + " was already used for a different request");
            return;
        }
        if (record.isInFlight()) {
            ProblemResponses.write(request, response, HttpStatus.CONFLICT, "Request in progress",
                    "A request with this " + properties.getHeaderName() + " is still being processed; retry shortly");
            return;
        }
        replay(record.response(), response);
    }

    private static void replay(StoredResponse stored, HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(stored.status());
        stored.headers().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setHeader(REPLAYED_HEADER, "true");
        byte[] body = stored.body();
        response.setContentLength(body.length);
        if (body.length > 0) {
            response.getOutputStream().write(body);
        }
        response.flushBuffer();
    }

    private static StoredResponse capture(CapturingResponseWrapper wrapper) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : wrapper.getHeaderNames()) {
            if (!NON_REPLAYABLE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.putIfAbsent(name, List.copyOf(wrapper.getHeaders(name)));
            }
        }
        String contentType = wrapper.getContentType();
        if (contentType != null && !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.put(HttpHeaders.CONTENT_TYPE, List.of(contentType));
        }
        return new StoredResponse(wrapper.getStatus(), headers, wrapper.getContentAsByteArray());
    }

    private Duration ttlFor(Method method, Idempotent idempotent) {
        if (idempotent.ttl().isBlank()) {
            return properties.getTtl();
        }
        return ttlCache.computeIfAbsent(method, m -> {
            Duration parsed = DurationStyle.detectAndParse(idempotent.ttl());
            if (parsed.isNegative() || parsed.isZero()) {
                throw new IllegalArgumentException("@Idempotent ttl must be positive on " + m);
            }
            return parsed;
        });
    }

    private static Idempotent findAnnotation(HandlerMethod handler) {
        Idempotent onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Idempotent.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), Idempotent.class);
    }

    private HandlerMethod resolveHandler(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = handlerMapping();
        if (mapping == null) {
            return null;
        }
        boolean parsedHere = false;
        try {
            if (!ServletRequestPathUtils.hasParsedRequestPath(request)) {
                ServletRequestPathUtils.parseAndCache(request);
                parsedHere = true;
            }
            HandlerExecutionChain chain = mapping.getHandler(request);
            return chain != null && chain.getHandler() instanceof HandlerMethod method ? method : null;
        } catch (Exception e) {
            log.debug("Could not resolve handler for " + requestTarget(request) + ": " + e.getMessage());
            return null;
        } finally {
            if (parsedHere) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }

    private RequestMappingHandlerMapping handlerMapping() {
        RequestMappingHandlerMapping mapping = this.handlerMapping;
        if (mapping == null) {
            mapping = handlerMappingProvider.getIfAvailable();
            this.handlerMapping = mapping;
        }
        return mapping;
    }

    private static String requestTarget(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + '?' + query;
    }
}
