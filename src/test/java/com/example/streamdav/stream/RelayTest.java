package com.example.streamdav.stream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayTest {

    /** Returns one byte after a delay. */
    private static InputStream slowByte(long millis) {
        return new InputStream() {
            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] buffer) throws IOException {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
                buffer[0] = 1;
                return 1;
            }
        };
    }

    /** Never returns data; only an interrupt ends the read. */
    private static InputStream silent() {
        return new InputStream() {
            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] buffer) throws IOException {
                try {
                    new LinkedBlockingQueue<>().take();
                    return -1;
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
            }
        };
    }

    @Test
    void aReadThatCompletedIsNeverMistakenForAStall() throws Exception {
        AtomicReference<Relay> relay = new AtomicReference<>();
        AtomicBoolean writeInterrupted = new AtomicBoolean();
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            relay.set(new Relay());
            try {
                assertEquals(1, relay.get().read(slowByte(100), new byte[1]));
                // A slow write to the player, long past the timeout; nothing may interrupt it.
                Thread.sleep(500);
            } catch (InterruptedException e) {
                writeInterrupted.set(true);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        // The watchdog, polling throughout with a timeout the read never reaches.
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
        while (System.nanoTime() < end) {
            Relay current = relay.get();
            if (current != null) {
                assertFalse(current.abandonIfWaiting(Duration.ofMillis(400), "test"));
            }
            Thread.sleep(5);
        }
        reader.get(2, TimeUnit.SECONDS);
        assertFalse(writeInterrupted.get());
    }

    @Test
    void abandonsAReadThatIsWaitingAndLeaksNoInterrupt() throws Exception {
        AtomicReference<Relay> relay = new AtomicReference<>();
        CompletableFuture<Boolean> reader = CompletableFuture.supplyAsync(() -> {
            relay.set(new Relay());
            IOException error = assertThrows(IOException.class, () -> relay.get().read(silent(), new byte[1]));
            assertTrue(error.getMessage().contains("player reconnected"), error.getMessage());
            return Thread.currentThread().isInterrupted();
        });
        while (relay.get() == null) {
            Thread.sleep(5);
        }
        Thread.sleep(100);
        assertTrue(relay.get().abandonIfWaiting(Duration.ofMillis(50), "the player reconnected"));
        assertFalse(reader.get(2, TimeUnit.SECONDS), "the interrupt that ended the read was consumed");
    }

    @Test
    void cannotAbandonBetweenReads() throws Exception {
        Relay relay = new Relay();
        relay.read(slowByte(0), new byte[1]);

        assertFalse(relay.abandonIfWaiting(Duration.ZERO, "test"));
        assertFalse(Thread.interrupted());
    }
}
