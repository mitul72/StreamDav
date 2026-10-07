package com.example.streamdav;

import com.example.streamdav.dav.DavClient;
import com.example.streamdav.dav.WebDavLibrary;
import com.example.streamdav.settings.Settings;
import com.example.streamdav.stream.StreamProxy;
import com.example.streamdav.ui.Navigator;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.io.IOException;

public class StreamDavApplication extends Application {
    private StreamProxy proxy;
    private Navigator navigator;

    @Override
    public void start(Stage stage) throws IOException {
        proxy = new StreamProxy();
        navigator = new Navigator(stage, new Settings(),
                (root, username, password) -> new WebDavLibrary(new DavClient(root, username, password), proxy));

        Scene scene = new Scene(new Region(), 1100, 720);
        scene.getStylesheets().add(Navigator.stylesheet());
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(500);
        // The player handles Esc itself so it can leave full screen or close, depending on state.
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("Press Esc to exit full screen");

        navigator.showConnect();
        stage.show();
    }

    @Override
    public void stop() {
        if (navigator != null) {
            navigator.shutdown();
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
