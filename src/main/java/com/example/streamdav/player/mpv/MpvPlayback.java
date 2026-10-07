package com.example.streamdav.player.mpv;

import com.example.streamdav.player.Playback;
import com.example.streamdav.player.Track;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Plays through an embedded libmpv, so anything mpv supports (MKV, HEVC, AV1, subtitles, ...) plays in the app.
 *
 * <p>mpv's software renderer draws each frame on a render thread, which hands it to the JavaFX thread through a
 * lock-free slot; the JavaFX thread copies it into the image on screen. Frames are rendered no larger than the video, the view or {@link #MAX_WIDTH} x
 * {@link #MAX_HEIGHT}, whichever is smallest, and the GPU scales them up from there.
 */
public final class MpvPlayback implements Playback {
    private static final Logger log = LogManager.getLogger(MpvPlayback.class);
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;
    // mpv/client.h error codes
    private static final int ERROR_LOADING_FAILED = -13;
    private static final int ERROR_NOTHING_TO_PLAY = -16;
    private static final int ERROR_UNKNOWN_FORMAT = -17;

    private final LibMpv mpv;
    private final MemorySegment handle;
    private final Arena arena = Arena.ofShared();
    private final Semaphore renderRequests = new Semaphore(0);
    private MemorySegment renderContext;
    private Thread eventThread;
    private Thread renderThread;
    private volatile boolean closed;

    private final StackPane view = new StackPane();
    private final ImageView imageView = new ImageView();
    private final ReadOnlyObjectWrapper<Status> status = new ReadOnlyObjectWrapper<>(Status.LOADING);
    private final ReadOnlyObjectWrapper<Duration> position = new ReadOnlyObjectWrapper<>(Duration.ZERO);
    private final ReadOnlyObjectWrapper<Duration> duration = new ReadOnlyObjectWrapper<>(Duration.UNKNOWN);
    private final ReadOnlyBooleanWrapper audioOnly = new ReadOnlyBooleanWrapper();
    private final DoubleProperty volume = new SimpleDoubleProperty(1);
    private final BooleanProperty mute = new SimpleBooleanProperty();
    private final ObservableList<Track> tracks = FXCollections.observableArrayList();
    private final ObservableList<Track> readOnlyTracks = FXCollections.unmodifiableObservableList(tracks);
    private String errorMessage;

    // Playback state as last reported by mpv; only touched on the JavaFX thread.
    private boolean started;
    private boolean paused;
    private boolean waiting;
    private boolean seeking;
    private boolean ended;
    private boolean failed;
    private boolean initialized;
    private boolean updatingFromMpv;

    // Frame hand-off between the render thread and the JavaFX thread, without locks. Buffers circulate: the render
    // thread draws into its own buffer and swaps it into `ready`; the JavaFX thread takes the ready frame, copies it
    // and returns the buffer to `spare` for reuse. A frame the JavaFX thread hasn't taken yet is replaced by a newer
    // one. At most three buffers exist: one being rendered, one ready and one being copied.
    private final AtomicReference<Frame> ready = new AtomicReference<>();
    private final Queue<Frame> spare = new ConcurrentLinkedQueue<>();
    /** Shows the newest ready frame once per JavaFX pulse; frames rendered faster than that are skipped. */
    private final AnimationTimer frameTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            showFrame();
        }
    };
    private final AtomicReference<Double> pendingPosition = new AtomicReference<>();
    /** The buffer being rendered into; only touched by the render thread. */
    private Frame back;
    /** The size of the last frame rendered; only touched by the render thread. */
    private int[] renderedSize;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private volatile long videoWidth;
    private volatile long videoHeight;
    private volatile double viewWidth = MAX_WIDTH;
    private volatile double viewHeight = MAX_HEIGHT;
    private final PauseTransition resizeSettle = new PauseTransition(Duration.millis(250));
    private boolean viewSized;

    private final FrameStats stats = new FrameStats();

    /** Debug-level timing of the frame pipeline, logged every few seconds by the render thread. */
    private static final class FrameStats {
        private final AtomicInteger shown = new AtomicInteger();
        // The rest is only touched by the render thread.
        private long since = System.nanoTime();
        private int rendered;
        private long renderNanos;
        private long alphaNanos;
        private int[] size;

        void rendered(long render, long alpha, int[] frameSize) {
            rendered++;
            renderNanos += render;
            alphaNanos += alpha;
            size = frameSize;
            report();
        }

        void shown() {
            shown.incrementAndGet();
        }

        private void report() {
            double seconds = (System.nanoTime() - since) / 1e9;
            if (seconds < 5 || !log.isDebugEnabled()) {
                return;
            }
            log.debug("{}x{}: rendered {} fps, shown {} fps, mpv render {} ms, alpha {} ms",
                    size[0], size[1], Math.round(rendered / seconds), Math.round(shown.getAndSet(0) / seconds),
                    String.format(Locale.ROOT, "%.1f", renderNanos / 1e6 / rendered),
                    String.format(Locale.ROOT, "%.1f", alphaNanos / 1e6 / rendered));
            since = System.nanoTime();
            rendered = 0;
            renderNanos = 0;
            alphaNanos = 0;
        }
    }

    private record Frame(int width, int height, MemorySegment pixels, MemorySegment target, Arena arena) {
        static Frame allocate(int width, int height) {
            Arena arena = Arena.ofShared();
            long stride = width * 4L;
            MemorySegment pixels = arena.allocate(stride * height, 64);
            return new Frame(width, height, pixels, LibMpv.softwareTarget(arena, width, height, stride, pixels), arena);
        }
    }

    public MpvPlayback(LibMpv mpv, URI url, Duration start) {
        this.mpv = mpv;
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.fitWidthProperty().bind(view.widthProperty());
        imageView.fitHeightProperty().bind(view.heightProperty());
        view.getChildren().add(imageView);
        view.setMinSize(0, 0);
        // A new size needs a new frame, even while paused, or the old one is stretched until playback moves on.
        // While a window is being dragged to a new size, keep rendering at the old size (the GPU scales it) and
        // switch once the size settles, rather than allocating new buffers for every intermediate size.
        resizeSettle.setOnFinished(event -> applyViewSize());
        view.widthProperty().addListener(observable -> onViewResized());
        view.heightProperty().addListener(observable -> onViewResized());

        handle = mpv.create();
        if (handle.equals(MemorySegment.NULL)) {
            fail("The built-in player couldn't start.");
            return;
        }
        option("vo", "libmpv");
        option("hwdec", "auto-copy-safe");
        option("keep-open", "yes");
        option("load-scripts", "no");
        option("ytdl", "no");
        option("osd-level", "0");
        option("input-default-bindings", "no");
        option("audio-client-name", "StreamDav");
        if (start.greaterThan(Duration.ZERO)) {
            option("start", String.format(Locale.ROOT, "%.3f", start.toSeconds()));
        }
        int error = mpv.initialize(handle);
        if (error < 0) {
            fail("The built-in player couldn't start: " + mpv.errorString(error));
            return;
        }
        initialized = true;
        mpv.requestLogMessages(handle, "warn");
        try {
            renderContext = mpv.createSoftwareRenderContext(handle);
            mpv.setUpdateCallback(renderContext, renderRequests::release, arena);
        } catch (RuntimeException e) {
            // dispose() still tears down whatever was created.
            log.warn("Could not set up mpv's software renderer", e);
            fail("The built-in player couldn't display video.");
            return;
        }
        observe("time-pos", LibMpv.FORMAT_DOUBLE);
        observe("duration", LibMpv.FORMAT_DOUBLE);
        observe("pause", LibMpv.FORMAT_FLAG);
        observe("paused-for-cache", LibMpv.FORMAT_FLAG);
        observe("seeking", LibMpv.FORMAT_FLAG);
        observe("eof-reached", LibMpv.FORMAT_FLAG);
        observe("volume", LibMpv.FORMAT_DOUBLE);
        observe("mute", LibMpv.FORMAT_FLAG);
        observe("dwidth", LibMpv.FORMAT_INT64);
        observe("dheight", LibMpv.FORMAT_INT64);
        // Changes whenever tracks are added or a different one is selected.
        observe("track-list", LibMpv.FORMAT_NONE);

        volume.addListener((observable, old, value) -> {
            if (!updatingFromMpv) {
                property("volume", String.format(Locale.ROOT, "%.1f", value.doubleValue() * 100));
            }
        });
        mute.addListener((observable, old, value) -> {
            if (!updatingFromMpv) {
                property("mute", value ? "yes" : "no");
            }
        });

        frameTimer.start();
        eventThread = Thread.ofPlatform().daemon().name("mpv-events").start(this::eventLoop);
        renderThread = Thread.ofPlatform().daemon().name("mpv-render").start(this::renderLoop);
        mpv.command(handle, "loadfile", url.toString());
    }

    // mpv threads

    private void eventLoop() {
        while (!closed) {
            LibMpv.Event event = mpv.waitEvent(handle, -1);
            switch (event.id()) {
                case LibMpv.EVENT_SHUTDOWN -> {
                    return;
                }
                case LibMpv.EVENT_PROPERTY_CHANGE -> onPropertyChange(LibMpv.property(event));
                case LibMpv.EVENT_FILE_LOADED -> {
                    String videoTrack = mpv.getProperty(handle, "vid");
                    boolean noVideo = videoTrack == null || videoTrack.equals("no");
                    onFx(() -> audioOnly.set(noVideo));
                }
                case LibMpv.EVENT_PLAYBACK_RESTART -> onFx(() -> {
                    started = true;
                    updateStatus();
                });
                case LibMpv.EVENT_END_FILE -> {
                    if (LibMpv.endFileReason(event) == LibMpv.END_FILE_REASON_ERROR) {
                        String message = describe(LibMpv.endFileError(event));
                        onFx(() -> fail(message));
                    }
                }
                case LibMpv.EVENT_LOG_MESSAGE -> log.warn("mpv {}", LibMpv.logMessage(event));
                default -> {
                }
            }
        }
    }

    private void onPropertyChange(LibMpv.Property property) {
        Object value = property.value();
        switch (property.name()) {
            case "time-pos" -> {
                // Unavailable while loading or switching tracks; keep the last position rather than jumping to 0.
                if (!(value instanceof Double seconds)) {
                    return;
                }
                // Coalesce: mpv reports this every frame, the UI only needs the latest.
                if (pendingPosition.getAndSet(seconds) == null) {
                    onFx(() -> position.set(Duration.seconds(pendingPosition.getAndSet(null))));
                }
            }
            case "duration" -> onFx(() ->
                    duration.set(value instanceof Double seconds ? Duration.seconds(seconds) : Duration.UNKNOWN));
            case "pause" -> onFx(() -> {
                paused = Boolean.TRUE.equals(value);
                updateStatus();
            });
            case "paused-for-cache" -> onFx(() -> {
                waiting = Boolean.TRUE.equals(value);
                updateStatus();
            });
            case "seeking" -> onFx(() -> {
                seeking = Boolean.TRUE.equals(value);
                updateStatus();
            });
            case "eof-reached" -> onFx(() -> {
                ended = Boolean.TRUE.equals(value);
                updateStatus();
            });
            case "volume" -> {
                if (value instanceof Double level) {
                    onFx(() -> fromMpv(() -> volume.set(Math.clamp(level / 100, 0, 1))));
                }
            }
            case "mute" -> onFx(() -> fromMpv(() -> mute.set(Boolean.TRUE.equals(value))));
            case "dwidth" -> {
                videoWidth = value instanceof Long width ? width : 0;
                renderRequests.release();
            }
            case "dheight" -> {
                videoHeight = value instanceof Long height ? height : 0;
                renderRequests.release();
            }
            case "track-list" -> {
                List<Track> current = MpvTracks.read(name -> mpv.getProperty(handle, name), Locale.getDefault());
                onFx(() -> tracks.setAll(current));
            }
            default -> {
            }
        }
    }

    private void renderLoop() {
        try {
            renderFrames();
        } catch (RuntimeException | Error e) {
            // Without this the thread would die silently: the picture freezes while the sound carries on.
            log.error("Video rendering stopped", e);
            onFx(() -> fail("The video couldn't be displayed."));
        }
    }

    private void renderFrames() {
        while (!closed) {
            renderRequests.acquireUninterruptibly();
            renderRequests.drainPermits();
            if (closed) {
                return;
            }
            boolean newFrame = (mpv.renderContextUpdate(renderContext) & LibMpv.RENDER_UPDATE_FRAME) != 0;
            int[] size = surfaceSize();
            // Without a new frame, only redraw the current one when the size it's needed at has changed.
            if (size == null || !newFrame && (renderedSize == null || Arrays.equals(size, renderedSize))) {
                continue;
            }
            renderedSize = size;
            if (back == null) {
                back = spare.poll();
            }
            if (back == null || back.width() != size[0] || back.height() != size[1]) {
                if (back != null) {
                    back.arena().close();
                }
                back = Frame.allocate(size[0], size[1]);
            }
            long renderStart = System.nanoTime();
            int error = mpv.render(renderContext, back.target());
            long renderEnd = System.nanoTime();
            if (error < 0) {
                log.warn("mpv couldn't render a {}x{} frame: {}", size[0], size[1], mpv.errorString(error));
                continue;
            }
            // mpv leaves the fourth byte of each "bgr0" pixel undefined; JavaFX reads it as alpha.
            MemorySegment pixels = back.pixels();
            for (long offset = 3; offset < pixels.byteSize(); offset += 4) {
                pixels.set(JAVA_BYTE, offset, (byte) 0xFF);
            }
            stats.rendered(renderEnd - renderStart, System.nanoTime() - renderEnd, size);
            // Publish the frame; if the previous one was never shown, reuse its buffer for the next frame.
            back = ready.getAndSet(back);
        }
    }

    /** The frame size: the video's shape, fitted into the view and the maximum, never larger than the video. */
    private int[] surfaceSize() {
        long width = videoWidth;
        long height = videoHeight;
        if (width <= 0 || height <= 0) {
            return null;
        }
        double scale = Math.min(1, Math.min(Math.min(MAX_WIDTH, Math.max(viewWidth, 16)) / width,
                Math.min(MAX_HEIGHT, Math.max(viewHeight, 16)) / height));
        // A width that's a multiple of 16 keeps every row 64-byte aligned, as mpv's fast paths want.
        int surfaceWidth = Math.max(16, (int) (width * scale) / 16 * 16);
        int surfaceHeight = Math.max(2, (int) Math.round(height * scale));
        return new int[] {surfaceWidth, surfaceHeight};
    }

    // JavaFX thread

    private void showFrame() {
        if (closed) {
            return;
        }
        Frame frame = ready.getAndSet(null);
        if (frame == null) {
            return;
        }
        if (pixelBuffer == null || pixelBuffer.getWidth() != frame.width() || pixelBuffer.getHeight() != frame.height()) {
            ByteBuffer buffer = ByteBuffer.allocateDirect((int) frame.pixels().byteSize());
            pixelBuffer = new PixelBuffer<>(frame.width(), frame.height(), buffer, PixelFormat.getByteBgraPreInstance());
            imageView.setImage(new WritableImage(pixelBuffer));
        }
        stats.shown();
        pixelBuffer.updateBuffer(buffer -> {
            MemorySegment.copy(frame.pixels(), 0, MemorySegment.ofBuffer(buffer.getBuffer()), 0, frame.pixels().byteSize());
            return null;
        });
        spare.offer(frame);
    }

    private static void release(Frame frame) {
        if (frame != null) {
            frame.arena().close();
        }
    }

    private void updateStatus() {
        if (failed) {
            status.set(Status.FAILED);
        } else if (!started) {
            status.set(Status.LOADING);
        } else if (ended) {
            status.set(Status.ENDED);
        } else if (paused) {
            // Paused by the user, even if a seek is still loading: the play button must offer to resume.
            status.set(Status.PAUSED);
        } else {
            status.set(waiting || seeking ? Status.BUFFERING : Status.PLAYING);
        }
    }

    private void fail(String message) {
        if (failed) {
            return;
        }
        log.warn("mpv playback failed: {}", message);
        // Stop the core before refusing further commands, or a failure in our rendering would leave the sound
        // playing with no way to pause it.
        if (initialized && !closed) {
            mpv.command(handle, "stop");
        }
        failed = true;
        errorMessage = message;
        updateStatus();
    }

    /** Runs an update from an mpv thread on the JavaFX thread, unless the playback was disposed in the meantime. */
    private void onFx(Runnable update) {
        Platform.runLater(() -> {
            if (!closed) {
                update.run();
            }
        });
    }

    private void fromMpv(Runnable update) {
        updatingFromMpv = true;
        try {
            update.run();
        } finally {
            updatingFromMpv = false;
        }
    }

    private void onViewResized() {
        if (!viewSized) {
            // The first layout: use it straight away.
            viewSized = true;
            applyViewSize();
        } else {
            resizeSettle.playFromStart();
        }
    }

    private void applyViewSize() {
        viewWidth = view.getWidth() * outputScale();
        viewHeight = view.getHeight() * outputScale();
        renderRequests.release();
    }

    private double outputScale() {
        return view.getScene() != null && view.getScene().getWindow() != null
                ? view.getScene().getWindow().getOutputScaleX()
                : 1;
    }

    private String describe(int error) {
        return switch (error) {
            case ERROR_UNKNOWN_FORMAT -> "The file isn't in a format the player recognises.";
            case ERROR_LOADING_FAILED -> "The file couldn't be loaded from the server.";
            case ERROR_NOTHING_TO_PLAY -> "The file doesn't contain anything playable.";
            default -> "Playback failed: " + mpv.errorString(error) + ".";
        };
    }

    private void option(String name, String value) {
        int error = mpv.setOption(handle, name, value);
        if (error < 0) {
            log.warn("mpv rejected option {}={}: {}", name, value, mpv.errorString(error));
        }
    }

    private void observe(String name, int format) {
        mpv.observeProperty(handle, name, format);
    }

    private void property(String name, String value) {
        if (!failed && !closed) {
            mpv.setProperty(handle, name, value);
        }
    }

    private void command(String... args) {
        if (!failed && !closed) {
            mpv.command(handle, args);
        }
    }

    // Playback

    @Override
    public Region view() {
        return view;
    }

    @Override
    public ReadOnlyObjectProperty<Status> statusProperty() {
        return status.getReadOnlyProperty();
    }

    @Override
    public ReadOnlyObjectProperty<Duration> positionProperty() {
        return position.getReadOnlyProperty();
    }

    @Override
    public ReadOnlyObjectProperty<Duration> durationProperty() {
        return duration.getReadOnlyProperty();
    }

    @Override
    public ReadOnlyBooleanProperty audioOnlyProperty() {
        return audioOnly.getReadOnlyProperty();
    }

    @Override
    public DoubleProperty volumeProperty() {
        return volume;
    }

    @Override
    public BooleanProperty muteProperty() {
        return mute;
    }

    @Override
    public String errorMessage() {
        return errorMessage;
    }

    @Override
    public void play() {
        if (ended) {
            command("seek", "0", "absolute");
        }
        property("pause", "no");
    }

    @Override
    public void pause() {
        property("pause", "yes");
    }

    @Override
    public void seek(Duration target) {
        command("seek", String.format(Locale.ROOT, "%.3f", target.toSeconds()), "absolute");
    }

    @Override
    public ObservableList<Track> tracks() {
        return readOnlyTracks;
    }

    @Override
    public void selectTrack(Track.Kind kind, Track track) {
        property(kind == Track.Kind.AUDIO ? "aid" : "sid", track == null ? "no" : track.id());
    }

    @Override
    public void dispose() {
        if (closed) {
            return;
        }
        closed = true;
        frameTimer.stop();
        resizeSettle.stop();
        if (handle.equals(MemorySegment.NULL)) {
            arena.close();
            return;
        }
        // Stop both threads before tearing down: the render context must be freed before the core is destroyed,
        // and the core must outlive any thread still calling into it.
        mpv.wakeup(handle);
        renderRequests.release();
        join(eventThread);
        join(renderThread);
        if (renderContext != null) {
            mpv.freeRenderContext(renderContext);
        }
        mpv.terminateDestroy(handle);
        // Both threads have stopped or run here (dispose is on the JavaFX thread), so every buffer is idle.
        release(back);
        release(ready.getAndSet(null));
        for (Frame frame = spare.poll(); frame != null; frame = spare.poll()) {
            release(frame);
        }
        back = null;
        arena.close();
    }

    /** Waits for the thread to end even if interrupted: tearing mpv down under a running thread would crash. */
    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
