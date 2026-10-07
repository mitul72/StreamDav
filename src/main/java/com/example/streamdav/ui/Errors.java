package com.example.streamdav.ui;

import javax.net.ssl.SSLException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateException;
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
        if (cause instanceof UnknownHostException || hasCause(cause, UnresolvedAddressException.class)) {
            return "Couldn't find that server. Check the address.";
        }
        if (hasCause(cause, CertPathBuilderException.class) || hasCause(cause, CertificateException.class)) {
            return "The server's certificate isn't trusted. It may be self-signed.";
        }
        return switch (cause) {
            case HttpConnectTimeoutException _ -> "Timed out connecting to the server.";
            case HttpTimeoutException _, SocketTimeoutException _ -> "The server took too long to respond.";
            case ConnectException _ -> "Couldn't connect to the server. Check the address and that the server is running.";
            case SSLException e when String.valueOf(e.getMessage()).contains("plaintext") ->
                    "This server doesn't use https. Try an http:// address.";
            case SSLException e -> "Secure connection failed: " + e.getMessage();
            default -> cause.getMessage() != null && !cause.getMessage().isBlank()
                    ? cause.getMessage()
                    : "Something went wrong (" + cause.getClass().getSimpleName() + ").";
        };
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
