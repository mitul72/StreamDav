package com.example.streamdav.dav;

import com.example.streamdav.library.RemoteFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Lists WebDAV folders with PROPFIND, authenticating with HTTP Basic. */
public final class DavClient {
    private static final String PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
            """;

    private final URI root;
    private final String authorization;
    private final HttpClient http;

    /** An empty username means anonymous access. */
    public DavClient(URI root, String username, String password) {
        this.root = Uris.withTrailingSlash(root);
        this.authorization = username == null || username.isEmpty() ? null : "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder()
                // Plain HTTP/1.1: some WebDAV servers mishandle the h2c upgrade the client would otherwise attempt.
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public URI root() {
        return root;
    }

    public HttpClient httpClient() {
        return http;
    }

    /** The Authorization header value, if the client has credentials. */
    public Optional<String> authorization() {
        return Optional.ofNullable(authorization);
    }

    /** Lists the direct members of a folder, excluding the folder itself. */
    public List<RemoteFile> list(URI folder) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(folder)
                .timeout(Duration.ofSeconds(30))
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY));
        authorization().ifPresent(value -> request.header("Authorization", value));
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 207) {
                // Resolve against the final URI in case the server redirected us.
                return MultistatusParser.parse(body, response.uri());
            }
            throw new DavException(describe(status), status);
        }
    }

    private static String describe(int status) {
        return switch (status) {
            case 401 -> "The server rejected the username or password.";
            case 403 -> "You don't have permission to open this folder.";
            case 404 -> "This folder doesn't exist on the server.";
            case 405, 501 -> "The server doesn't support WebDAV at this address.";
            default -> "The server responded with HTTP " + status + ".";
        };
    }
}
