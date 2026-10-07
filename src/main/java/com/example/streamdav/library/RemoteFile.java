package com.example.streamdav.library;

import java.net.URI;
import java.time.Instant;

/**
 * A file or folder on the media server.
 *
 * @param uri          absolute location; folders always end with a slash
 * @param name         display name (the decoded last path segment)
 * @param size         length in bytes, or -1 for folders and when unknown
 * @param lastModified null when the server doesn't report it
 */
public record RemoteFile(URI uri, String name, boolean directory, long size, Instant lastModified) {
}
