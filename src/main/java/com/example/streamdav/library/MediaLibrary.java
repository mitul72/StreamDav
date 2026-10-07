package com.example.streamdav.library;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/** A connected media server, as the UI sees it. */
public interface MediaLibrary extends AutoCloseable {

    /** The folder the user connected to; browsing never goes above it. */
    URI root();

    /** Lists the direct children of a folder. Blocks, so call it off the JavaFX thread. */
    List<RemoteFile> list(URI folder) throws IOException, InterruptedException;

    /** A URL that media players can open without needing the server's credentials. */
    URI streamUrl(RemoteFile file);

    /** Releases connections and stream URLs once the user is done with this server. */
    @Override
    default void close() {
    }

    @FunctionalInterface
    interface Connector {
        /** Creates a library for a server. May block; the UI calls it off the JavaFX thread. */
        MediaLibrary open(URI root, String username, String password) throws IOException;
    }
}
