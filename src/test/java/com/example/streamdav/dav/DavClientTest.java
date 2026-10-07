package com.example.streamdav.dav;

import com.example.streamdav.library.RemoteFile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DavClientTest {
    private static final String LISTING = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/dav/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/Movies/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
              <d:response><d:href>/dav/clip.mp4</d:href>
                <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>
            """;

    private HttpServer server;
    private URI base;
    private final AtomicReference<HttpExchange> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/dav/", exchange -> {
            lastRequest.set(exchange);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 207, LISTING);
        });
        server.createContext("/old/", exchange -> {
            exchange.getResponseHeaders().set("Location", "/dav/");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/private/", exchange -> respond(exchange, 401, "Unauthorized"));
        server.createContext("/static/", exchange -> respond(exchange, 405, "Method Not Allowed"));
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void listsAFolderWithPropfind() throws Exception {
        DavClient client = new DavClient(base.resolve("/dav"), "mitul", "secret");
        assertEquals(base.resolve("/dav/"), client.root(), "the root is normalised to a folder URL");

        List<RemoteFile> files = client.list(client.root());

        assertEquals(List.of("Movies", "clip.mp4"), files.stream().map(RemoteFile::name).toList());
        assertEquals(42, files.get(1).size());
        HttpExchange request = lastRequest.get();
        assertEquals("PROPFIND", request.getRequestMethod());
        assertEquals("1", request.getRequestHeaders().getFirst("Depth"));
        String expected = "Basic " + Base64.getEncoder().encodeToString("mitul:secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, request.getRequestHeaders().getFirst("Authorization"));
        assertTrue(lastBody.get().contains("getcontentlength"));
    }

    @Test
    void anonymousAccessSendsNoCredentials() throws Exception {
        DavClient client = new DavClient(base.resolve("/dav/"), "", "");

        client.list(client.root());

        assertNull(lastRequest.get().getRequestHeaders().getFirst("Authorization"));
        assertTrue(client.authorization().isEmpty());
    }

    @Test
    void followsRedirectsAndResolvesAgainstTheFinalUrl() throws Exception {
        DavClient client = new DavClient(base.resolve("/old/"), "", "");

        List<RemoteFile> files = client.list(client.root());

        assertEquals("PROPFIND", lastRequest.get().getRequestMethod());
        assertEquals(base.resolve("/dav/Movies/"), files.getFirst().uri());
    }

    @Test
    void explainsRejectedCredentials() {
        DavClient client = new DavClient(base.resolve("/private/"), "mitul", "wrong");

        DavException error = assertThrows(DavException.class, () -> client.list(client.root()));

        assertEquals(401, error.statusCode());
        assertEquals("The server rejected the username or password.", error.getMessage());
    }

    @Test
    void explainsServersWithoutWebdav() {
        DavClient client = new DavClient(base.resolve("/static/"), "", "");

        DavException error = assertThrows(DavException.class, () -> client.list(client.root()));

        assertEquals(405, error.statusCode());
        assertEquals("The server doesn't support WebDAV at this address.", error.getMessage());
    }
}
