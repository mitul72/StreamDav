package com.example.streamdav.dav;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

final class Uris {
    private static final String LEGAL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#[]@!$&'()*+,;=%";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Uris() {
    }

    /** Parses a URI, percent-encoding characters that aren't legal in one. Some servers send raw spaces in hrefs. */
    static URI lenient(String text) throws URISyntaxException {
        try {
            return new URI(text);
        } catch (URISyntaxException e) {
            return new URI(encodeIllegal(text));
        }
    }

    private static String encodeIllegal(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (c < 0x80 && LEGAL.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
        }
        return out.toString();
    }

    static URI withTrailingSlash(URI uri) {
        String path = uri.getRawPath();
        if (path != null && path.endsWith("/")) {
            return uri;
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + (path == null ? "" : path) + "/");
    }

    /** The decoded last path segment, ignoring a trailing slash. */
    static String lastSegment(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            return "";
        }
        int end = path.endsWith("/") ? path.length() - 1 : path.length();
        return path.substring(path.lastIndexOf('/', end - 1) + 1, end);
    }

    /** The path without its trailing slash, so a folder compares equal however the server spells it. */
    static String withoutTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
