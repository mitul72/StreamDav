package com.example.streamdav.dav;

import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.stream.StreamProxy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebDavLibraryTest {
    private static final byte[] CLIP = "pretend this is video".getBytes(StandardCharsets.UTF_8);
    private static final String AUTHORIZATION =
            "Basic " + Base64.getEncoder().encodeToString("mitul:secret".getBytes(StandardCharsets.UTF_8));

    private HttpServer server;
    private StreamProxy proxy;
    private URI root;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/dav/", exchange -> {
            try (exchange) {
                if (!AUTHORIZATION.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                } else if (exchange.getRequestMethod().equals("PROPFIND")) {
                    byte[] listing = """
                            <d:multistatus xmlns:d="DAV:">
                              <d:response><d:href>/dav/</d:href></d:response>
                              <d:response><d:href>/dav/Clip%201.mp4</d:href></d:response>
                            </d:multistatus>
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(207, listing.length);
                    exchange.getResponseBody().write(listing);
                } else {
                    exchange.sendResponseHeaders(200, CLIP.length);
                    exchange.getResponseBody().write(CLIP);
                }
            }
        });
        server.start();
        root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/dav/");
        proxy = new StreamProxy();
    }

    @AfterEach
    void stop() {
        proxy.close();
        server.stop(0);
    }

    @Test
    void listsAndStreamsThroughTheProxyWithoutExposingCredentials() throws Exception {
        WebDavLibrary library = new WebDavLibrary(new DavClient(root, "mitul", "secret"), proxy);

        List<RemoteFile> files = library.list(library.root());
        assertEquals(List.of("Clip 1.mp4"), files.stream().map(RemoteFile::name).toList());

        URI streamUrl = library.streamUrl(files.getFirst());
        assertEquals("127.0.0.1", streamUrl.getHost());
        assertNull(streamUrl.getUserInfo(), "the player URL carries no credentials");
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(streamUrl).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertArrayEquals(CLIP, response.body());
    }

    @Test
    void closingReleasesTheStreamUrls() throws Exception {
        WebDavLibrary library = new WebDavLibrary(new DavClient(root, "mitul", "secret"), proxy);
        URI streamUrl = library.streamUrl(library.list(library.root()).getFirst());

        library.close();

        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(streamUrl).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(404, response.statusCode(), "the proxy no longer serves a closed library's files");
        assertThrows(IOException.class, () -> library.list(library.root()), "and its connections are shut");
    }
}
