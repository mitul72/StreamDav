package com.example.streamdav.ui;

import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import com.example.streamdav.settings.ServerProfile;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

public class ConnectController {
    @FXML private ListView<ServerProfile> savedServers;
    @FXML private Button removeServerButton;
    @FXML private TextField urlField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField nameField;
    @FXML private CheckBox saveServerCheck;
    @FXML private CheckBox rememberPasswordCheck;
    @FXML private Label passwordHint;
    @FXML private Button connectButton;
    @FXML private ProgressIndicator progress;
    @FXML private Label statusLabel;

    private Navigator navigator;

    void init(Navigator navigator) {
        this.navigator = navigator;
        rememberPasswordCheck.disableProperty().bind(saveServerCheck.selectedProperty().not());
        passwordHint.visibleProperty().bind(rememberPasswordCheck.selectedProperty().and(saveServerCheck.selectedProperty()));
        passwordHint.managedProperty().bind(passwordHint.visibleProperty());

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
        savedServers.getItems().setAll(navigator.settings().servers());
        Platform.runLater(urlField::requestFocus);
    }

    @FXML
    private void connect() {
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

        Task<Connection> task = new Task<>() {
            @Override
            protected Connection call() throws Exception {
                MediaLibrary library = navigator.connector().open(root, username, password);
                return new Connection(library, library.list(library.root()));
            }
        };
        task.setOnSucceeded(event -> {
            setBusy(false);
            if (save) {
                navigator.settings().saveServer(name, root.toString(), username, rememberPassword ? password : "");
            }
            Connection connection = task.getValue();
            navigator.showBrowser(connection.library(), name, connection.rootListing());
        });
        task.setOnFailed(event -> {
            setBusy(false);
            statusLabel.setText(Errors.describe(task.getException()));
        });
        setBusy(true);
        navigator.background().execute(task);
    }

    @FXML
    private void removeServer() {
        ServerProfile server = savedServers.getSelectionModel().getSelectedItem();
        if (server != null) {
            navigator.settings().removeServer(server);
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
        statusLabel.setText("");
    }

    private void setBusy(boolean busy) {
        connectButton.setDisable(busy);
        progress.setVisible(busy);
        if (busy) {
            statusLabel.setText("");
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
            Label detail = new Label(server.username().isEmpty() ? server.url() : server.username() + " · " + server.url());
            detail.getStyleClass().add("server-detail");
            setGraphic(new VBox(2, name, detail));
        }
    }
}
