package com.example.streamdav.ui;

import javax.net.ssl.SSLException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class Errors {
    private Errors() {
    }

    /** A short, user-facing explanation of why a background operation failed. */
    static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause instanceof CompletionException
                || cause instanceof ExecutionException || cause instanceof UncheckedIOException)) {
            cause = cause.getCause();
        }
        return switch (cause) {
            case UnknownHostException _ -> "Couldn't find that server. Check the address.";
            case ConnectException _ -> "Couldn't connect to the server. Check the address and that the server is running.";
            case SocketTimeoutException _ -> "The server took too long to respond.";
            case SSLException e -> "Secure connection failed: " + e.getMessage();
            default -> cause.getMessage() != null && !cause.getMessage().isBlank()
                    ? cause.getMessage()
                    : "Something went wrong (" + cause.getClass().getSimpleName() + ").";
        };
    }
}
