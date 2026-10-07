package com.example.streamdav.ui;

import com.example.streamdav.catalog.LibraryIndex;
import com.example.streamdav.catalog.LibraryIndex.Category;
import com.example.streamdav.catalog.LibraryIndex.Item;
import com.example.streamdav.metadata.ApiKeys;
import com.example.streamdav.metadata.Metadata;
import com.example.streamdav.metadata.MetadataMatcher;
import com.example.streamdav.store.LibraryStore.Source;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The library's home screen: shelves of recent additions, movies, shows and anime, and a grid per category. */
public class LibraryController {
    private static final int SHELF_SIZE = 24;

    @FXML private BorderPane root;
    @FXML private ToggleGroup tabs;
    @FXML private ToggleButton homeTab;
    @FXML private ToggleButton moviesTab;
    @FXML private ToggleButton showsTab;
    @FXML private ToggleButton animeTab;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Label statusLabel;
    @FXML private TextField searchField;
    @FXML private Button refreshButton;
    @FXML private Button serversButton;
    @FXML private MenuButton menuButton;
    @FXML private StackPane content;

    private Navigator navigator;
    private LibraryModel model;
    private ImageCache images;
    /** Scroll positions per view, so a refresh that redraws the page doesn't jump to the top. */
    private final Map<String, Double> scroll = new HashMap<>();
    private ScrollPane current;
    private String currentView;

