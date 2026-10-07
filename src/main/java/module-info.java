module com.example.streamdav {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires java.net.http;
    requires java.prefs;
    requires java.xml;
    requires org.apache.logging.log4j;

    opens com.example.streamdav.ui to javafx.fxml;
    // JavaFX instantiates the Application subclass reflectively.
    exports com.example.streamdav to javafx.graphics;
}
