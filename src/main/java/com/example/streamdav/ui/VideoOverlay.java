package com.example.streamdav.ui;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Popup;
import javafx.stage.Stage;

/**
 * The player's controls, kept exactly over the main window's content when mpv draws the video straight into that
 * window: nothing the main window itself draws would show above the picture.
 *
 * <p>A popup rather than a stage: the window manager doesn't manage popups, so the controls don't show up as a
 * second window in the task switcher, go exactly where they're put, and never take the keyboard from the main
 * window (whose key events JavaFX passes on to the popup). As popups stay above other apps' windows too, the
 * controls hide while another app is in front.
 */
final class VideoOverlay {
    private final Stage owner;
    private final Region content;
    private final Popup popup = new Popup();
    private final InvalidationListener follow = observable -> follow();
    /** Some changes (leaving full screen, say) settle the window's layout a pulse later. */
    private final InvalidationListener followLater = observable -> Platform.runLater(this::follow);
    private final ChangeListener<Boolean> visibility = (observable, was, now) -> updateVisibility();

    VideoOverlay(Stage owner, Region content) {
        this.owner = owner;
        this.content = content;
        popup.getContent().add(content);
        popup.setAutoFix(false);
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);
        popup.getScene().getStylesheets().add(Navigator.stylesheet());
    }

    void show() {
        Scene scene = owner.getScene();
        owner.xProperty().addListener(follow);
        owner.yProperty().addListener(follow);
        owner.widthProperty().addListener(follow);
        owner.heightProperty().addListener(follow);
        scene.xProperty().addListener(follow);
        scene.yProperty().addListener(follow);
        scene.widthProperty().addListener(follow);
        scene.heightProperty().addListener(follow);
        owner.fullScreenProperty().addListener(followLater);
        owner.maximizedProperty().addListener(followLater);
        owner.focusedProperty().addListener(visibility);
        owner.iconifiedProperty().addListener(visibility);
        updateVisibility();
    }

    void close() {
        Scene scene = owner.getScene();
        owner.xProperty().removeListener(follow);
        owner.yProperty().removeListener(follow);
        owner.widthProperty().removeListener(follow);
        owner.heightProperty().removeListener(follow);
        scene.xProperty().removeListener(follow);
        scene.yProperty().removeListener(follow);
        scene.widthProperty().removeListener(follow);
        scene.heightProperty().removeListener(follow);
        owner.fullScreenProperty().removeListener(followLater);
        owner.maximizedProperty().removeListener(followLater);
        owner.focusedProperty().removeListener(visibility);
        owner.iconifiedProperty().removeListener(visibility);
        popup.hide();
    }

    /** Shown while the app is in front; hidden when another app is, or the window is minimised. */
    private void updateVisibility() {
        boolean visible = owner.isFocused() && !owner.isIconified();
        if (visible && !popup.isShowing()) {
            sizeContent();
            popup.show(owner, contentX(), contentY());
        } else if (!visible && popup.isShowing()) {
            popup.hide();
        }
    }

    /** Covers the main window's content area, without its title bar and borders. */
    private void follow() {
        sizeContent();
        if (popup.isShowing()) {
            popup.setX(contentX());
            popup.setY(contentY());
        }
    }

    private void sizeContent() {
        Scene scene = owner.getScene();
        content.setMinSize(scene.getWidth(), scene.getHeight());
        content.setPrefSize(scene.getWidth(), scene.getHeight());
        content.setMaxSize(scene.getWidth(), scene.getHeight());
    }

    private double contentX() {
        return owner.getX() + owner.getScene().getX();
    }

    private double contentY() {
        return owner.getY() + owner.getScene().getY();
    }
}
