package com.example.streamdav.player;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;

/** Plays through JavaFX Media, which handles MP4 (H.264/HEVC + AAC), MP3, WAV and AIFF. */
public final class JavaFxPlayback implements Playback {
    private static final Logger log = LogManager.getLogger(JavaFxPlayback.class);

    private final StackPane view = new StackPane();
    private final ReadOnlyObjectWrapper<Status> status = new ReadOnlyObjectWrapper<>(Status.LOADING);
    private final ReadOnlyObjectWrapper<Duration> position = new ReadOnlyObjectWrapper<>(Duration.ZERO);
    private final ReadOnlyObjectWrapper<Duration> duration = new ReadOnlyObjectWrapper<>(Duration.UNKNOWN);
    private final ReadOnlyBooleanWrapper audioOnly = new ReadOnlyBooleanWrapper();
    private final DoubleProperty volume = new SimpleDoubleProperty(1);
    private final BooleanProperty mute = new SimpleBooleanProperty();
    private final Duration start;
    private MediaPlayer player;
    private String errorMessage;
    /** Whether to start once the file is ready; pausing while it loads clears it. */
    private boolean playWhenReady = true;
    private boolean closed;

    public JavaFxPlayback(URI url, Duration start) {
        this.start = start;
        MediaView mediaView = new MediaView();
        mediaView.setPreserveRatio(true);
        mediaView.fitWidthProperty().bind(view.widthProperty());
        mediaView.fitHeightProperty().bind(view.heightProperty());
        view.getChildren().add(mediaView);
        view.setMinSize(0, 0);
        try {
            Media media = new Media(url.toString());
            media.setOnError(() -> fail(media.getError()));
            player = new MediaPlayer(media);
        } catch (MediaException e) {
            fail(e);
            return;
        }
        player.setOnReady(this::onReady);
        player.setOnError(() -> fail(player.getError()));
        player.setOnEndOfMedia(() -> {
            if (!closed && status.get() != Status.FAILED) {
                status.set(Status.ENDED);
            }
        });
        player.statusProperty().addListener((observable, old, playerStatus) -> onStatusChanged(playerStatus));
        player.currentTimeProperty().addListener((observable, old, time) -> {
            if (!closed) {
                position.set(time);
            }
        });
        player.volumeProperty().bindBidirectional(volume);
        player.muteProperty().bindBidirectional(mute);
        mediaView.setMediaPlayer(player);
    }

    private void onReady() {
        if (closed || status.get() == Status.FAILED) {
            return;
        }
        Media media = player.getMedia();
        audioOnly.set(media.getWidth() == 0 && media.getHeight() == 0);
        duration.set(media.getDuration());
        if (start.greaterThan(Duration.ZERO)) {
            player.seek(start);
        }
        if (playWhenReady) {
            player.play();
        } else if (status.get() == Status.LOADING) {
            status.set(Status.PAUSED);
        }
    }

    private void onStatusChanged(MediaPlayer.Status playerStatus) {
        if (closed || status.get() == Status.FAILED) {
            return;
        }
        switch (playerStatus) {
            case PLAYING -> status.set(Status.PLAYING);
            case PAUSED, STOPPED -> {
                if (status.get() != Status.ENDED) {
                    status.set(Status.PAUSED);
                }
            }
            case STALLED -> status.set(Status.BUFFERING);
            default -> {
            }
        }
    }

    private void fail(MediaException error) {
        if (closed || status.get() == Status.FAILED) {
            return;
        }
        log.warn("JavaFX playback failed", error);
        errorMessage = describe(error);
        status.set(Status.FAILED);
    }

    private static String describe(MediaException error) {
        if (error == null) {
            return "Playback failed.";
        }
        return switch (error.getType()) {
            case MEDIA_UNSUPPORTED, OPERATION_UNSUPPORTED -> "The built-in player can't decode this file.";
            case MEDIA_INACCESSIBLE, MEDIA_UNAVAILABLE -> "The file couldn't be loaded from the server.";
            case MEDIA_CORRUPTED -> "The file appears to be damaged.";
            default -> "Playback failed: " + error.getMessage();
        };
    }

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
        if (closed || status.get() == Status.FAILED) {
            return;
        }
        playWhenReady = true;
        if (player == null) {
            return;
        }
        if (status.get() == Status.ENDED) {
            player.seek(Duration.ZERO);
        }
        player.play();
        if (player.getStatus() == MediaPlayer.Status.UNKNOWN) {
            status.set(Status.LOADING);
        }
        // MediaPlayer stays PLAYING at the end of the media (and ignores pause there), so replaying fires no
        // status change; report it ourselves.
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            status.set(Status.PLAYING);
        }
    }

    @Override
    public void pause() {
        if (closed || status.get() == Status.FAILED) {
            return;
        }
        playWhenReady = false;
        if (player != null) {
            player.pause();
        }
        if (status.get() == Status.LOADING || status.get() == Status.BUFFERING) {
            status.set(Status.PAUSED);
        }
    }

    @Override
    public void seek(Duration target) {
        if (!closed && status.get() != Status.FAILED && player != null) {
            if (status.get() == Status.ENDED) {
                status.set(Status.PAUSED);
            }
            player.seek(target);
        }
    }

    @Override
    public void dispose() {
        if (closed) {
            return;
        }
        closed = true;
        if (player != null) {
            player.volumeProperty().unbindBidirectional(volume);
            player.muteProperty().unbindBidirectional(mute);
            player.dispose();
        }
    }
}
