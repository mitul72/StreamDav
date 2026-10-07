package com.example.streamdav.ui;

import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.player.Playback;
import com.example.streamdav.player.Playback.Status;
import com.example.streamdav.player.Track;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.net.URI;
import java.util.List;

public class PlayerController {
    private static final double SKIP_SECONDS = 10;
    private static final double VOLUME_STEP = 0.05;
    /** Positions this close to either end aren't worth resuming from. */
    private static final double RESUME_MARGIN_SECONDS = 30;

    @FXML private StackPane root;
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
    @FXML private MenuButton audioMenu;
    @FXML private MenuButton subtitleMenu;
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
    private Playback playback;
    private Duration resumeFrom = Duration.ZERO;
    private boolean updatingSeekSlider;
    private boolean closed;

    void init(Navigator navigator, RemoteFile file, Playback.Factory engine, URI streamUrl) {
        this.navigator = navigator;
        this.file = file;
        titleLabel.setText(file.name());
        audioTitle.setText(file.name());
        audioPane.getChildren().addFirst(Icon.AUDIO.create(96));
        closeButton.setGraphic(Icon.BACK.create(24));
        playButton.setGraphic(Icon.PLAY.create(30));
        muteButton.setGraphic(Icon.VOLUME.create(22));
        externalButton.setGraphic(Icon.EXTERNAL.create(22));
        audioMenu.setGraphic(Icon.AUDIO_TRACKS.create(22));
        subtitleMenu.setGraphic(Icon.SUBTITLES.create(22));
        for (MenuButton menu : List.of(audioMenu, subtitleMenu)) {
            menu.managedProperty().bind(menu.visibleProperty());
            // Keep the controls up while a menu is open.
            menu.showingProperty().addListener((observable, was, showing) -> showControls());
        }
        fullScreenListener.changed(null, null, navigator.stage().isFullScreen());
        navigator.stage().fullScreenProperty().addListener(fullScreenListener);

        fadeOut.setNode(overlay);
        fadeOut.setToValue(0);
        hideTimer.setOnFinished(event -> hideControls());
        toastTimer.setOnFinished(event -> toastLabel.setVisible(false));
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        root.addEventFilter(MouseEvent.MOUSE_MOVED, event -> showControls());
        root.setOnMouseClicked(this::onMouseClicked);
        configureSeekSlider();

        long resumeMillis = navigator.settings().resumeMillis(file.uri());
        resumeFrom = Duration.millis(resumeMillis);
        playback = engine.open(streamUrl, resumeFrom);
        root.getChildren().addFirst(playback.view());
        // Ignore anything the engine reports after this screen has closed (events already queued, say).
        playback.statusProperty().addListener((observable, old, status) -> ifOpen(() -> onStatusChanged(status)));
        playback.positionProperty().addListener((observable, old, time) -> ifOpen(() -> updatePosition(time)));
        playback.durationProperty().addListener((observable, old, total) -> ifOpen(() -> onDurationChanged(total)));
        playback.audioOnlyProperty().addListener((observable, old, audio) -> ifOpen(() -> audioPane.setVisible(audio)));
        playback.muteProperty().addListener((observable, old, muted) -> ifOpen(() ->
                muteButton.setGraphic((muted ? Icon.MUTED : Icon.VOLUME).create(22))));
        volumeSlider.valueProperty().bindBidirectional(playback.volumeProperty());
        playback.tracks().addListener((ListChangeListener<Track>) change -> ifOpen(this::updateTrackMenus));
        updateTrackMenus();
        onStatusChanged(playback.status());
    }

    private void ifOpen(Runnable update) {
        if (!closed) {
            update.run();
        }
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
        if (playback != null) {
            saveProgress();
            playback.dispose();
        }
    }

