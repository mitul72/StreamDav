package com.example.streamdav.ui;

import com.example.streamdav.library.MediaKind;
import com.example.streamdav.library.RemoteFile;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import java.util.Locale;

/** Vector icons drawn on a 24x24 grid. Paths are from Google's Material Icons (Apache License 2.0). */
enum Icon {
    FOLDER("M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"),
    VIDEO("M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"),
    AUDIO("M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"),
    FILE("M6 2c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6H6zm7 7V3.5L18.5 9H13z"),
    BACK("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"),
    UP("M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z"),
    REFRESH("M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08"
            + "c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"),
    MORE("M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"
            + "m0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"),
    PLAY("M8 5v14l11-7z"),
    PAUSE("M6 19h4V5H6v14zm8-14v14h4V5h-4z"),
    VOLUME("M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"
            + "M14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"),
    MUTED("M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64"
            + "l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27"
            + " 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21"
            + " 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"),
    FULLSCREEN("M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"),
    EXIT_FULLSCREEN("M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z"),
    EXTERNAL("M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7z"
            + "M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z");

    private final String path;

    Icon(String path) {
        this.path = path;
    }

    static Icon of(RemoteFile file) {
        if (file.directory()) {
            return FOLDER;
        }
        return switch (MediaKind.of(file.name())) {
            case VIDEO -> VIDEO;
            case AUDIO -> AUDIO;
            case OTHER -> FILE;
        };
    }

    /** A {@code size}-pixel square node. Style it with {@code .icon} or {@code .icon-<name>} and {@code -fx-fill}. */
    Node create(double size) {
        SVGPath shape = new SVGPath();
        shape.setContent(path);
        shape.getStyleClass().addAll("icon", "icon-" + name().toLowerCase(Locale.ROOT).replace('_', '-'));
        shape.setScaleX(size / 24);
        shape.setScaleY(size / 24);
        // The Group sizes itself to the scaled shape; the box keeps every icon the same footprint.
        StackPane box = new StackPane(new Group(shape));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }
}
