package com.example.streamdav.ui;

import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.player.Playback;
import com.example.streamdav.player.Players;
import com.example.streamdav.settings.Settings;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the window and switches between the connect, browse and play screens. */
public final class Navigator {
    private static final Logger log = LogManager.getLogger(Navigator.class);
    private static final String APP_NAME = "StreamDav";

    private final Stage stage;
    private final Settings settings;
    private final MediaLibrary.Connector connector;
    private final Players players;
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    private MediaLibrary library;
    private String serverName;
    private Parent browserView;
    private BrowserController browser;
    private PlayerController player;

    public Navigator(Stage stage, Settings settings, MediaLibrary.Connector connector, Players players) {
        this.stage = stage;
        this.settings = settings;
        this.connector = connector;
        this.players = players;
    }

    public static String stylesheet() {
        return Objects.requireNonNull(Navigator.class.getResource("streamdav.css")).toExternalForm();
    }

    /** Shows the first screen, connecting straight away if the user picked a server to autoconnect to. */
    public void start() {
        ConnectController connect = showConnect();
        settings.autoConnectServer().ifPresent(connect::autoConnect);
    }

    ConnectController showConnect() {
        library = null;
        browser = null;
        browserView = null;
        Loaded<ConnectController> view = load("connect-view.fxml");
        view.controller().init(this);
        show(view.root(), APP_NAME);
        return view.controller();
    }

    void showBrowser(MediaLibrary library, String serverName, List<RemoteFile> rootListing) {
        this.library = library;
        this.serverName = serverName;
        Loaded<BrowserController> view = load("browser-view.fxml");
        browser = view.controller();
        browserView = view.root();
        browser.init(this, library, serverName, rootListing);
        returnToBrowser();
    }

    void returnToBrowser() {
        player = null;
        show(browserView, serverName + " — " + APP_NAME);
        browser.focus();
    }

    /** Plays in the built-in player when it supports the format, otherwise hands off to an external player. */
    void play(RemoteFile file) {
        Optional<Playback.Factory> engine = players.forFile(file.name());
        if (engine.isEmpty()) {
            openExternally(file);
            return;
        }
        Loaded<PlayerController> view = load("player-view.fxml");
        player = view.controller();
        player.init(this, file, engine.get(), library.streamUrl(file));
        show(view.root(), file.name() + " — " + APP_NAME);
        player.focus();
    }

    void openExternally(RemoteFile file) {
        Optional<String> command = settings.externalPlayerCommand().or(ExternalPlayer::detect);
        if (command.isEmpty()) {
            command = promptForExternalPlayer("No external player was found. Install mpv or VLC, "
                    + "or enter the command for the player you'd like to use.");
        }
        if (command.isEmpty()) {
            return;
        }
        try {
            ExternalPlayer.launch(command.get(), library.streamUrl(file));
        } catch (IOException e) {
            log.warn("Could not start external player '{}'", command.get(), e);
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
            alert.setHeaderText("Couldn't start the external player");
            showDialog(alert);
        }
    }

    void configureExternalPlayer() {
        promptForExternalPlayer("Used for formats the built-in player can't play, such as MKV. "
                + "The stream URL is added as the last argument.");
    }

    private Optional<String> promptForExternalPlayer(String message) {
        TextInputDialog dialog = new TextInputDialog(settings.externalPlayerCommand().or(ExternalPlayer::detect).orElse(""));
        dialog.setTitle("External player");
        dialog.setHeaderText(message);
        dialog.setContentText("Command:");
        Optional<String> answer = showDialog(dialog);
        if (answer.isEmpty()) {
            return Optional.empty();
        }
        // Saving a blank command goes back to detecting the player automatically.
        settings.setExternalPlayerCommand(answer.get());
        return settings.externalPlayerCommand().or(ExternalPlayer::detect);
    }

    private <R> Optional<R> showDialog(Dialog<R> dialog) {
        dialog.initOwner(stage);
        dialog.getDialogPane().getStylesheets().add(stylesheet());
        return dialog.showAndWait();
    }

    MediaLibrary.Connector connector() {
        return connector;
    }

    Settings settings() {
        return settings;
    }

    Stage stage() {
        return stage;
    }

    Executor background() {
        return background;
    }

    /** Called when the app exits, so the current playback position is remembered. */
    public void shutdown() {
        if (player != null) {
            player.shutdown();
        }
        background.shutdownNow();
    }

    private void show(Parent root, String title) {
        stage.getScene().setRoot(root);
        stage.setTitle(title);
    }

    private <C> Loaded<C> load(String fxml) {
        FXMLLoader loader = new FXMLLoader(Navigator.class.getResource(fxml));
        try {
            Parent root = loader.load();
            return new Loaded<>(root, loader.getController());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + fxml, e);
        }
    }

    private record Loaded<C>(Parent root, C controller) {
    }
}
