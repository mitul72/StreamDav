package com.example.streamdav.ui;

import com.example.streamdav.library.RemoteFile;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;

public class PlayerController {
    private static final Logger log = LogManager.getLogger(PlayerController.class);
    private static final double SKIP_SECONDS = 10;
    private static final double VOLUME_STEP = 0.05;
    /** Positions this close to either end aren't worth resuming from. */
    private static final double RESUME_MARGIN_SECONDS = 30;

    @FXML private StackPane root;
    @FXML private MediaView mediaView;
    @FXML private VBox audioPane;
    @FXML private Label audioTitle;
    @FXML private ProgressIndicator buffering;
    @FXML private BorderPane overlay;
    @FXML private Button closeButton;
    @FXML private Label titleLabel;
    @FXML private VBox controls;
    @FXML private Slider seekSlider;
    @FXML private Button playButton;
    @FXML private Label timeLabel;
    @FXML private Button muteButton;
    @FXML private Slider volumeSlider;
    @FXML private Button externalButton;
    @FXML private Button fullScreenButton;
    @FXML private Label toastLabel;
    @FXML private VBox errorPane;
    @FXML private Label errorLabel;

    private final PauseTransition hideTimer = new PauseTransition(Duration.seconds(3));
    private final PauseTransition toastTimer = new PauseTransition(Duration.seconds(3));
    private final FadeTransition fadeOut = new FadeTransition(Duration.millis(300));
    private final ChangeListener<Boolean> fullScreenListener = (observable, old, fullScreen) ->
            fullScreenButton.setGraphic((fullScreen ? Icon.EXIT_FULLSCREEN : Icon.FULLSCREEN).create(24));
    private Navigator navigator;
    private RemoteFile file;
    private MediaPlayer player;
    private boolean audioOnly;
    private boolean failed;
    private boolean updatingSeekSlider;
    private boolean closed;

    void init(Navigator navigator, RemoteFile file, URI streamUrl) {
        this.navigator = navigator;
        this.file = file;
        titleLabel.setText(file.name());
        audioTitle.setText(file.name());
        audioPane.getChildren().addFirst(Icon.AUDIO.create(96));
        closeButton.setGraphic(Icon.BACK.create(24));
        playButton.setGraphic(Icon.PLAY.create(30));
        muteButton.setGraphic(Icon.VOLUME.create(22));
        externalButton.setGraphic(Icon.EXTERNAL.create(22));
        fullScreenListener.changed(null, null, navigator.stage().isFullScreen());
        navigator.stage().fullScreenProperty().addListener(fullScreenListener);
        mediaView.fitWidthProperty().bind(root.widthProperty());
        mediaView.fitHeightProperty().bind(root.heightProperty());

        fadeOut.setNode(overlay);
        fadeOut.setToValue(0);
        hideTimer.setOnFinished(event -> hideControls());
        toastTimer.setOnFinished(event -> toastLabel.setVisible(false));
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        root.addEventFilter(MouseEvent.MOUSE_MOVED, event -> showControls());
        root.setOnMouseClicked(this::onMouseClicked);
        configureSeekSlider();

        try {
            Media media = new Media(streamUrl.toString());
            media.setOnError(() -> showError(media.getError()));
            player = new MediaPlayer(media);
        } catch (MediaException e) {
            showError(e);
            return;
        }
        player.setOnReady(this::onReady);
        player.setOnError(() -> showError(player.getError()));
        player.setOnEndOfMedia(this::onEndOfMedia);
        player.statusProperty().addListener((observable, old, status) -> onStatusChanged(status));
        player.currentTimeProperty().addListener((observable, old, time) -> updatePosition(time));
        player.muteProperty().addListener((observable, old, muted) ->
                muteButton.setGraphic((muted ? Icon.MUTED : Icon.VOLUME).create(22)));
        volumeSlider.valueProperty().bindBidirectional(player.volumeProperty());
        mediaView.setMediaPlayer(player);
        buffering.setVisible(true);
    }

    void focus() {
        root.requestFocus();
    }

