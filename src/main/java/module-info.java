module trietdao.ftp_client {
    requires javafx.controls;
    requires transitive javafx.graphics;
    requires javafx.fxml;


    opens trietdao.ftp_client to javafx.fxml;
    exports trietdao.ftp_client;
}