package com.example.streamdav.metadata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A local HTTP server answering every request with a function of it, recording what was asked. */
final class StubApi implements AutoCloseable {

    record Request(String method, URI uri, String authorization, String body) {
    }

    record Response(int status, String body, String retryAfter) {
        static Response ok(String body) {
            return new Response(200, body, null);
        }
    }

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();

    StubApi(Function<Request, Response> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> handle(exchange, handler));
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private void handle(HttpExchange exchange, Function<Request, Response> handler) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI(),
                exchange.getRequestHeaders().getFirst("Authorization"), body);
        requests.add(request);
        Response response = handler.apply(request);
        if (response.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", response.retryAfter());
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
