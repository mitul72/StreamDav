package com.example.streamdav.ui;

import com.example.streamdav.catalog.LibraryIndex.Category;
import com.example.streamdav.catalog.LibraryIndex.EpisodeEntry;
import com.example.streamdav.catalog.LibraryIndex.FileRef;
import com.example.streamdav.catalog.LibraryIndex.Item;
import com.example.streamdav.metadata.EpisodeInfo;
import com.example.streamdav.metadata.Metadata;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** A movie or show: its artwork and details, and its versions or episodes to play. */
final class DetailView {
    private static final double HERO_HEIGHT = 420;
    /** Absolutely numbered runs longer than this ("One Piece - 1071") are split into pages. */
    private static final int EPISODE_PAGE = 100;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);

    private final Navigator navigator;
    private final ImageCache images;
    private final Item item;
    private final BorderPane root = new BorderPane();
    private final VBox episodeList = new VBox();

    DetailView(Navigator navigator, ImageCache images, Item item) {
        this.navigator = navigator;
        this.images = images;
        this.item = item;
        root.getStyleClass().add("detail-view");

        VBox page = new VBox(hero());
        page.getStyleClass().add("detail-page");
        if (!item.episodes().isEmpty()) {
            page.getChildren().add(episodes());
        }
        if (!item.versions().isEmpty()) {
            page.getChildren().add(fileList(item.versions().size() == 1 ? "File" : "Files", item.versions(),
                    version -> version.file().name()));
        }
        if (!item.extras().isEmpty()) {
            // Openings, endings and the like, packed with the show: by name, in the order they're numbered.
            List<FileRef> extras = item.extras().stream()
                    .sorted(Comparator.comparing(extra -> extra.file().name(), NaturalOrder.INSTANCE)).toList();
            page.getChildren().add(fileList("Extras", extras, extra -> withoutExtension(extra.file().name())));
        }
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("library-page");

        Button back = new Button("Library", Icon.BACK.create(18));
        back.getStyleClass().add("back-button");
        back.setOnAction(event -> navigator.showLibrary());
        StackPane stack = new StackPane(scroll, back);
        StackPane.setAlignment(back, Pos.TOP_LEFT);
        root.setCenter(stack);
        root.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE || event.getCode() == KeyCode.BACK_SPACE
                    || (event.isAltDown() && event.getCode() == KeyCode.LEFT)) {
                navigator.showLibrary();
                event.consume();
            }
        });
    }

    Parent root() {
        return root;
    }

    void focus() {
        root.requestFocus();
    }

    private Node hero() {
        Region backdrop = new Region();
        backdrop.getStyleClass().add("backdrop-fallback");
        StackPane hero = new StackPane(backdrop);
        hero.getStyleClass().add("hero");
        hero.setMinHeight(HERO_HEIGHT);
        hero.setPrefHeight(HERO_HEIGHT);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        hero.setClip(clip);

        String backdropUrl = item.metadata().map(Metadata::backdropUrl).orElse(null);
        images.load(backdropUrl, 1600, 900, image -> {
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            // Cover the hero: fill its width, and its height when the window is wide and short.
            view.fitWidthProperty().bind(Bindings.createDoubleBinding(
                    () -> Math.max(hero.getWidth(), HERO_HEIGHT * image.getWidth() / image.getHeight()), hero.widthProperty()));
            StackPane.setAlignment(view, Pos.TOP_CENTER);
            hero.getChildren().add(1, view);
        });
        Region shade = new Region();
        shade.getStyleClass().add("hero-shade");
        hero.getChildren().add(shade);

        StackPane poster = PosterCard.poster(item.title(), item.metadata().map(Metadata::posterUrl).orElse(null),
                180, 270, images);
        poster.getStyleClass().add("detail-poster");

        Label title = new Label(item.title());
        title.getStyleClass().add("detail-title");
        title.setWrapText(true);
        Label facts = new Label(facts());
        facts.getStyleClass().add("detail-facts");
        VBox info = new VBox(10, title, facts);
        item.metadata().map(Metadata::overview).filter(text -> !text.isBlank()).ifPresent(overview -> {
            Label text = new Label(overview);
            text.getStyleClass().add("detail-overview");
            text.setWrapText(true);
            text.setMaxWidth(780);
            info.getChildren().add(text);
        });
        if (item.metadata().isEmpty()) {
            Label unmatched = new Label("No details found for this title yet.");
            unmatched.getStyleClass().add("hint");
            info.getChildren().add(unmatched);
        }
        info.getChildren().add(actions());
        info.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox overlay = new HBox(28, poster, info);
        overlay.getStyleClass().add("hero-content");
        overlay.setAlignment(Pos.BOTTOM_LEFT);
        hero.getChildren().add(overlay);
        return hero;
    }

    private String facts() {
        List<String> parts = new ArrayList<>();
        if (item.year() != null) {
            parts.add(String.valueOf(item.year()));
        }
        item.metadata().map(Metadata::genres).filter(genres -> !genres.isEmpty())
                .ifPresent(genres -> parts.add(String.join(", ", genres.subList(0, Math.min(3, genres.size())))));
        item.metadata().map(Metadata::rating).ifPresent(rating -> parts.add(String.format(Locale.ROOT, "★ %.1f", rating)));
        if (item.category() == Category.ANIME) {
            parts.add("Anime");
        }
        if (!item.episodes().isEmpty()) {
            parts.add(item.episodes().size() + (item.episodes().size() == 1 ? " episode" : " episodes"));
        }
        return String.join("  ·  ", parts);
    }

    private Node actions() {
        HBox actions = new HBox(10);
        actions.getStyleClass().add("detail-actions");
        if (!item.versions().isEmpty()) {
            Button play = new Button("Play", Icon.PLAY.create(18));
            play.getStyleClass().add("primary");
            play.setOnAction(event -> navigator.play(item.versions().getFirst()));
            actions.getChildren().add(play);
            if (item.versions().size() > 1) {
                MenuButton versions = new MenuButton("Versions");
                item.versions().forEach(version -> versions.getItems().add(versionItem(version)));
                actions.getChildren().add(versions);
            }
            Button external = new Button("External player", Icon.EXTERNAL.create(16));
            external.setOnAction(event -> navigator.openExternally(item.versions().getFirst()));
            actions.getChildren().add(external);
        } else if (!item.episodes().isEmpty()) {
            EpisodeEntry first = resumable().orElse(item.episodes().stream()
                    .filter(episode -> episode.season() == null || episode.season() != 0).findFirst()
                    .orElse(item.episodes().getFirst()));
            Button play = new Button("Play " + first.label(), Icon.PLAY.create(18));
            play.getStyleClass().add("primary");
            play.setOnAction(event -> navigator.play(first.versions().getFirst()));
            actions.getChildren().add(play);
        }
        return actions;
    }

    /** The episode the user was last part-way through, if any. */
    private Optional<EpisodeEntry> resumable() {
        return item.episodes().stream()
                .filter(episode -> episode.versions().stream()
                        .anyMatch(version -> navigator.settings().resumeMillis(version.file().uri()) > 0))
                .reduce((first, second) -> second);
    }

    private MenuItem versionItem(FileRef version) {
        MenuItem entry = new MenuItem(version.file().name() + "  (" + Format.size(version.file().size()) + ")");
        entry.setOnAction(event -> navigator.play(version));
        return entry;
    }

    private Node episodes() {
        HBox seasons = new HBox(6);
        seasons.getStyleClass().add("season-tabs");
        ToggleGroup group = new ToggleGroup();
        for (Integer season : item.seasons()) {
            List<EpisodeEntry> episodes = item.episodes(season);
            if (season == null && episodes.size() > EPISODE_PAGE) {
                for (int start = 0; start < episodes.size(); start += EPISODE_PAGE) {
                    List<EpisodeEntry> slice = episodes.subList(start, Math.min(episodes.size(), start + EPISODE_PAGE));
                    String label = slice.getFirst().numbers().getFirst() + "–" + slice.getLast().numbers().getLast();
                    seasons.getChildren().add(seasonTab(label, slice, group));
                }
            } else {
                seasons.getChildren().add(seasonTab(seasonName(season), episodes, group));
            }
        }
        // Start on the season with the episode being watched, else the first regular one.
        EpisodeEntry start = resumable().orElse(null);
        ToggleButton selected = seasons.getChildren().stream().map(ToggleButton.class::cast)
                .filter(tab -> start != null && ((List<?>) tab.getUserData()).contains(start)).findFirst()
                .orElse((ToggleButton) seasons.getChildren().getFirst());
        selected.setSelected(true);
        showEpisodes(castEpisodes(selected.getUserData()));
        group.selectedToggleProperty().addListener((observable, was, now) -> {
            if (now == null) {
                was.setSelected(true);
            } else {
                showEpisodes(castEpisodes(now.getUserData()));
            }
        });

        Node tabs = seasons;
        if (seasons.getChildren().size() == 1) {
            Label heading = new Label(seasonName(item.seasons().getFirst()));
            heading.getStyleClass().add("shelf-title");
            tabs = heading;
        } else {
            FlowPane wrapped = new FlowPane(6, 6);
            wrapped.getStyleClass().add("season-tabs");
            wrapped.getChildren().setAll(seasons.getChildren());
            tabs = wrapped;
        }
        VBox section = new VBox(14, tabs, episodeList);
        section.getStyleClass().add("episodes-section");
        return section;
    }

    @SuppressWarnings("unchecked")
    private static List<EpisodeEntry> castEpisodes(Object data) {
        return (List<EpisodeEntry>) data;
    }

    private static ToggleButton seasonTab(String label, List<EpisodeEntry> episodes, ToggleGroup group) {
        ToggleButton tab = new ToggleButton(label);
        tab.setToggleGroup(group);
        tab.setUserData(episodes);
        return tab;
    }

    private static String seasonName(Integer season) {
        if (season == null) {
            return "Episodes";
        }
        return season == 0 ? "Specials" : "Season " + season;
    }

    private void showEpisodes(List<EpisodeEntry> episodes) {
        episodeList.getChildren().clear();
        // Without episode details (anime matched on AniList, unmatched shows) a picture per row only repeats the
        // number, so those lists are compact.
        boolean detailed = episodes.stream().anyMatch(episode -> episode.info().isPresent());
        for (EpisodeEntry episode : episodes) {
            episodeList.getChildren().add(episodeRow(episode, detailed));
        }
    }

    private Node fileList(String title, List<FileRef> files, Function<FileRef, String> label) {
        Label heading = new Label(title);
        heading.getStyleClass().add("shelf-title");
        VBox rows = new VBox(2);
        for (FileRef version : files) {
            Label name = new Label(label.apply(version));
            name.getStyleClass().add("episode-title");
            Label meta = new Label(Format.size(version.file().size()));
            meta.getStyleClass().add("episode-meta");
            VBox text = new VBox(4, name, meta);
            HBox row = new HBox(text);
            row.getStyleClass().add("episode-row");
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    navigator.play(version);
                }
            });
            ContextMenu menu = new ContextMenu();
            MenuItem external = new MenuItem("Play in external player");
            external.setOnAction(event -> navigator.openExternally(version));
            menu.getItems().add(external);
            row.setOnContextMenuRequested(event -> menu.show(row, event.getScreenX(), event.getScreenY()));
            rows.getChildren().add(row);
        }
        VBox section = new VBox(10, heading, rows);
        section.getStyleClass().add("episodes-section");
        return section;
    }

    private static String withoutExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && name.length() - dot <= 5 ? name.substring(0, dot) : name;
    }

    private Node episodeRow(EpisodeEntry episode, boolean detailed) {
        EpisodeInfo info = episode.info().orElse(null);
        Node still;
        if (detailed) {
            StackPane picture = PosterCard.poster(episode.label(), info == null ? null : info.stillUrl(), 160, 90, images);
            picture.getStyleClass().add("episode-still");
            still = picture;
        } else {
            Label number = new Label(String.valueOf(episode.numbers().getFirst()));
            number.getStyleClass().add("episode-number");
            number.setMinWidth(Region.USE_PREF_SIZE);
            still = number;
        }

        String name = info != null && info.title() != null ? info.title() : episode.label();
        Label title = new Label(name);
        title.getStyleClass().add("episode-title");
        List<String> facts = new ArrayList<>();
        if (info != null && info.title() != null) {
            facts.add(episode.label());
        }
        if (info != null && info.airDate() != null) {
            facts.add(DATE.format(info.airDate()));
        }
        if (episode.versions().size() > 1) {
            facts.add(episode.versions().size() + " versions");
        }
        long resume = episode.versions().stream().mapToLong(version -> navigator.settings().resumeMillis(version.file().uri()))
                .max().orElse(0);
        if (resume > 0) {
            facts.add("Resume from " + Format.duration(javafx.util.Duration.millis(resume)));
        }
        Label meta = new Label(String.join("  ·  ", facts));
        meta.getStyleClass().add("episode-meta");
        VBox text = new VBox(4, title, meta);
        if (info != null && info.overview() != null) {
            Label overview = new Label(info.overview());
            overview.getStyleClass().add("episode-overview");
            overview.setWrapText(true);
            overview.setMaxHeight(40);
            text.getChildren().add(overview);
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(16, still, text);
        row.getStyleClass().add(detailed ? "episode-row" : "episode-row-compact");
        row.getStyleClass().add("episode-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setFocusTraversable(true);
        FileRef first = episode.versions().getFirst();
        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                navigator.play(first);
            }
        });
        row.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                navigator.play(first);
                event.consume();
            }
        });
        ContextMenu menu = new ContextMenu();
        if (episode.versions().size() > 1) {
            episode.versions().forEach(version -> menu.getItems().add(versionItem(version)));
            menu.getItems().add(new SeparatorMenuItem());
        }
        MenuItem external = new MenuItem("Play in external player");
        external.setOnAction(event -> navigator.openExternally(first));
        menu.getItems().add(external);
        row.setOnContextMenuRequested(event -> menu.show(row, event.getScreenX(), event.getScreenY()));
        return row;
    }

}
