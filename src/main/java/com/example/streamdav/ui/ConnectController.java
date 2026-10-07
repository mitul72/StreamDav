package com.example.streamdav.ui;

import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.settings.ServerProfile;
import com.example.streamdav.settings.Settings;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

public class ConnectController {
    @FXML private Button libraryButton;
    @FXML private ListView<ServerProfile> savedServers;
    @FXML private Button removeServerButton;
    @FXML private TextField urlField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField nameField;
    @FXML private CheckBox saveServerCheck;
    @FXML private CheckBox rememberPasswordCheck;
    @FXML private CheckBox autoConnectCheck;
    @FXML private Label passwordHint;
    @FXML private Button connectButton;
    @FXML private ProgressIndicator progress;
    @FXML private Label progressLabel;
    @FXML private Button cancelButton;
    @FXML private Label statusLabel;

    private Navigator navigator;
    private Task<?> pending;

    void init(Navigator navigator) {
        this.navigator = navigator;
        libraryButton.setGraphic(Icon.LIBRARY.create(18));
        libraryButton.setVisible(navigator.hasLibrary());
        libraryButton.setManaged(libraryButton.isVisible());
        rememberPasswordCheck.disableProperty().bind(saveServerCheck.selectedProperty().not());
        passwordHint.visibleProperty().bind(rememberPasswordCheck.selectedProperty().and(saveServerCheck.selectedProperty()));
        passwordHint.managedProperty().bind(passwordHint.visibleProperty());
        // Connecting unattended needs the password, so autoconnect implies remembering it.
        autoConnectCheck.disableProperty().bind(saveServerCheck.selectedProperty().not());
        autoConnectCheck.selectedProperty().addListener((observable, was, selected) -> {
            if (selected && !usernameField.getText().isBlank()) {
                rememberPasswordCheck.setSelected(true);
            }
        });
        rememberPasswordCheck.selectedProperty().addListener((observable, was, selected) -> {
            if (!selected && !usernameField.getText().isBlank()) {
                autoConnectCheck.setSelected(false);
            }
        });

        savedServers.setCellFactory(list -> new ServerCell());
        savedServers.setPlaceholder(new Label("Servers you connect to will appear here."));
        savedServers.getSelectionModel().selectedItemProperty().addListener((observable, old, server) -> {
            if (server != null) {
                fill(server);
            }
        });
        savedServers.setOnMouseClicked(event -> {
            ServerProfile server = savedServers.getSelectionModel().getSelectedItem();
            if (event.getClickCount() == 2 && server != null) {
                if (!server.username().isEmpty() && server.password().isEmpty()) {
                    statusLabel.setText("Enter the password for " + server.username() + ".");
                    passwordField.requestFocus();
                } else {
                    connect();
                }
            }
        });
        removeServerButton.disableProperty().bind(savedServers.getSelectionModel().selectedItemProperty().isNull());
        cancelButton.managedProperty().bind(cancelButton.visibleProperty());
        savedServers.getItems().setAll(navigator.settings().servers());
        Platform.runLater(urlField::requestFocus);
    }

