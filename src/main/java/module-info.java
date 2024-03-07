module com.example.streamdav {
    requires javafx.controls;
    requires javafx.fxml;
    requires org.apache.logging.log4j;


    opens com.example.streamdav to javafx.fxml;
    exports com.example.streamdav;
}