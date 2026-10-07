package com.example.streamdav.ui;

import com.example.streamdav.catalog.LibraryIndex.Item;
import com.example.streamdav.metadata.Metadata.Kind;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.List;

/** A library item as a poster with its title underneath; titles without artwork get a coloured placeholder. */
final class PosterCard {
    static final double WIDTH = 150;
    static final double HEIGHT = 225;
    /** Placeholder colours, picked by title so an item keeps its colour. */
    private static final String[] PLACEHOLDER_COLOURS = {"#3b4a6b", "#5a3d5c", "#2f5d5a", "#6b4a32", "#4a5a2f", "#5c3a3a"};

    private PosterCard() {
    }

    static Node create(Item item, ImageCache images, Runnable open) {
        StackPane poster = poster(item.title(), item.metadata().map(metadata -> metadata.posterUrl()).orElse(null),
                WIDTH, HEIGHT, images);

        Label title = new Label(item.title());
        title.getStyleClass().add("card-title-text");
        title.setWrapText(true);
        title.setMaxWidth(WIDTH);
        // Two lines at most, so long titles wrap rather than cut off, and every card lines up.
        title.setMinHeight(36);
        title.setPrefHeight(36);
        title.setMaxHeight(36);
        title.setAlignment(Pos.TOP_LEFT);
        Label subtitle = new Label(subtitle(item));
        subtitle.getStyleClass().add("card-subtitle");

        VBox card = new VBox(6, poster, title, subtitle);
        card.getStyleClass().add("poster-card");
        card.setPrefWidth(WIDTH);
        card.setMaxWidth(WIDTH);
        card.setFocusTraversable(true);
        card.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                open.run();
            }
        });
        card.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                open.run();
                event.consume();
            }
        });
        return card;
    }

    /** Artwork with rounded corners over a placeholder that names the title until the image arrives. */
    static StackPane poster(String title, String url, double width, double height, ImageCache images) {
        Label initials = new Label(title);
        initials.getStyleClass().add("poster-placeholder-text");
        initials.setWrapText(true);
        initials.setMaxWidth(width - 20);
        StackPane frame = new StackPane(initials);
        frame.getStyleClass().add("poster");
        frame.setStyle("-fx-background-color: " + PLACEHOLDER_COLOURS[Math.floorMod(title.hashCode(), PLACEHOLDER_COLOURS.length)] + ";");
        frame.setMinSize(width, height);
        frame.setPrefSize(width, height);
        frame.setMaxSize(width, height);
        frame.setAlignment(Pos.CENTER);
        Rectangle clip = new Rectangle(width, height);
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        frame.setClip(clip);
        images.load(url, width * 2, height * 2, image -> {
            ImageView view = new ImageView(image);
            view.setFitWidth(width);
            view.setFitHeight(height);
            view.setPreserveRatio(false);
            view.setSmooth(true);
            frame.getChildren().setAll(view);
        });
        return frame;
    }

    static String subtitle(Item item) {
        List<String> parts = new ArrayList<>();
        if (item.year() != null) {
            parts.add(String.valueOf(item.year()));
        }
        if (item.kind() == Kind.SHOW) {
            int count = item.episodes().size();
            parts.add(count + (count == 1 ? " episode" : " episodes"));
        } else if (item.versions().size() > 1) {
            parts.add(item.versions().size() + " versions");
        }
        return String.join(" · ", parts);
    }
}
