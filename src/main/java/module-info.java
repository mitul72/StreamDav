module com.example.streamdav {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.example.streamdav to javafx.fxml;
    exports com.example.streamdav;
}