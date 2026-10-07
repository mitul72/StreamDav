package com.example.streamdav.metadata;

import java.io.IOException;

/** A metadata provider refused or failed a request. */
public class MetadataException extends IOException {
    private final int status;

    public MetadataException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** The HTTP status, or 0 when the response couldn't be understood. */
    public int status() {
        return status;
    }
}
