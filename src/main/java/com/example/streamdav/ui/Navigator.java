package com.example.streamdav.ui;

import com.example.streamdav.catalog.LibraryIndex;
import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.player.Playback;
import com.example.streamdav.player.Players;
import com.example.streamdav.settings.AppDirs;
import com.example.streamdav.settings.ServerProfile;
import com.example.streamdav.settings.Settings;
import com.example.streamdav.store.LibraryStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the window and switches between the library, connect, browse and play screens. */
public final class Navigator {
    private static final Logger log = LogManager.getLogger(Navigator.class);
    private static final String APP_NAME = "StreamDav";

    /**
     * The server the browser is connected to.
     *
     * @param serverId null when the user chose not to save the server
     */
    record Session(String serverId, String name, URI url, String username, String password) {
    }

    private final Stage stage;
    private final Settings settings;
    private final MediaLibrary.Connector connector;
    private final Players players;
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
    private final ImageCache images = new ImageCache(AppDirs.cache().resolve("artwork"));
    /** Connections to saved servers for playing library files, opened when first needed. */
    private final Map<String, MediaLibrary> connections = new HashMap<>();
    private final LibraryModel libraryModel;

    private MediaLibrary library;
    private Session session;
    private Parent browserView;
    private BrowserController browser;
    private Parent libraryView;
    private LibraryController libraryController;
    private PlayerController player;
    /** The player's controls, when the video is drawn straight into the window below them. */
    private VideoOverlay videoOverlay;
    /** The screen the player goes back to. */
    private Parent beforePlayer;
    private String titleBeforePlayer;

    public Navigator(Stage stage, Settings settings, MediaLibrary.Connector connector, Players players) {
        this.stage = stage;
        this.settings = settings;
        this.connector = connector;
        this.players = players;
        this.libraryModel = openLibrary(settings, connector);
    }

    private static LibraryModel openLibrary(Settings settings, MediaLibrary.Connector connector) {
        try {
            return new LibraryModel(LibraryStore.open(AppDirs.data().resolve("library.db")), settings, connector);
        } catch (IOException e) {
            // The app still browses and plays without a library.
            log.error("Could not open the library database", e);
            return null;
        }
    }

    public static String stylesheet() {
        return Objects.requireNonNull(Navigator.class.getResource("streamdav.css")).toExternalForm();
    }

    /**
     * Shows the library when it has folders, updating it in the background; otherwise the connect screen,
     * connecting straight away if the user picked a server to autoconnect to.
     */
    public void start() {
        if (hasLibrary()) {
            showLibrary();
            libraryModel.refresh();
            return;
        }
        ConnectController connect = showConnect();
        settings.autoConnectServer().ifPresent(connect::autoConnect);
    }

    /** Whether there's a library with folders in it to go back to. */
    boolean hasLibrary() {
        return libraryModel != null && libraryModel.hasSources();
    }

    void showLibrary() {
        if (libraryModel == null) {
            showError("The library isn't available", "Its database couldn't be opened; see the log for details.");
            return;
        }
        if (libraryView == null) {
            Loaded<LibraryController> view = load("library-view.fxml");
            libraryController = view.controller();
            libraryView = view.root();
            libraryController.init(this, libraryModel, images);
            libraryModel.load();
        }
        player = null;
        show(libraryView, "Library — " + APP_NAME);
        libraryController.focus();
    }

    void showDetail(LibraryIndex.Item item) {
        DetailView detail = new DetailView(this, images, item);
        show(detail.root(), item.title() + " — " + APP_NAME);
        detail.focus();
    }

    ConnectController showConnect() {
        if (library != null) {
            library.close();
        }
        library = null;
        session = null;
        browser = null;
        browserView = null;
        Loaded<ConnectController> view = load("connect-view.fxml");
        view.controller().init(this);
        show(view.root(), APP_NAME);
        return view.controller();
    }

    void showBrowser(MediaLibrary library, Session session, List<RemoteFile> rootListing) {
        this.library = library;
        this.session = session;
        Loaded<BrowserController> view = load("browser-view.fxml");
        browser = view.controller();
        browserView = view.root();
        browser.init(this, library, session.name(), rootListing);
        returnToBrowser();
    }

    void returnToBrowser() {
        player = null;
        show(browserView, session.name() + " — " + APP_NAME);
        browser.focus();
    }

    /** Plays a file from the browser's server. */
    void play(RemoteFile file) {
        play(file, library);
    }

    /** Plays a library file, connecting to its server if need be. */
    void play(LibraryIndex.FileRef file) {
        connection(file.serverId()).ifPresent(server -> play(file.file(), server));
    }

