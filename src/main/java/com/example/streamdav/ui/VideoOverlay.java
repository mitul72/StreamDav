package com.example.streamdav.ui;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * A transparent window kept exactly over the main window's content, for the player's controls when mpv draws the
 * video straight into the main window: nothing the main window itself draws would show above the picture.
 */
final class VideoOverlay {
    private final Stage owner;
    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    private final InvalidationListener follow = observable -> follow();
    private final ChangeListener<Boolean> ownerFocused;
    private final ChangeListener<Boolean> ownerIconified;

    VideoOverlay(Stage owner, Parent content) {
        this.owner = owner;
        stage.initOwner(owner);
        Scene scene = new Scene(content, Color.TRANSPARENT);
        scene.getStylesheets().add(Navigator.stylesheet());
        stage.setScene(scene);
        // Clicking the main window (or switching back to the app) focuses it; the controls should have the keys.
        ownerFocused = (observable, was, focused) -> {
            if (focused && stage.isShowing()) {
                Platform.runLater(stage::requestFocus);
            }
        };
        ownerIconified = (observable, was, iconified) -> {
            if (iconified) {
                stage.hide();
            } else {
                show();
            }
        };
    }

    void show() {
        owner.xProperty().addListener(follow);
        owner.yProperty().addListener(follow);
        owner.widthProperty().addListener(follow);
        owner.heightProperty().addListener(follow);
        owner.fullScreenProperty().addListener(follow);
        owner.getScene().widthProperty().addListener(follow);
        owner.getScene().heightProperty().addListener(follow);
        owner.focusedProperty().addListener(ownerFocused);
        owner.iconifiedProperty().addListener(ownerIconified);
        follow();
        stage.show();
        stage.requestFocus();
    }

    void close() {
        owner.xProperty().removeListener(follow);
        owner.yProperty().removeListener(follow);
        owner.widthProperty().removeListener(follow);
        owner.heightProperty().removeListener(follow);
        owner.fullScreenProperty().removeListener(follow);
        owner.getScene().widthProperty().removeListener(follow);
        owner.getScene().heightProperty().removeListener(follow);
        owner.focusedProperty().removeListener(ownerFocused);
        owner.iconifiedProperty().removeListener(ownerIconified);
        stage.close();
    }

    /** Covers the main window's content area, without its title bar and borders. */
    private void follow() {
        Scene scene = owner.getScene();
        stage.setX(owner.getX() + scene.getX());
        stage.setY(owner.getY() + scene.getY());
        stage.setWidth(scene.getWidth());
        stage.setHeight(scene.getHeight());
    }
}