    void init(Navigator navigator, LibraryModel model, ImageCache images) {
        this.navigator = navigator;
        this.model = model;
        this.images = images;
        refreshButton.setGraphic(Icon.REFRESH.create(20));
        serversButton.setGraphic(Icon.SERVER.create(20));
        menuButton.setGraphic(Icon.MORE.create(20));

        // A selected tab stays selected: clicking it again shouldn't leave no tab chosen.
        tabs.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null) {
                was.setSelected(true);
            } else {
                searchField.clear();
                render();
            }
        });
        searchField.textProperty().addListener(observable -> render());
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                searchField.clear();
                root.requestFocus();
            }
        });
        model.indexProperty().addListener(observable -> render());
        model.emptyProperty().addListener(observable -> render());
        busyIndicator.visibleProperty().bind(model.busyProperty());
        busyIndicator.managedProperty().bind(model.busyProperty());
        statusLabel.textProperty().bind(model.statusProperty());
        refreshButton.disableProperty().bind(model.busyProperty());
        root.addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        render();
    }

    void focus() {
        root.requestFocus();
    }

    private void render() {
        rememberScroll();
        String query = searchField.getText() == null ? "" : searchField.getText().strip();
        LibraryIndex index = model.indexProperty().get();
        Node view;
        String name;
        if (model.emptyProperty().get()) {
            view = emptyState();
            name = "empty";
        } else if (!query.isEmpty()) {
            view = grid(search(index, query), "Nothing in your library matches “" + query + "”.");
            name = "search";
        } else if (tabs.getSelectedToggle() == moviesTab) {
            view = grid(index.category(Category.MOVIES), "No movies yet.");
            name = "movies";
        } else if (tabs.getSelectedToggle() == showsTab) {
            view = grid(index.category(Category.SHOWS), "No TV shows yet.");
            name = "shows";
        } else if (tabs.getSelectedToggle() == animeTab) {
            view = grid(index.category(Category.ANIME), "No anime yet.");
            name = "anime";
        } else {
            view = home(index);
            name = "home";
        }
        content.getChildren().setAll(view);
        currentView = name;
        current = view instanceof ScrollPane pane ? pane : null;
        if (current != null) {
            double position = scroll.getOrDefault(name, 0.0);
            // The content's size is only known after layout.
            current.applyCss();
            current.layout();
            current.setVvalue(position);
        }
    }

    private void rememberScroll() {
        if (current != null && currentView != null) {
            scroll.put(currentView, current.getVvalue());
        }
    }

    private Node home(LibraryIndex index) {
        VBox shelves = new VBox();
        shelves.getStyleClass().add("shelves");
        ScrollPane page = page(shelves);
        addShelf(shelves, page, "Recently Added", index.recentlyAdded(SHELF_SIZE), null);
        addShelf(shelves, page, "Movies", index.category(Category.MOVIES), moviesTab);
        addShelf(shelves, page, "TV Shows", index.category(Category.SHOWS), showsTab);
        addShelf(shelves, page, "Anime", index.category(Category.ANIME), animeTab);
        if (shelves.getChildren().isEmpty()) {
            return message(model.busyProperty().get() ? "Your library is being scanned…" : "No videos found in your library folders.");
        }
        return page;
    }

    private void addShelf(VBox shelves, ScrollPane page, String title, List<Item> items, ToggleButton seeAll) {
        if (items.isEmpty()) {
            return;
        }
        Label heading = new Label(title);
        heading.getStyleClass().add("shelf-title");
        HBox header = new HBox(12, heading);
        header.getStyleClass().add("shelf-header");
        header.setAlignment(Pos.BASELINE_LEFT);
        if (seeAll != null && items.size() > SHELF_SIZE) {
            Hyperlink link = new Hyperlink("See all " + items.size());
            link.setOnAction(event -> seeAll.setSelected(true));
            header.getChildren().add(link);
        }
        HBox row = new HBox(16);
        row.getStyleClass().add("shelf-row");
        items.stream().limit(SHELF_SIZE).forEach(item -> row.getChildren().add(card(item)));
        ScrollPane rowScroll = new ScrollPane(row);
        rowScroll.getStyleClass().add("shelf-scroll");
        rowScroll.setFitToHeight(true);
        rowScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rowScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // The mouse wheel scrolls the page, not the shelf under the pointer; sideways gestures scroll the shelf.
        rowScroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (Math.abs(event.getDeltaX()) < Math.abs(event.getDeltaY())) {
                double scrollable = page.getContent().getBoundsInLocal().getHeight() - page.getViewportBounds().getHeight();
                if (scrollable > 0) {
                    page.setVvalue(page.getVvalue() - event.getDeltaY() / scrollable);
                }
                event.consume();
            }
        });
        VBox shelf = new VBox(8, header, rowScroll);
        shelf.getStyleClass().add("shelf");
        shelves.getChildren().add(shelf);
    }

    private Node grid(List<Item> items, String emptyMessage) {
        if (items.isEmpty()) {
            return message(emptyMessage);
        }
        FlowPane grid = new FlowPane(20, 24);
        grid.getStyleClass().add("poster-grid");
        items.forEach(item -> grid.getChildren().add(card(item)));
        ScrollPane page = page(grid);
        grid.prefWrapLengthProperty().bind(page.widthProperty().subtract(60));
        return page;
    }

    private Node card(Item item) {
        return PosterCard.create(item, images, () -> navigator.showDetail(item));
    }

    private static ScrollPane page(Region content) {
        ScrollPane page = new ScrollPane(content);
        page.getStyleClass().add("library-page");
        page.setFitToWidth(true);
        page.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return page;
    }

    private Node emptyState() {
        Label title = new Label("Your library is empty");
        title.getStyleClass().add("empty-title");
        Label explanation = new Label("Open a server, go to the folder with your movies, shows or anime, and choose "
                + "“Add this folder to Library” from the ⋮ menu. StreamDav sorts what it finds into movies, shows "
                + "and anime, and finds posters and details for them.");
        explanation.setWrapText(true);
        explanation.getStyleClass().add("hint");
        Button browse = new Button("Open a server");
        browse.getStyleClass().add("primary");
        browse.setOnAction(event -> showServers());
        VBox box = new VBox(14, title, explanation, browse);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(460);
        StackPane centered = new StackPane(box);
        centered.getStyleClass().add("empty-state");
        return centered;
    }

    private static Node message(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hint");
        StackPane centered = new StackPane(label);
        centered.getStyleClass().add("empty-state");
        return centered;
    }

    /** Items whose title, or any title the provider knows them by, contains the query. */
    static List<Item> search(LibraryIndex index, String query) {
        String needle = MetadataMatcher.normalize(query);
        return index.items().stream()
                .filter(item -> MetadataMatcher.normalize(item.title()).contains(needle)
                        || item.metadata().map(Metadata::altTitles).orElse(List.of()).stream()
                        .anyMatch(title -> MetadataMatcher.normalize(title).contains(needle)))
                .sorted(LibraryIndex.BY_TITLE)
                .toList();
    }

    @FXML
    private void refresh() {
        model.refresh();
    }

    @FXML
    private void showServers() {
        navigator.showConnect();
    }

    @FXML
    private void configureExternalPlayer() {
        navigator.configureExternalPlayer();
    }

    @FXML
    private void configureTmdb() {
        TextInputDialog dialog = new TextInputDialog(navigator.settings().tmdbApiKey().orElse(""));
        dialog.setTitle("TMDB API key");
        dialog.setHeaderText(ApiKeys.hasBuiltInTmdbKey()
                ? "This build includes a TMDB key. Enter your own to use it instead, or leave this empty."
                : "Movies and TV shows get their posters and details from TMDB, which needs a free API key: create an "
                + "account at themoviedb.org, then copy the key (or the read access token) from Settings → API.\n\n"
                + "Anime is matched through AniList and needs no key.");
        dialog.setContentText("API key:");
        dialog.getDialogPane().setPrefWidth(560);
        navigator.showDialog(dialog).ifPresent(model::setTmdbKey);
    }

    @FXML
    private void manageFolders() {
        List<Source> sources;
        try {
            sources = model.sources();
        } catch (IOException e) {
            navigator.showError("Couldn't read the library folders", e.getMessage());
            return;
        }
        ListView<Source> list = new ListView<>();
        list.getItems().setAll(sources);
        list.setPrefSize(520, 260);
        list.setPlaceholder(new Label("No folders yet."));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Source source, boolean empty) {
                super.updateItem(source, empty);
                if (empty || source == null) {
                    setText(null);
                    return;
                }
                String server = navigator.settings().servers().stream()
                        .filter(saved -> saved.id().equals(source.serverId())).map(saved -> saved.name())
                        .findFirst().orElse("Removed server");
                setText(source.name() + "  —  " + server + "\n"
                        + URLDecoder.decode(source.uri().getRawPath(), StandardCharsets.UTF_8));
            }
        });
        Button remove = new Button("Remove from Library");
        remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(event -> {
            Source selected = list.getSelectionModel().getSelectedItem();
            model.removeSource(selected);
            list.getItems().remove(selected);
        });
        Label hint = new Label("To add a folder, open its server and choose “Add this folder to Library”. "
                + "Removing a folder only removes it from the library, not from the server.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint");
        VBox body = new VBox(12, list, new HBox(remove), hint);
        body.setPrefWidth(540);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Library folders");
        dialog.setHeaderText("Folders in your library");
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        navigator.showDialog(dialog);
    }

    private void onKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.F5 || (event.isShortcutDown() && event.getCode() == KeyCode.R)) {
            if (!model.busyProperty().get()) {
                refresh();
            }
        } else if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
            searchField.requestFocus();
        } else {
            return;
        }
        event.consume();
    }

}