    /** Remembers the playback position and releases the player. Safe to call more than once. */
    void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        hideTimer.stop();
        toastTimer.stop();
        navigator.stage().fullScreenProperty().removeListener(fullScreenListener);
        navigator.stage().setFullScreen(false);
        if (player != null) {
            saveProgress();
            player.dispose();
        }
    }

    private void configureSeekSlider() {
        seekSlider.valueChangingProperty().addListener((observable, wasChanging, changing) -> {
            if (!changing) {
                seek(seekSlider.getValue());
            }
        });
        seekSlider.valueProperty().addListener((observable, old, value) -> {
            if (updatingSeekSlider) {
                return;
            }
            if (seekSlider.isValueChanging()) {
                // Preview the time under the thumb while dragging; seek once on release.
                timeLabel.setText(Format.duration(Duration.seconds(value.doubleValue())) + " / " + totalTime());
            } else {
                seek(value.doubleValue());
            }
        });
    }

    private void onReady() {
        buffering.setVisible(false);
        Media media = player.getMedia();
        audioOnly = media.getWidth() == 0 && media.getHeight() == 0;
        audioPane.setVisible(audioOnly);
        mediaView.setVisible(!audioOnly);

        Duration total = media.getDuration();
        seekSlider.setDisable(!isKnown(total));
        if (isKnown(total)) {
            seekSlider.setMax(total.toSeconds());
            long resumeMillis = navigator.settings().resumeMillis(file.uri());
            if (resumeMillis > 0 && resumeMillis / 1000.0 < total.toSeconds() - RESUME_MARGIN_SECONDS) {
                player.seek(Duration.millis(resumeMillis));
                toast("Resumed from " + Format.duration(Duration.millis(resumeMillis)));
            }
        }
        updatePosition(player.getCurrentTime());
        player.play();
    }

    private void onStatusChanged(MediaPlayer.Status status) {
        switch (status) {
            case PLAYING -> {
                playButton.setGraphic(Icon.PAUSE.create(30));
                buffering.setVisible(false);
                scheduleHide();
            }
            case STALLED -> buffering.setVisible(true);
            default -> {
                playButton.setGraphic(Icon.PLAY.create(30));
                buffering.setVisible(false);
                showControls();
            }
        }
    }

    private void onEndOfMedia() {
        navigator.settings().clearResume(file.uri());
        player.stop();
        showControls();
    }

    private void updatePosition(Duration time) {
        if (!seekSlider.isValueChanging()) {
            updatingSeekSlider = true;
            seekSlider.setValue(time.toSeconds());
            updatingSeekSlider = false;
        }
        timeLabel.setText(Format.duration(time) + " / " + totalTime());
    }

    private String totalTime() {
        return Format.duration(player == null ? null : player.getTotalDuration());
    }

    private void saveProgress() {
        Duration total = player.getTotalDuration();
        if (failed || !isKnown(total)) {
            return;
        }
        double position = player.getCurrentTime().toSeconds();
        if (position < RESUME_MARGIN_SECONDS || position > total.toSeconds() - RESUME_MARGIN_SECONDS) {
            navigator.settings().clearResume(file.uri());
        } else {
            navigator.settings().setResumeMillis(file.uri(), (long) (position * 1000));
        }
    }

    private void onKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case SPACE, K -> togglePlay();
            case LEFT, J -> skip(-SKIP_SECONDS);
            case RIGHT, L -> skip(SKIP_SECONDS);
            case UP -> changeVolume(VOLUME_STEP);
            case DOWN -> changeVolume(-VOLUME_STEP);
            case M -> toggleMute();
            case F, F11 -> toggleFullScreen();
            case ESCAPE -> {
                if (navigator.stage().isFullScreen()) {
                    navigator.stage().setFullScreen(false);
                } else {
                    close();
                }
            }
            default -> {
                return;
            }
        }
        event.consume();
        showControls();
    }

    private void onMouseClicked(MouseEvent event) {
        // Only clicks on the picture itself; buttons and sliders handle their own.
        if (event.getButton() != MouseButton.PRIMARY || (event.getTarget() != root && event.getTarget() != mediaView)) {
            return;
        }
        if (event.getClickCount() == 2) {
            toggleFullScreen();
            togglePlay(); // undo the pause from the first click of the double-click
        } else {
            togglePlay();
        }
    }

    @FXML
    private void togglePlay() {
        if (player == null || failed) {
            return;
        }
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
        } else {
            player.play();
        }
    }

    @FXML
    private void toggleMute() {
        if (player != null) {
            player.setMute(!player.isMute());
        }
    }

    @FXML
    private void toggleFullScreen() {
        navigator.stage().setFullScreen(!navigator.stage().isFullScreen());
    }

    @FXML
    private void openExternally() {
        if (player != null && !failed) {
            player.pause();
        }
        navigator.openExternally(file);
    }

    @FXML
    private void close() {
        if (closed) {
            return;
        }
        shutdown();
        navigator.returnToBrowser();
    }

    private void seek(double seconds) {
        if (player != null && !failed) {
            player.seek(Duration.seconds(seconds));
        }
    }

    private void skip(double seconds) {
        if (player == null || failed || !isKnown(player.getTotalDuration())) {
            return;
        }
        double target = player.getCurrentTime().toSeconds() + seconds;
        seek(Math.clamp(target, 0, player.getTotalDuration().toSeconds()));
    }

    private void changeVolume(double delta) {
        if (player != null) {
            player.setMute(false);
            player.setVolume(Math.clamp(player.getVolume() + delta, 0, 1));
        }
    }

    private void showControls() {
        fadeOut.stop();
        overlay.setOpacity(1);
        root.setCursor(Cursor.DEFAULT);
        scheduleHide();
    }

    private void scheduleHide() {
        if (isPlayingVideo()) {
            hideTimer.playFromStart();
        } else {
            hideTimer.stop();
        }
    }

    private void hideControls() {
        if (isPlayingVideo()) {
            fadeOut.playFromStart();
            root.setCursor(Cursor.NONE);
        }
    }

    private boolean isPlayingVideo() {
        return player != null && !failed && !audioOnly && player.getStatus() == MediaPlayer.Status.PLAYING;
    }

    private void toast(String message) {
        toastLabel.setText(message);
        toastLabel.setVisible(true);
        toastTimer.playFromStart();
    }

    private void showError(MediaException error) {
        if (failed) {
            return;
        }
        failed = true;
        log.warn("Playback failed for {}", file.uri(), error);
        buffering.setVisible(false);
        controls.setVisible(false);
        showControls();
        errorLabel.setText(describe(error) + "\nYou can open it in an external player such as mpv or VLC instead.");
        errorPane.setVisible(true);
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

    private static boolean isKnown(Duration duration) {
        return duration != null && !duration.isUnknown() && !duration.isIndefinite();
    }
}
