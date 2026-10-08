package com.example.streamdav.player;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.net.URI;

/** One file being played by some media engine. Create it, show its view, and dispose it when done. */
public interface Playback {

    /** {@code BUFFERING} means waiting for data while trying to play; a paused playback is always {@code PAUSED}. */
    enum Status { LOADING, PLAYING, PAUSED, BUFFERING, ENDED, FAILED }

    @FunctionalInterface
    interface Factory {
        /** Starts loading {@code url}; playback begins at {@code start} once the file is ready. */
        Playback open(URI url, Duration start);

        /**
         * True when the engine draws the picture straight into the native window, on the GPU, rather than into
         * {@link #view()}. Nothing JavaFX draws in that window shows over the picture, so the player's controls go
         * in a transparent window above it.
         */
        default boolean drawsOnWindow() {
            return false;
        }
    }

    /**
     * Shows the picture, scaled to fit whatever size the player lays it out at. For engines that draw on the window
     * it's an empty region the size of the picture, for clicks.
     */
    Region view();

    ReadOnlyObjectProperty<Status> statusProperty();

    ReadOnlyObjectProperty<Duration> positionProperty();

    /** {@link Duration#UNKNOWN} until the file has loaded, and for streams without one. */
    ReadOnlyObjectProperty<Duration> durationProperty();

    /** True when the file has no picture to show. */
    ReadOnlyBooleanProperty audioOnlyProperty();

    /** From 0 (silent) to 1 (full). */
    DoubleProperty volumeProperty();

    BooleanProperty muteProperty();

    /** Why playback failed, once the status is {@link Status#FAILED}. */
    String errorMessage();

    void play();

    void pause();

    void seek(Duration position);

    /** Audio and subtitle tracks to choose between; empty if the engine can't switch tracks. */
    default ObservableList<Track> tracks() {
        return FXCollections.emptyObservableList();
    }

    /** Switches to {@code track}; {@code null} turns subtitles off. */
    default void selectTrack(Track.Kind kind, Track track) {
    }

    /** Stops playback and releases the engine. The playback can't be used afterwards. */
    void dispose();

    default Status status() {
        return statusProperty().get();
    }
}