    /** Plays in the built-in player when it supports the format, otherwise hands off to an external player. */
    private void play(RemoteFile file, MediaLibrary from) {
        Optional<Playback.Factory> engine = players.forFile(file.name());
        if (engine.isEmpty()) {
            openExternally(file, from);
            return;
        }
        beforePlayer = stage.getScene().getRoot();
        titleBeforePlayer = stage.getTitle();
        Loaded<PlayerController> view = load("player-view.fxml");
        player = view.controller();
        if (engine.get().drawsOnWindow()) {
            // mpv covers the window's content with the picture; the controls go in a window above it.
            StackPane backdrop = new StackPane();
            backdrop.getStyleClass().add("player-backdrop");
            PlayerController current = player;
            backdrop.addEventHandler(KeyEvent.KEY_PRESSED, current::handleKey);
            show(backdrop, file.name() + " — " + APP_NAME);
            videoOverlay = new VideoOverlay(stage, (Region) view.root());
            player.init(this, file, engine.get(), from.streamUrl(file));
            videoOverlay.show();
        } else {
            player.init(this, file, engine.get(), from.streamUrl(file));
            show(view.root(), file.name() + " — " + APP_NAME);
        }
        player.focus();
    }

    /** Goes back to the screen the player was opened from. */
    void closePlayer() {
        player = null;
        closeVideoOverlay();
        if (beforePlayer == browserView && browser != null) {
            returnToBrowser();
            return;
        }
        show(beforePlayer, titleBeforePlayer);
        beforePlayer.requestFocus();
    }

    private void closeVideoOverlay() {
        if (videoOverlay != null) {
            videoOverlay.close();
            videoOverlay = null;
        }
    }

    void openExternally(RemoteFile file) {
        openExternally(file, library);
    }

    void openExternally(LibraryIndex.FileRef file) {
        connection(file.serverId()).ifPresent(server -> openExternally(file.file(), server));
    }

    private void openExternally(RemoteFile file, MediaLibrary from) {
        openExternally(file, from.streamUrl(file));
    }

    /** Uses the same server URL as the embedded player, including files opened from the library. */
    void openExternally(RemoteFile file, URI streamUrl) {
        Optional<String> command = settings.externalPlayerCommand().or(ExternalPlayer::detect);
        if (command.isEmpty()) {
            command = promptForExternalPlayer("No external player was found. Install mpv or VLC, "
                    + "or enter the command for the player you'd like to use.");
        }
        if (command.isEmpty()) {
            return;
        }
        try {
            ExternalPlayer.launch(command.get(), streamUrl);
        } catch (IOException e) {
            log.warn("Could not start external player '{}'", command.get(), e);
            showError("Couldn't start the external player", e.getMessage());
        }
    }

    private Optional<MediaLibrary> connection(String serverId) {
        MediaLibrary open = connections.get(serverId);
        if (open != null) {
            return Optional.of(open);
        }
        try {
            MediaLibrary server = libraryModel.open(serverId);
            connections.put(serverId, server);
            return Optional.of(server);
        } catch (IOException e) {
            log.warn("Could not open server {}", serverId, e);
            showError("Couldn't connect to the server", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Adds a folder of the connected server to the library. The library scans in the background, so the server
     * has to be saved with its password.
     */
    void addToLibrary(URI folder, String name) {
        if (libraryModel == null) {
            showError("The library isn't available", "Its database couldn't be opened; see the log for details.");
            return;
        }
        if (session.serverId() == null) {
            showError("Save this server first",
                    "The library scans its folders in the background, so the server needs to be saved. Connect "
                            + "again with “Save this server” ticked.");
            return;
        }
        Optional<ServerProfile> saved = settings.servers().stream()
                .filter(server -> server.id().equals(session.serverId())).findFirst();
        if (saved.isPresent() && !session.username().isEmpty() && saved.get().password().isEmpty()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "The library scans this server in the background, so StreamDav needs to remember its password. "
                            + "It's stored unencrypted in your user preferences.", ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("Remember the password for " + session.name() + "?");
            if (showDialog(confirm).filter(ButtonType.OK::equals).isEmpty()) {
                return;
            }
            settings.saveServer(saved.get().name(), saved.get().url(), saved.get().username(), session.password());
        }
        libraryModel.addSource(session.serverId(), folder, name);
        showLibrary();
    }

    /** Forgets a removed server's library folders and connection. */
    void serverRemoved(String serverId) {
        if (libraryModel != null) {
            libraryModel.removeServer(serverId);
        }
        MediaLibrary open = connections.remove(serverId);
        if (open != null) {
            open.close();
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

    void showError(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(header);
        showDialog(alert);
    }

    <R> Optional<R> showDialog(Dialog<R> dialog) {
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
        closeVideoOverlay();
        if (library != null) {
            library.close();
        }
        connections.values().forEach(MediaLibrary::close);
        if (libraryModel != null) {
            libraryModel.close();
        }
        images.close();
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