    private void configureSeekSlider() {
        seekSlider.setDisable(true);
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

    private void onStatusChanged(Status status) {
        buffering.setVisible(status == Status.LOADING || status == Status.BUFFERING);
        playButton.setGraphic((isPlayingOrTrying(status) ? Icon.PAUSE : Icon.PLAY).create(30));
        switch (status) {
            case PLAYING -> {
                if (resumeFrom.greaterThan(Duration.ZERO)) {
                    toast("Resumed from " + Format.duration(resumeFrom));
                    resumeFrom = Duration.ZERO;
                }
                scheduleHide();
            }
            case ENDED -> {
                navigator.settings().clearResume(file.uri());
                showControls();
            }
            case FAILED -> showError(playback.errorMessage());
            default -> showControls();
        }
    }

    private void onDurationChanged(Duration total) {
        seekSlider.setDisable(!isKnown(total));
        if (isKnown(total)) {
            // A shorter estimate clamps the slider's value; that's not the user seeking, so don't seek.
            updatingSeekSlider = true;
            seekSlider.setMax(total.toSeconds());
            updatingSeekSlider = false;
        }
        updatePosition(playback.positionProperty().get());
    }

    /** Offers a choice of audio only when there's more than one track, and subtitles whenever there are any. */
    private void updateTrackMenus() {
        List<Track> audio = playback.tracks().stream().filter(track -> track.kind() == Track.Kind.AUDIO).toList();
        List<Track> subtitles = playback.tracks().stream().filter(track -> track.kind() == Track.Kind.SUBTITLE).toList();
        fillTrackMenu(audioMenu, Track.Kind.AUDIO, audio, false);
        fillTrackMenu(subtitleMenu, Track.Kind.SUBTITLE, subtitles, true);
        audioMenu.setVisible(audio.size() > 1);
        subtitleMenu.setVisible(!subtitles.isEmpty());
    }

    private void fillTrackMenu(MenuButton menu, Track.Kind kind, List<Track> tracks, boolean canTurnOff) {
        ToggleGroup group = new ToggleGroup();
        menu.getItems().clear();
        if (canTurnOff) {
            RadioMenuItem off = new RadioMenuItem("Off");
            off.setToggleGroup(group);
            off.setSelected(tracks.stream().noneMatch(Track::selected));
            off.setOnAction(event -> playback.selectTrack(kind, null));
            menu.getItems().add(off);
        }
        for (Track track : tracks) {
            RadioMenuItem item = new RadioMenuItem(track.label());
            item.setToggleGroup(group);
            item.setSelected(track.selected());
            item.setOnAction(event -> playback.selectTrack(kind, track));
            menu.getItems().add(item);
        }
    }

    private void updatePosition(Duration time) {
        // While the user drags the slider, the label previews the time under the thumb; leave both alone.
        if (seekSlider.isValueChanging()) {
            return;
        }
        updatingSeekSlider = true;
        seekSlider.setValue(time.toSeconds());
        updatingSeekSlider = false;
        timeLabel.setText(Format.duration(time) + " / " + totalTime());
    }

    private String totalTime() {
        return Format.duration(playback == null ? null : playback.durationProperty().get());
    }

    private void saveProgress() {
        Duration total = playback.durationProperty().get();
        if (playback.status() == Status.FAILED || !isKnown(total)) {
            return;
        }
        double position = playback.positionProperty().get().toSeconds();
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
        if (event.getButton() != MouseButton.PRIMARY || !isOnPicture(event.getTarget())) {
            return;
        }
        if (event.getClickCount() == 2) {
            toggleFullScreen();
            togglePlay(); // undo the pause from the first click of the double-click
        } else {
            togglePlay();
        }
    }

    private boolean isOnPicture(Object target) {
        for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
            if (node == playback.view()) {
                return true;
            }
        }
        return target == root;
    }

    @FXML
    private void togglePlay() {
        if (playback == null || playback.status() == Status.FAILED) {
            return;
        }
        if (isPlayingOrTrying(playback.status())) {
            playback.pause();
        } else {
            playback.play();
        }
    }

    /** Loading and buffering count as playing: pressing pause then should pause. */
    private static boolean isPlayingOrTrying(Status status) {
        return status == Status.PLAYING || status == Status.BUFFERING || status == Status.LOADING;
    }

    @FXML
    private void toggleMute() {
        if (playback != null) {
            playback.muteProperty().set(!playback.muteProperty().get());
        }
    }

    @FXML
    private void toggleFullScreen() {
        navigator.stage().setFullScreen(!navigator.stage().isFullScreen());
    }

    @FXML
    private void openExternally() {
        if (playback != null && playback.status() == Status.PLAYING) {
            playback.pause();
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
        if (playback != null && playback.status() != Status.FAILED) {
            playback.seek(Duration.seconds(seconds));
        }
    }

    private void skip(double seconds) {
        if (playback == null || !isKnown(playback.durationProperty().get())) {
            return;
        }
        double target = playback.positionProperty().get().toSeconds() + seconds;
        seek(Math.clamp(target, 0, playback.durationProperty().get().toSeconds()));
    }

    private void changeVolume(double delta) {
        if (playback != null) {
            playback.muteProperty().set(false);
            playback.volumeProperty().set(Math.clamp(playback.volumeProperty().get() + delta, 0, 1));
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
        if (isPlayingVideo() && !audioMenu.isShowing() && !subtitleMenu.isShowing()) {
            fadeOut.playFromStart();
            root.setCursor(Cursor.NONE);
        }
    }

    private boolean isPlayingVideo() {
        return playback != null && playback.status() == Status.PLAYING && !playback.audioOnlyProperty().get();
    }

    private void toast(String message) {
        toastLabel.setText(message);
        toastLabel.setVisible(true);
        toastTimer.playFromStart();
    }

    private void showError(String message) {
        controls.setVisible(false);
        showControls();
        errorLabel.setText(message + "\nYou can open it in an external player such as mpv or VLC instead.");
        errorPane.setVisible(true);
    }

    private static boolean isKnown(Duration duration) {
        return duration != null && !duration.isUnknown() && !duration.isIndefinite();
    }
}
