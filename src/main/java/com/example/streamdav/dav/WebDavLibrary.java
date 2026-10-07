package com.example.streamdav.dav;

import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.stream.StreamProxy;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/** A media library on a WebDAV server; files are played through the local streaming proxy. */
public final class WebDavLibrary implements MediaLibrary {
    private final DavClient client;
    private final StreamProxy proxy;

    public WebDavLibrary(DavClient client, StreamProxy proxy) {
        this.client = client;
        this.proxy = proxy;
    }

    @Override
    public URI root() {
        return client.root();
    }

    @Override
    public List<RemoteFile> list(URI folder) throws IOException, InterruptedException {
        return client.list(folder);
    }

    @Override
    public URI streamUrl(RemoteFile file) {
        return proxy.publish(file.uri(), client.httpClient(), client.authorization());
    }

    @Override
    public void close() {
        proxy.forget(client.httpClient());
        client.close();
    }
}
