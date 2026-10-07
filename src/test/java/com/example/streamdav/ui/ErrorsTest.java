package com.example.streamdav.ui;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertPathBuilderException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorsTest {

    // These mirror what java.net.http.HttpClient throws for each situation.

    @Test
    void unknownHost() {
        ConnectException error = new ConnectException();
        error.initCause(new UnresolvedAddressException());
        assertEquals("Couldn't find that server. Check the address.", Errors.describe(error));
    }

    @Test
    void untrustedCertificate() {
        SSLHandshakeException error = new SSLHandshakeException("(certificate_unknown) PKIX path building failed");
        error.initCause(new CertPathBuilderException("unable to find valid certification path"));
        assertEquals("The server's certificate isn't trusted. It may be self-signed.", Errors.describe(error));
    }

    @Test
    void httpsToAPlainHttpServer() {
        assertEquals("This server doesn't use https. Try an http:// address.",
                Errors.describe(new SSLException("Unrecognized SSL message, plaintext connection?")));
    }

    @Test
    void timeouts() {
        assertEquals("Timed out connecting to the server.",
                Errors.describe(new HttpConnectTimeoutException("HTTP connect timed out")));
        assertEquals("The server took too long to respond.",
                Errors.describe(new HttpTimeoutException("request timed out")));
    }

    @Test
    void unwrapsAsyncWrappers() {
        assertEquals("Couldn't connect to the server. Check the address and that the server is running.",
                Errors.describe(new CompletionException(new ConnectException("Connection refused"))));
    }

    @Test
    void passesThroughUserFacingMessages() {
        assertEquals("The server rejected the username or password.",
                Errors.describe(new IOException("The server rejected the username or password.")));
    }

    @Test
    void namesTheErrorWhenThereIsNoMessage() {
        assertEquals("Something went wrong (IllegalStateException).", Errors.describe(new IllegalStateException()));
    }
}