    @FXML
    private void connect() {
        // Double-clicking a saved server or pressing Enter can ask again while a connection is underway.
        if (pending != null) {
            return;
        }
        URI root;
        try {
            root = parseServerUrl(urlField.getText());
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
            return;
        }
        String username = usernameField.getText().strip();
        String password = passwordField.getText();
        String name = nameField.getText().isBlank() ? root.getHost() : nameField.getText().strip();
        boolean save = saveServerCheck.isSelected();
        boolean rememberPassword = rememberPasswordCheck.isSelected();
        boolean autoConnect = save && autoConnectCheck.isSelected();

        Task<Connection> task = new Task<>() {
            @Override
            protected Connection call() throws Exception {
                MediaLibrary library = navigator.connector().open(root, username, password);
                try {
                    return new Connection(library, library.list(library.root()));
                } catch (Exception e) {
                    library.close();
                    throw e;
                }
            }
        };
        task.setOnSucceeded(event -> {
            if (pending != task) {
                task.getValue().library().close();
                return;
            }
            setBusy(false, null);
            String id = save ? navigator.settings().saveServer(name, root.toString(), username,
                    rememberPassword ? password : "") : null;
            if (save) {
                Settings settings = navigator.settings();
                if (autoConnect) {
                    settings.setAutoConnect(id);
                } else if (settings.autoConnectServer().filter(server -> server.id().equals(id)).isPresent()) {
                    settings.setAutoConnect(null);
                }
            }
            Connection connection = task.getValue();
            navigator.showBrowser(connection.library(), new Navigator.Session(id, name, root, username, password),
                    connection.rootListing());
        });
        task.setOnFailed(event -> {
            if (pending != task) {
                return;
            }
            setBusy(false, null);
            statusLabel.setText(Errors.describe(task.getException()));
        });
        task.setOnCancelled(event -> {
            if (pending == task) {
                setBusy(false, null);
            }
        });
        pending = task;
        setBusy(true, "Connecting to " + name + "…");
        navigator.background().execute(task);
    }

    /** Connects to a saved server straight away, as if the user had double-clicked it. */
    void autoConnect(ServerProfile server) {
        savedServers.getItems().stream()
                .filter(saved -> saved.id().equals(server.id()))
                .findFirst()
                .ifPresent(saved -> {
                    savedServers.getSelectionModel().select(saved);
                    connect();
                });
    }

    @FXML
    private void cancel() {
        if (pending != null) {
            pending.cancel();
        }
    }

    @FXML
    private void showLibrary() {
        cancel();
        navigator.showLibrary();
    }

    @FXML
    private void removeServer() {
        ServerProfile server = savedServers.getSelectionModel().getSelectedItem();
        if (server != null) {
            navigator.settings().removeServer(server);
            navigator.serverRemoved(server.id());
            savedServers.getItems().remove(server);
        }
    }

    /** Validates what the user typed and normalises it to a folder URL ending in a slash. */
    static URI parseServerUrl(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Enter the server's address.");
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        URI uri;
        try {
            uri = new URI(value.replace(" ", "%20"));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("That doesn't look like a valid address.");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("The address must start with http:// or https://.");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("The address is missing a host name.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Enter the username and password in their own fields, not in the address.");
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return URI.create(scheme + "://" + uri.getRawAuthority() + (path.endsWith("/") ? path : path + "/"));
    }

    private void fill(ServerProfile server) {
        urlField.setText(server.url());
        usernameField.setText(server.username());
        passwordField.setText(server.password());
        nameField.setText(server.name());
        saveServerCheck.setSelected(true);
        rememberPasswordCheck.setSelected(!server.password().isEmpty());
        autoConnectCheck.setSelected(server.autoConnect());
        statusLabel.setText("");
    }

    private void setBusy(boolean busy, String message) {
        connectButton.setDisable(busy);
        progress.setVisible(busy);
        progressLabel.setText(busy ? message : "");
        cancelButton.setVisible(busy);
        if (busy) {
            statusLabel.setText("");
        } else {
            pending = null;
        }
    }

    private record Connection(MediaLibrary library, List<RemoteFile> rootListing) {
    }

    private static final class ServerCell extends ListCell<ServerProfile> {
        @Override
        protected void updateItem(ServerProfile server, boolean empty) {
            super.updateItem(server, empty);
            setText(null);
            if (empty || server == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(server.name());
            name.getStyleClass().add("server-name");
            HBox title = new HBox(8, name);
            title.setAlignment(Pos.CENTER_LEFT);
            if (server.autoConnect()) {
                Label badge = new Label("Autoconnect");
                badge.getStyleClass().add("badge");
                title.getChildren().add(badge);
            }
            Label detail = new Label(server.username().isEmpty() ? server.url() : server.username() + " · " + server.url());
            detail.getStyleClass().add("server-detail");
            setGraphic(new VBox(2, title, detail));
        }
    }
}
