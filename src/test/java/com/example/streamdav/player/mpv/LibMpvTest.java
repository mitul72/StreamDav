package com.example.streamdav.player.mpv;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the bindings against the installed libmpv; skipped where libmpv isn't available. */
class LibMpvTest {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    private static LibMpv mpv;

    @BeforeAll
    static void load() {
        Optional<LibMpv> loaded = LibMpv.load();
        assumeTrue(loaded.isPresent(), "libmpv is not installed");
        mpv = loaded.get();
    }

    private MemorySegment newCore() {
        MemorySegment handle = mpv.create();
        assertFalse(handle.equals(MemorySegment.NULL), "mpv_create failed");
        assertEquals(0, mpv.setOption(handle, "vo", "libmpv"));
        assertEquals(0, mpv.setOption(handle, "ao", "null"));
        assertEquals(0, mpv.setOption(handle, "load-scripts", "no"));
        assertEquals(0, mpv.setOption(handle, "keep-open", "yes"));
        assertEquals(0, mpv.initialize(handle));
        return handle;
    }

    @Test
    void reportsErrorsForUnknownOptions() {
        MemorySegment handle = mpv.create();
        try {
            int error = mpv.setOption(handle, "no-such-option", "1");
            assertTrue(error < 0);
            assertNotNull(mpv.errorString(error));
        } finally {
            mpv.terminateDestroy(handle);
        }
    }

    @Test
    void playsAndRendersAFrameInSoftware() throws Exception {
        MemorySegment handle = newCore();
        Semaphore frameReady = new Semaphore(0);
        MemorySegment renderContext = mpv.createSoftwareRenderContext(handle);
        // The render context must be freed before the core is destroyed, or libmpv aborts the process.
        try (Arena arena = Arena.ofShared()) {
            try {
                mpv.setUpdateCallback(renderContext, frameReady::release, arena);
                assertEquals(0, mpv.observeProperty(handle, "duration", LibMpv.FORMAT_DOUBLE));
                assertEquals(0, mpv.command(handle, "loadfile",
                        "av://lavfi:testsrc=size=" + WIDTH + "x" + HEIGHT + ":rate=25:duration=3"));

                // A lavfi source reports a duration that grows as it plays; any positive value will do.
                Double duration = null;
                boolean loaded = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while ((!loaded || duration == null) && System.nanoTime() < deadline) {
                    LibMpv.Event event = mpv.waitEvent(handle, 0.1);
                    if (event.id() == LibMpv.EVENT_FILE_LOADED) {
                        loaded = true;
                    } else if (event.id() == LibMpv.EVENT_END_FILE) {
                        assumeTrue(LibMpv.endFileReason(event) != LibMpv.END_FILE_REASON_ERROR,
                                "this libmpv can't open lavfi sources: " + mpv.errorString(LibMpv.endFileError(event)));
                    } else if (event.id() == LibMpv.EVENT_PROPERTY_CHANGE) {
                        LibMpv.Property property = LibMpv.property(event);
                        if (property.name().equals("duration") && property.value() instanceof Double value && value > 0) {
                            duration = value;
                        }
                    }
                }
                assertTrue(loaded, "file never loaded");
                assertNotNull(duration, "duration never reported");
                assertEquals("320", mpv.getProperty(handle, "width"));

                // Render frames until one arrives; testsrc is colourful, so a rendered frame isn't all black.
                long stride = WIDTH * 4L;
                MemorySegment pixels = arena.allocate(stride * HEIGHT, 64);
                MemorySegment target = LibMpv.softwareTarget(arena, WIDTH, HEIGHT, stride, pixels);
                boolean rendered = false;
                while (!rendered && frameReady.tryAcquire(5, TimeUnit.SECONDS)) {
                    if ((mpv.renderContextUpdate(renderContext) & LibMpv.RENDER_UPDATE_FRAME) != 0) {
                        assertEquals(0, mpv.render(renderContext, target));
                        rendered = true;
                    }
                    mpv.waitEvent(handle, 0);
                }
                assertTrue(rendered, "no frame was rendered");
                assertTrue(hasColour(pixels), "rendered frame is blank");
            } finally {
                mpv.freeRenderContext(renderContext);
            }
        } finally {
            mpv.terminateDestroy(handle);
        }
    }

    private static boolean hasColour(MemorySegment pixels) {
        for (long i = 0; i < pixels.byteSize(); i += 4) {
            if (pixels.get(JAVA_BYTE, i) != 0 || pixels.get(JAVA_BYTE, i + 1) != 0 || pixels.get(JAVA_BYTE, i + 2) != 0) {
                return true;
            }
        }
        return false;
    }
}
