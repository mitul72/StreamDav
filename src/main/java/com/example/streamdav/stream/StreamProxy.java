package com.example.streamdav.stream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A loopback HTTP server that relays media from a remote server, adding its credentials.
 *
 * <p>Neither JavaFX Media nor external players can send our Authorization header, so they get an unguessable
 * {@code http://127.0.0.1} URL instead. Range requests pass through so players can seek.
 */
public final class StreamProxy implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(StreamProxy.class);
    private static final String CONTEXT = "/stream/";
    private static final Duration DEFAULT_HEADER_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(60);
    private static final List<String> REQUEST_HEADERS = List.of("Range", "If-Range");
    // Content-Encoding goes along with the bytes; dropping it would hand players compressed data as media.
    private static final List<String> RESPONSE_HEADERS =
            List.of("Content-Type", "Content-Encoding", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified");

    private record Target(URI uri, HttpClient client, Optional<String> authorization) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<URI, String> tokens = new ConcurrentHashMap<>();
    private final Map<String, Target> targets = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("stream-watchdog").factory());
    private final Duration headerTimeout;
    private final Duration idleTimeout;
    private final HttpServer server;

    public StreamProxy() throws IOException {
        this(DEFAULT_HEADER_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
    }

    /**
     * @param headerTimeout how long to wait for the server to start answering
     * @param idleTimeout   how long the server may send nothing while a player is waiting for data
     */
    StreamProxy(Duration headerTimeout, Duration idleTimeout) throws IOException {
        this.headerTimeout = headerTimeout;
        this.idleTimeout = idleTimeout;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext(CONTEXT, this::handle);
        server.start();
    }

    /**
     * Returns a local URL that streams {@code remote}, fetched with {@code client} and the given Authorization value.
     * The URL ends with the remote file name so players can recognise the format.
     */
    public URI publish(URI remote, HttpClient client, Optional<String> authorization) {
        String token = tokens.computeIfAbsent(remote, uri -> newToken());
        // Re-publishing (e.g. after reconnecting) replaces the client and credentials.
        targets.put(token, new Target(remote, client, authorization));
        InetSocketAddress address = server.getAddress();
        String host = address.getAddress() instanceof Inet6Address
                ? "[" + address.getAddress().getHostAddress() + "]"
                : address.getAddress().getHostAddress();
        String name = rawFileName(remote);
        return URI.create("http://" + host + ":" + address.getPort() + CONTEXT + token + "/" + (name.isEmpty() ? "media" : name));
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        watchdog.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Target target = targets.get(token(exchange.getRequestURI().getRawPath()));
            if (target == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            relay(exchange, target, method.equals("HEAD"));
        } catch (IOException e) {
            // Players routinely drop the connection mid-body when they seek or close.
            log.debug("Stream connection closed: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void relay(HttpExchange exchange, Target target, boolean head) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(target.uri())
                .timeout(headerTimeout)
                .method(head ? "HEAD" : "GET", HttpRequest.BodyPublishers.noBody());
        for (String name : REQUEST_HEADERS) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value != null) {
                request.header(name, value);
            }
        }
        // Ranges are byte offsets into the file, so ask for it as stored, not compressed.
        request.header("Accept-Encoding", "identity");
        target.authorization().ifPresent(value -> request.header("Authorization", value));

        HttpResponse<InputStream> response;
        try {
            response = target.client().send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            log.warn("{} didn't answer within {} s", target.uri(), headerTimeout.toSeconds());
            exchange.sendResponseHeaders(504, -1);
            return;
        } catch (IOException e) {
            log.warn("Could not reach {}: {}", target.uri(), e.toString());
            exchange.sendResponseHeaders(502, -1);
            return;
        }
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status >= 400) {
                log.warn("{} answered HTTP {}", target.uri(), status);
            }
            Headers headers = exchange.getResponseHeaders();
            for (String name : RESPONSE_HEADERS) {
                response.headers().firstValue(name).ifPresent(value -> headers.set(name, value));
            }
            if (status == 206) {
                headers.set("Accept-Ranges", "bytes");
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (head) {
                // For HEAD the server must not be given a length, so pass it through as a header.
                if (length >= 0) {
                    headers.set("Content-Length", Long.toString(length));
                }
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            // HttpServer's length argument: 0 means chunked, -1 means no body.
            exchange.sendResponseHeaders(status, length < 0 ? 0 : length == 0 ? -1 : length);
            copy(body, exchange.getResponseBody(), target.uri());
        }
    }

    /**
     * Copies the body, giving up if the server sends nothing for {@link #idleTimeout}. Only time spent waiting for
     * the server counts: a paused player that stops reading is backpressure, not a stall.
     */
    private void copy(InputStream body, OutputStream out, URI source) throws IOException {
        AtomicLong waitingSince = new AtomicLong(-1);
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread reader = Thread.currentThread();
        long period = Math.max(1, idleTimeout.toMillis() / 4);
        ScheduledFuture<?> check = watchdog.scheduleAtFixedRate(() -> {
            long since = waitingSince.get();
            if (since >= 0 && System.nanoTime() - since > idleTimeout.toNanos() && timedOut.compareAndSet(false, true)) {
                log.warn("{} stopped sending data; closing the stream", source);
                // Closing alone doesn't wake a read that's already blocked; interrupting this request's thread does.
                try {
                    body.close();
                } catch (IOException ignored) {
                    // The interrupt ends the read either way.
                }
                reader.interrupt();
            }
        }, period, period, TimeUnit.MILLISECONDS);
        try {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                waitingSince.set(System.nanoTime());
                int read = body.read(buffer);
                waitingSince.set(-1);
                if (read < 0) {
                    return;
                }
                out.write(buffer, 0, read);
                // Pass data on as it arrives; buffering it would starve a player while the server is slow.
                out.flush();
            }
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new IOException(source + " stopped sending data", e);
            }
            throw e;
        } finally {
            check.cancel(false);
            if (timedOut.get()) {
                Thread.interrupted(); // the stream is over; don't leak the interrupt into the response cleanup
            }
        }
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The token from {@code /stream/<token>/<file name>}. */
    private static String token(String rawPath) {
        String rest = rawPath.startsWith(CONTEXT) ? rawPath.substring(CONTEXT.length()) : "";
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String rawFileName(URI uri) {
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        int end = path.endsWith("/") ? path.length() - 1 : path.length();
        return path.substring(path.lastIndexOf('/', end - 1) + 1, end);
    }
}
