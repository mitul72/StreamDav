package com.example.streamdav.ui;

import com.example.streamdav.library.MediaKind;
import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class BrowserController {
    /** Folders first, then names in natural order. */
    private static final Comparator<RemoteFile> DEFAULT_ORDER = Comparator.comparing(RemoteFile::directory).reversed()
            .thenComparing(RemoteFile::name, NaturalOrder.INSTANCE);

    @FXML private BorderPane root;
    @FXML private Button libraryButton;
    @FXML private Button backButton;
    @FXML private Button upButton;
    @FXML private HBox breadcrumbs;
    @FXML private TextField filterField;
    @FXML private CheckBox showAllCheck;
    @FXML private Button refreshButton;
    @FXML private MenuButton menuButton;
    @FXML private TableView<RemoteFile> table;
    @FXML private TableColumn<RemoteFile, RemoteFile> nameColumn;
    @FXML private TableColumn<RemoteFile, RemoteFile> sizeColumn;
    @FXML private TableColumn<RemoteFile, RemoteFile> modifiedColumn;
    @FXML private ProgressIndicator loading;
    @FXML private Label statusLabel;

    private final ObservableList<RemoteFile> items = FXCollections.observableArrayList();
    private final FilteredList<RemoteFile> filtered = new FilteredList<>(items);
    private final Deque<URI> history = new ArrayDeque<>();
    private Navigator navigator;
    private MediaLibrary library;
    private String serverName;
    private URI current;
    private Task<?> pending;
    private long loadGeneration;
    private boolean loadFailed;

    void init(Navigator navigator, MediaLibrary library, String serverName, List<RemoteFile> rootListing) {
        this.navigator = navigator;
        this.library = library;
        this.serverName = serverName;
        libraryButton.setGraphic(Icon.LIBRARY.create(20));
        backButton.setGraphic(Icon.BACK.create(20));
        upButton.setGraphic(Icon.UP.create(20));
        refreshButton.setGraphic(Icon.REFRESH.create(20));
        menuButton.setGraphic(Icon.MORE.create(20));
        configureTable();

        filterField.textProperty().addListener(observable -> refilter());
        filterField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                filterField.clear();
                table.requestFocus();
            }
        });
        showAllCheck.selectedProperty().addListener(observable -> refilter());
        root.addEventHandler(KeyEvent.KEY_PRESSED, this::onKeyPressed);

        current = library.root();
        refilter();
        updateNavigation();
        showListing(rootListing, null);
    }

    void focus() {
        table.requestFocus();
    }

    private void configureTable() {
        SortedList<RemoteFile> sorted = new SortedList<>(filtered);
        // Clicking a column header sorts by it; otherwise fall back to folders-first natural order.
        sorted.comparatorProperty().bind(Bindings.createObjectBinding(
                () -> table.getComparator() != null ? table.getComparator() : DEFAULT_ORDER, table.comparatorProperty()));
        table.setItems(sorted);
        table.setSortPolicy(t -> true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setRowFactory(t -> createRow());
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                RemoteFile selected = table.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    open(selected);
                }
            } else if (event.getCode() == KeyCode.BACK_SPACE) {
                goUp();
            } else {
                return;
            }
            event.consume();
        });

        nameColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        nameColumn.setComparator(DEFAULT_ORDER);
        nameColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(RemoteFile file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.name());
                setGraphic(empty || file == null ? null : Icon.of(file).create(18));
            }
        });
        sizeColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        sizeColumn.setComparator(Comparator.comparingLong(RemoteFile::size));
        sizeColumn.setCellFactory(column -> textCell(file -> file.directory() ? "" : Format.size(file.size()), "size-cell"));
        modifiedColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        modifiedColumn.setComparator(Comparator.comparing(RemoteFile::lastModified, Comparator.nullsFirst(Comparator.naturalOrder())));
        modifiedColumn.setCellFactory(column -> textCell(file -> Format.timestamp(file.lastModified()), "date-cell"));
    }

    private static TableCell<RemoteFile, RemoteFile> textCell(Function<RemoteFile, String> text, String styleClass) {
        TableCell<RemoteFile, RemoteFile> cell = new TableCell<>() {
            @Override
            protected void updateItem(RemoteFile file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : text.apply(file));
            }
        };
        cell.getStyleClass().add(styleClass);
        return cell;
    }

    private TableRow<RemoteFile> createRow() {
        TableRow<RemoteFile> row = new TableRow<>();
        MenuItem open = new MenuItem();
        MenuItem external = new MenuItem("Play in external player");
        MenuItem addToLibrary = new MenuItem("Add to Library");
        MenuItem copyLink = new MenuItem("Copy link");
        ContextMenu menu = new ContextMenu(open, external, addToLibrary, new SeparatorMenuItem(), copyLink);
        menu.setOnShowing(event -> {
            RemoteFile file = row.getItem();
            open.setText(file.directory() ? "Open" : "Play");
            open.setDisable(!file.directory() && !MediaKind.of(file.name()).isMedia());
            external.setVisible(!file.directory());
            addToLibrary.setVisible(file.directory());
        });
        open.setOnAction(event -> open(row.getItem()));
        addToLibrary.setOnAction(event -> navigator.addToLibrary(row.getItem().uri(), row.getItem().name()));
        external.setOnAction(event -> navigator.openExternally(row.getItem()));
        copyLink.setOnAction(event -> copyLink(row.getItem()));
        row.contextMenuProperty().bind(Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(menu));
        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                open(row.getItem());
            }
        });
        return row;
    }

    private void open(RemoteFile file) {
        if (file.directory()) {
            navigate(file.uri(), null, true);
        } else if (MediaKind.of(file.name()).isMedia()) {
            navigator.play(file);
        } else {
            statusLabel.setText("“" + file.name() + "” isn't a media file.");
        }
    }

    private void navigate(URI folder, URI select, boolean remember) {
        if (remember) {
            history.push(current);
        }
        current = folder;
        filterField.clear();
        updateNavigation();
        load(folder, select);
    }

    @FXML
    private void goBack() {
        if (!history.isEmpty()) {
            navigate(history.pop(), current, false);
        }
    }

    @FXML
    private void goUp() {
        if (!atRoot()) {
            navigate(current.resolve(".."), current, true);
        }
    }

    @FXML
    private void refresh() {
        RemoteFile selected = table.getSelectionModel().getSelectedItem();
        load(current, selected == null ? null : selected.uri());
    }

    @FXML
    private void showLibrary() {
        navigator.showLibrary();
    }

    @FXML
    private void addCurrentFolderToLibrary() {
        navigator.addToLibrary(current, atRoot() ? serverName : folderName(current));
    }

    @FXML
    private void configureExternalPlayer() {
        navigator.configureExternalPlayer();
    }

    @FXML
    private void disconnect() {
        if (pending != null) {
            pending.cancel();
        }
        navigator.showConnect();
    }

    private void load(URI folder, URI select) {
        long generation = ++loadGeneration;
        if (pending != null) {
            pending.cancel();
        }
        loading.setVisible(true);
        Task<List<RemoteFile>> task = new Task<>() {
            @Override
            protected List<RemoteFile> call() throws Exception {
                return library.list(folder);
            }
        };
        // Ignore results from folders the user has already navigated away from.
        task.setOnSucceeded(event -> {
            if (generation == loadGeneration) {
                showListing(task.getValue(), select);
            }
        });
        task.setOnFailed(event -> {
            if (generation == loadGeneration) {
                showLoadError(task.getException());
            }
        });
        pending = task;
        navigator.background().execute(task);
    }

    private void showListing(List<RemoteFile> listing, URI select) {
        loadFailed = false;
        loading.setVisible(false);
        items.setAll(listing);
        updateStatus();
        List<RemoteFile> visible = table.getItems();
        RemoteFile toSelect = visible.stream().filter(file -> file.uri().equals(select)).findFirst()
                .orElse(visible.isEmpty() ? null : visible.getFirst());
        if (toSelect != null) {
            table.getSelectionModel().select(toSelect);
            table.scrollTo(toSelect);
        }
        table.requestFocus();
    }

    private void showLoadError(Throwable error) {
        loadFailed = true;
        loading.setVisible(false);
        items.clear();
        Label message = new Label(Errors.describe(error));
        message.setWrapText(true);
        Button retry = new Button("Try again");
        retry.setOnAction(event -> load(current, null));
        VBox placeholder = new VBox(12, message, retry);
        placeholder.setAlignment(Pos.CENTER);
        placeholder.setMaxWidth(440);
        table.setPlaceholder(placeholder);
        statusLabel.setText("Couldn't load this folder");
    }

    private void refilter() {
        String query = filterField.getText() == null ? "" : filterField.getText().strip().toLowerCase(Locale.ROOT);
        boolean showAll = showAllCheck.isSelected();
        filtered.setPredicate(file -> isVisible(file, query, showAll));
        updateStatus();
    }

    private static boolean isVisible(RemoteFile file, String query, boolean showAll) {
        if (!showAll && (file.name().startsWith(".") || !(file.directory() || MediaKind.of(file.name()).isMedia()))) {
            return false;
        }
        return query.isEmpty() || file.name().toLowerCase(Locale.ROOT).contains(query);
    }

    private void updateStatus() {
        if (loadFailed) {
            return;
        }
        long folders = filtered.stream().filter(RemoteFile::directory).count();
        long files = filtered.size() - folders;
        int hidden = items.size() - filtered.size();
        List<String> parts = new ArrayList<>();
        if (folders > 0) {
            parts.add(count(folders, "folder"));
        }
        if (files > 0) {
            parts.add(count(files, "file"));
        }
        if (parts.isEmpty()) {
            parts.add("No items");
        }
        if (hidden > 0) {
            parts.add(hidden + " hidden");
        }
        statusLabel.setText(String.join(" · ", parts));
        table.setPlaceholder(new Label(placeholderText()));
    }

    private String placeholderText() {
        if (items.isEmpty()) {
            return "This folder is empty.";
        }
        if (!filterField.getText().isBlank()) {
            return "Nothing matches “" + filterField.getText().strip() + "”.";
        }
        return "No media here. Turn on “All files” to see everything.";
    }

    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private void onKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.F5 || (event.isShortcutDown() && event.getCode() == KeyCode.R)) {
            refresh();
        } else if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
            filterField.requestFocus();
        } else if (event.isAltDown() && event.getCode() == KeyCode.LEFT) {
            goBack();
        } else if (event.isAltDown() && event.getCode() == KeyCode.UP) {
            goUp();
        } else {
            return;
        }
        event.consume();
    }

    private void copyLink(RemoteFile file) {
        ClipboardContent content = new ClipboardContent();
        content.putString(file.uri().toString());
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Copied link to “" + file.name() + "”");
    }

    private boolean atRoot() {
        String rootPath = library.root().getRawPath();
        return !current.getRawPath().startsWith(rootPath) || current.getRawPath().equals(rootPath);
    }

    private void updateNavigation() {
        backButton.setDisable(history.isEmpty());
        upButton.setDisable(atRoot());
        breadcrumbs.getChildren().clear();
        List<URI> trail = trail();
        for (int i = 0; i < trail.size(); i++) {
            URI folder = trail.get(i);
            String label = i == 0 ? serverName : folderName(folder);
            if (i > 0) {
                Label separator = new Label("›");
                separator.getStyleClass().add("crumb-separator");
                breadcrumbs.getChildren().add(separator);
            }
            Node crumb;
            if (i == trail.size() - 1) {
                Label currentCrumb = new Label(label);
                currentCrumb.getStyleClass().add("crumb-current");
                crumb = currentCrumb;
            } else {
                Hyperlink link = new Hyperlink(label);
                link.setOnAction(event -> navigate(folder, null, true));
                crumb = link;
            }
            breadcrumbs.getChildren().add(crumb);
        }
    }

    /** The root followed by each folder down to the current one. */
    private List<URI> trail() {
        List<URI> trail = new ArrayList<>();
        URI rootUri = library.root();
        trail.add(rootUri);
        if (atRoot()) {
            return trail;
        }
        StringBuilder path = new StringBuilder(rootUri.getRawPath());
        for (String segment : current.getRawPath().substring(path.length()).split("/")) {
            if (!segment.isEmpty()) {
                path.append(segment).append('/');
                trail.add(rootUri.resolve(path.toString()));
            }
        }
        return trail;
    }

    private static String folderName(URI folder) {
        String path = folder.getPath();
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }
}
