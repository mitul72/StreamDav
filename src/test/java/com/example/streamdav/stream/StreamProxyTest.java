package com.example.streamdav.stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamProxyTest {
    private static final String AUTHORIZATION = "Basic dXNlcjpwYXNz";
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");

    private final byte[] media = new byte[1000];
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer upstream;
    private StreamProxy proxy;
    private URI remote;

    /** A minimal media server: requires the credentials and honours single byte ranges. */
    @BeforeEach
    void start() throws IOException {
        for (int i = 0; i < media.length; i++) {
            media[i] = (byte) i;
        }
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/files/", exchange -> {
            try (exchange) {
                if (!AUTHORIZATION.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                int start = 0;
                int end = media.length - 1;
                int status = 200;
                exchange.getResponseHeaders().set("Content-Type", "video/mp4");
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null) {
                    Matcher matcher = RANGE.matcher(range);
                    assertTrue(matcher.matches());
                    start = Integer.parseInt(matcher.group(1));
                    end = matcher.group(2).isEmpty() ? end : Integer.parseInt(matcher.group(2));
                    status = 206;
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + media.length);
                }
                int length = end - start + 1;
                if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(length));
                    exchange.sendResponseHeaders(status, -1);
                } else {
                    exchange.sendResponseHeaders(status, length);
                    exchange.getResponseBody().write(media, start, length);
                }
            }
        });
        upstream.start();
        remote = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/files/My%20Clip.mp4");
        proxy = new StreamProxy();
    }

    @AfterEach
    void stop() {
        proxy.close();
        upstream.stop(0);
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void publishesAnUnguessableLoopbackUrlEndingInTheFileName() {
        URI url = proxy.publish(remote, client, Optional.of(AUTHORIZATION));

        assertEquals("127.0.0.1", url.getHost());
        assertTrue(url.getRawPath().endsWith("/My%20Clip.mp4"), url.toString());
        assertEquals(url, proxy.publish(remote, client, Optional.of(AUTHORIZATION)), "same file, same URL");
        assertNotEquals(url, proxy.publish(remote.resolve("Other.mp4"), client, Optional.of(AUTHORIZATION)));
    }

    @Test
    void streamsTheWholeFileWithCredentials() throws Exception {
        URI url = proxy.publish(remote, client, Optional.of(AUTHORIZATION));

        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(url));

        assertEquals(200, response.statusCode());
        assertArrayEquals(media, response.body());
        assertEquals("video/mp4", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(1000, response.headers().firstValueAsLong("Content-Length").orElseThrow());
    }

    @Test
    void passesRangeRequestsThroughForSeeking() throws Exception {
        URI url = proxy.publish(remote, client, Optional.of(AUTHORIZATION));

        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(url).header("Range", "bytes=100-199"));

        assertEquals(206, response.statusCode());
        assertArrayEquals(Arrays.copyOfRange(media, 100, 200), response.body());
        assertEquals("bytes 100-199/1000", response.headers().firstValue("Content-Range").orElseThrow());
        assertEquals("bytes", response.headers().firstValue("Accept-Ranges").orElseThrow());
    }

    @Test
    void answersHeadRequestsWithTheLength() throws Exception {
        URI url = proxy.publish(remote, client, Optional.of(AUTHORIZATION));

        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(url).HEAD());

        assertEquals(200, response.statusCode());
        assertEquals(1000, response.headers().firstValueAsLong("Content-Length").orElseThrow());
        assertEquals(0, response.body().length);
    }

    @Test
    void relaysUpstreamErrors() throws Exception {
        URI url = proxy.publish(remote, client, Optional.empty());

        assertEquals(401, send(HttpRequest.newBuilder(url)).statusCode());
    }

    @Test
    void rejectsUnknownTokensAndOtherMethods() throws Exception {
        URI url = proxy.publish(remote, client, Optional.of(AUTHORIZATION));
        URI guessed = URI.create(url.toString().replaceFirst("/stream/[^/]+/", "/stream/guess/"));

        assertEquals(404, send(HttpRequest.newBuilder(guessed)).statusCode());
        assertEquals(404, send(HttpRequest.newBuilder(url.resolve("/stream/"))).statusCode());
        assertEquals(405, send(HttpRequest.newBuilder(url).DELETE()).statusCode());
    }

    @Test
    void asksForUncompressedDataAndKeepsAnyEncodingTheServerUsesAnyway() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> acceptEncoding = new java.util.concurrent.atomic.AtomicReference<>();
        byte[] gzipped = {0x1f, (byte) 0x8b, 8, 0};
        upstream.createContext("/gzip/", exchange -> {
            try (exchange) {
                acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, gzipped.length);
                exchange.getResponseBody().write(gzipped);
            }
        });
        URI url = proxy.publish(remote.resolve("/gzip/clip.mp4"), client, Optional.empty());

        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(url));

        assertEquals("identity", acceptEncoding.get());
        assertEquals("gzip", response.headers().firstValue("Content-Encoding").orElseThrow());
        assertArrayEquals(gzipped, response.body());
    }

    @Test
    void reportsUnreachableServersAsBadGateway() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        URI url = proxy.publish(URI.create("http://127.0.0.1:" + unusedPort + "/gone.mp4"), client, Optional.empty());

        assertEquals(502, send(HttpRequest.newBuilder(url)).statusCode());
    }
}
