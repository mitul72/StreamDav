package com.example.streamdav.metadata;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** JSON requests to a metadata API, waiting out rate limits and retrying brief server errors. */
final class ApiHttp {
    private static final Logger log = LogManager.getLogger(ApiHttp.class);
    private static final int ATTEMPTS = 4;
    private static final Duration MAX_WAIT = Duration.ofSeconds(90);

    private final HttpClient http;
    private final String service;
    private final Duration minInterval;
    private long nextRequestNanos;

    /**
     * @param service     the provider's name, for error messages
     * @param minInterval the least time between requests, to stay under a provider's rate limit
     */
    ApiHttp(HttpClient http, String service, Duration minInterval) {
        this.http = http;
        this.service = service;
        this.minInterval = minInterval;
    }

    static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    Json.Obj get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
        headers.forEach(request::header);
        return send(request);
    }

    Json.Obj post(URI uri, Map<String, String> headers, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return send(request);
    }

    private Json.Obj send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpRequest request = builder.timeout(Duration.ofSeconds(30)).header("Accept", "application/json").build();
        for (int attempt = 1; ; attempt++) {
            pace();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                try {
                    return Json.parseObject(response.body());
                } catch (IllegalArgumentException e) {
                    throw new MetadataException(service + " sent a response that couldn't be read.", 0);
                }
            }
            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt == ATTEMPTS) {
                throw new MetadataException(describe(status), status);
            }
            Duration wait = status == 429 ? retryAfter(response) : Duration.ofSeconds(attempt * 2L);
            log.info("{} answered HTTP {}; retrying in {} s", service, status, wait.toSeconds());
            Thread.sleep(wait);
        }
    }

    /** Spaces requests out; callers on several threads queue up behind each other. */
    private void pace() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long start = Math.max(now, nextRequestNanos);
            nextRequestNanos = start + minInterval.toNanos();
            wait = start - now;
        }
        if (wait > 0) {
            Thread.sleep(Duration.ofNanos(wait));
        }
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        long seconds = response.headers().firstValue("Retry-After").flatMap(ApiHttp::parseSeconds).orElse(10L);
        Duration wait = Duration.ofSeconds(Math.max(1, seconds));
        return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
    }

    private static Optional<Long> parseSeconds(String value) {
        try {
            return Optional.of(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String describe(int status) {
        return switch (status) {
            case 401 -> service + " rejected the API key.";
            case 403 -> service + " refused the request.";
            case 404 -> service + " doesn't have this title.";
            case 429 -> service + " is limiting requests; try again in a minute.";
            default -> service + " responded with HTTP " + status + ".";
        };
    }
}
