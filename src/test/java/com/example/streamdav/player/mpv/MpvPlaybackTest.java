package com.example.streamdav.player.mpv;

import com.example.streamdav.player.Playback;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the JavaFX/native hand-off, not just the libmpv bindings. Run with xvfb-run on headless Linux. */
class MpvPlaybackTest {
    private static LibMpv mpv;
    private MpvPlayback playback;
    private Stage stage;

    @BeforeAll
    static void startToolkit() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
                || System.getenv("DISPLAY") != null, "a display (or xvfb-run) is required");
        var library = LibMpv.load();
        assumeTrue(library.isPresent(), "libmpv is not installed");
        mpv = library.get();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyRunning) {
            // Another test class in this JVM started JavaFX first.
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX never started");
    }

    @AfterEach
    void closePlayer() throws Exception {
        onFx(() -> {
            if (playback != null) {
                playback.dispose();
                playback.dispose();
            }
            if (stage != null) {
                stage.close();
            }
            return null;
        });
    }

    @Test
    void pauseWhileLoadingReportsPausedAndPlayResumesLoading() throws Exception {
        onFx(() -> {
            playback = new MpvPlayback(mpv, source(), Duration.ZERO);
            playback.pause();
            assertEquals(Playback.Status.PAUSED, playback.status());
            playback.play();
            assertEquals(Playback.Status.LOADING, playback.status());
            return null;
        });
        await(() -> playback.status() == Playback.Status.PLAYING);
        onFx(() -> {
            playback.pause();
            assertEquals(Playback.Status.PAUSED, playback.status());
            return null;
        });
        // Drain native property notifications too: the pause must survive the asynchronous hand-off.
        Thread.sleep(100);
        assertEquals(Playback.Status.PAUSED, onFx(playback::status));
    }

    @Test
    void showsColourFramesAndDisposesDuringPlayback() throws Exception {
        onFx(() -> {
            playback = new MpvPlayback(mpv, source(), Duration.ZERO);
            stage = new Stage();
            stage.setScene(new Scene(playback.view(), 320, 240));
            stage.show();
            return null;
        });
        await(() -> {
            assertNotEquals(Playback.Status.FAILED, playback.status(), playback.errorMessage());
            ImageView image = (ImageView) ((StackPane) playback.view()).getChildren().getFirst();
            return image.getImage() != null && image.getImage().getWidth() >= 320
                    && image.getImage().getPixelReader().getArgb((int) image.getImage().getWidth() / 2,
                    (int) image.getImage().getHeight() / 2) != 0xff000000;
        });
        onFx(() -> {
            playback.pause();
            stage.setWidth(480);
            stage.setHeight(360);
            return null;
        });
        await(() -> {
            ImageView image = (ImageView) ((StackPane) playback.view()).getChildren().getFirst();
            return image.getImage().getWidth() > 320;
        });
        onFx(() -> {
            playback.play();
            playback.dispose();
            return null;
        });
    }

    private static URI source() {
        return URI.create("av://lavfi:testsrc=size=640x480:rate=25:duration=10");
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (onFx(condition)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("player state did not settle within ten seconds");
    }
}
