package com.example.streamdav.stream;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads a server's response for one player connection, and lets other threads abandon a read that's waiting on a
 * silent server. Each read is claimed atomically: a read that has already returned can never be mistaken for a
 * stalled one, so a slow-but-healthy stream is never cut off mid-write.
 */
final class Relay {
    private static final Object ABANDONED = new Object();

    /** One read in progress; compared by identity, so every read is distinct. */
    private static final class Waiting {
        final long since = System.nanoTime();
    }

    private final AtomicReference<Object> state = new AtomicReference<>();
    private final CountDownLatch interruptSent = new CountDownLatch(1);
    private final Thread reader;
    private volatile String reason;

    /** Must be created on the thread that will call {@link #read}. */
    Relay() {
        reader = Thread.currentThread();
    }

    /** Reads like {@link InputStream#read(byte[])}, but throws if another thread abandons the read. */
    int read(InputStream body, byte[] buffer) throws IOException {
        Waiting waiting = new Waiting();
        if (!state.compareAndSet(null, waiting)) {
            throw abandonedException(null);
        }
        int read;
        try {
            read = body.read(buffer);
        } catch (IOException e) {
            if (state.get() == ABANDONED) {
                throw abandonedException(e);
            }
            throw e;
        }
        if (!state.compareAndSet(waiting, null)) {
            // Abandoned just as the data arrived; the abandoning thread is about to interrupt us.
            throw abandonedException(null);
        }
        return read;
    }

    /**
     * Abandons the read in progress if the server has sent nothing for at least {@code silence}.
     *
     * @return whether a read was abandoned
     */
    boolean abandonIfWaiting(Duration silence, String why) {
        Object current = state.get();
        if (current instanceof Waiting waiting && System.nanoTime() - waiting.since >= silence.toNanos()
                && state.compareAndSet(waiting, ABANDONED)) {
            reason = why;
            reader.interrupt();
            interruptSent.countDown();
            return true;
        }
        return false;
    }

    private IOException abandonedException(IOException cause) {
        // Consume the interrupt that ended the read, so it can't leak into whatever this thread does next.
        boolean waited = false;
        while (!waited) {
            try {
                interruptSent.await();
                waited = true;
            } catch (InterruptedException ignored) {
                // That was the interrupt we were waiting for (or an earlier one); keep waiting for the signal.
            }
        }
        Thread.interrupted();
        return new IOException("Stopped reading from the server: " + reason, cause);
    }
}
