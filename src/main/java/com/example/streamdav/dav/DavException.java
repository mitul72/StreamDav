package com.example.streamdav.dav;

import java.io.IOException;

/** The server answered, but not with what we asked for. The message is suitable for showing to the user. */
public class DavException extends IOException {
    private final int statusCode;

    public DavException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
