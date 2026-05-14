package trietdao.ftp_client;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.MouseButton;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;

public class Controller implements Initializable {
    @FXML
    private TextField hostField;

    @FXML
    private TextField portField;

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private Button connectButton;

    @FXML
    private Button createDirectoryButton;

    @FXML
    private Button deleteButton;

    @FXML
    private Button removeDirectoryButton;

    @FXML
    private Button uploadButton;

    @FXML
    private Button downloadButton;

    @FXML
    private Label remotePathLabel;

    @FXML
    private ListView<RemoteItem> remoteListView;

    @FXML
    private ScrollPane consoleScrollPane;

    @FXML
    private TextFlow consoleFlow;

    private final ObservableList<RemoteItem> remoteItems = FXCollections.observableArrayList();
    private FtpClient ftpClient;
    private boolean connected;
    private String remoteCurrentPath = "/";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        remoteListView.setItems(remoteItems);
        remoteListView.setCellFactory(listView -> new ListCell<RemoteItem>() {
            @Override
            protected void updateItem(RemoteItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText((item.directory ? "[DIR] " : "") + item.name);
                }
            }
        });

        remoteListView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                openSelectedRemoteItem();
            }
        });

        hostField.setText("ftp.dlptest.com");
        portField.setText("21");
        usernameField.setText("dlpuser");
        passwordField.setText("rNrKYTX9g7z3RgJRmxWuGHbeu");

        if (consoleFlow != null) {
            consoleFlow.getChildren().clear();
        }

        remotePathLabel.setText(remoteCurrentPath);
        setConnectedState(false);
        appendNotification("Ready");
    }

    @FXML
    public void onConnectClicked() {
        if (connected) {
            try {
                disconnect();
                appendNotification("Disconnected.");
            } catch (IOException ex) {
                appendNotification("Disconnect failed: " + ex.getMessage());
                disconnectSilently();
            }
            return;
        }

        try {
            ftpClient = new FtpClient();
            ftpClient.setLogListener(this::appendProtocolLog);
            ftpClient.connect(hostField.getText().trim(), Integer.parseInt(portField.getText().trim()));
            ftpClient.login(usernameField.getText().trim(), passwordField.getText());

            connected = true;
            remoteCurrentPath = ftpClient.printWorkingDirectory();
            refreshRemoteListing();
            setConnectedState(true);
            appendNotification("Connected to " + hostField.getText().trim() + ":" + portField.getText().trim());
        } catch (IOException | NumberFormatException ex) {
            appendNotification("Connect failed: " + ex.getMessage());
            disconnectSilently();
        }
    }

    @FXML
    public void onCreateDirectoryClicked() {
        if (!ensureConnected()) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create New Directory");
        dialog.setHeaderText("Create a folder in the current remote path");
        dialog.setContentText("Folder name:");

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }

        try {
            ftpClient.createDirectory(result.get().trim());
            refreshRemoteListing();
            appendNotification("Created directory: " + result.get().trim());
        } catch (IOException ex) {
            appendNotification("Create directory failed: " + ex.getMessage());
        }
    }

    @FXML
    public void onDeleteClicked() {
        if (!ensureConnected()) {
            return;
        }

        RemoteItem selected = remoteListView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.directory || selected.parent) {
            appendNotification("Select a remote file to delete.");
            return;
        }

        try {
            ftpClient.deleteFile(selected.path);
            refreshRemoteListing();
            appendNotification("Deleted file: " + selected.name);
        } catch (IOException ex) {
            appendNotification("Delete failed: " + ex.getMessage());
        }
    }

    @FXML
    public void onRemoveDirectoryClicked() {
        if (!ensureConnected()) {
            return;
        }

        RemoteItem selected = remoteListView.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.directory || selected.parent) {
            appendNotification("Select a remote directory to remove.");
            return;
        }

        try {
            ftpClient.removeDirectory(selected.path);
            refreshRemoteListing();
            appendNotification("Removed directory: " + selected.name);
        } catch (IOException ex) {
            appendNotification("Remove directory failed: " + ex.getMessage());
        }
    }

    @FXML
    public void onUploadClicked() {
        if (!ensureConnected()) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select file to upload");
        File localFile = chooser.showOpenDialog(uploadButton.getScene().getWindow());
        if (localFile == null) {
            return;
        }

        try {
            ftpClient.uploadFile(localFile.getAbsolutePath(), localFile.getName());
            refreshRemoteListing();
            appendNotification("Uploaded: " + localFile.getName());
        } catch (IOException ex) {
            appendNotification("Upload failed: " + ex.getMessage());
        }
    }

    @FXML
    public void onDownloadClicked() {
        if (!ensureConnected()) {
            return;
        }

        RemoteItem selected = remoteListView.getSelectionModel().getSelectedItem();
        if (selected == null || selected.directory || selected.parent) {
            appendNotification("Select a remote file to download.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save downloaded file");
        chooser.setInitialFileName(selected.name);
        File targetFile = chooser.showSaveDialog(downloadButton.getScene().getWindow());
        if (targetFile == null) {
            return;
        }

        try {
            ftpClient.downloadFile(selected.name, targetFile.getAbsolutePath());
            appendNotification("Downloaded: " + selected.name);
        } catch (IOException ex) {
            appendNotification("Download failed: " + ex.getMessage());
        }
    }

    private void openSelectedRemoteItem() {
        if (!connected) {
            return;
        }

        RemoteItem selected = remoteListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }

        if (selected.parent) {
            navigateToParent();
            return;
        }

        if (!selected.directory) {
            return;
        }

        try {
            ftpClient.changeDirectory(selected.path);
            refreshRemoteListing();
        } catch (IOException ex) {
            appendNotification("Open folder failed: " + ex.getMessage());
        }
    }

    private void navigateToParent() {
        try {
            RemoteItem parentItem = remoteItems.isEmpty() ? null : remoteItems.get(0);
            if (parentItem == null || !parentItem.parent) {
                return;
            }

            ftpClient.changeDirectory(parentItem.path);
            refreshRemoteListing();
        } catch (IOException ex) {
            appendNotification("Navigate up failed: " + ex.getMessage());
        }
    }

    private void refreshRemoteListing() throws IOException {
        remoteCurrentPath = ftpClient.printWorkingDirectory();
        remotePathLabel.setText(remoteCurrentPath);

        remoteItems.setAll(parseListing(ftpClient.list(null), remoteCurrentPath));
        if (!"/".equals(remoteCurrentPath)) {
            remoteItems.add(0, RemoteItem.parent(parentRemotePath(remoteCurrentPath)));
        }
    }

    private List<RemoteItem> parseListing(List<String> lines, String basePath) {
        List<RemoteItem> items = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }

            String[] parts = line.trim().split("\\s+");
            if (parts.length == 0) {
                continue;
            }

            boolean directory = parts[0].startsWith("d");
            String name = line.trim();
            if (parts.length >= 9) {
                StringBuilder builder = new StringBuilder();
                for (int i = 8; i < parts.length; i++) {
                    if (i > 8) {
                        builder.append(' ');
                    }
                    builder.append(parts[i]);
                }
                name = builder.toString();
            }

            items.add(new RemoteItem(name, directory, false, combineRemotePath(basePath, name)));
        }
        return items;
    }

    private String combineRemotePath(String basePath, String childName) {
        if (basePath == null || basePath.isBlank() || "/".equals(basePath)) {
            return "/" + childName;
        }

        if (basePath.endsWith("/")) {
            return basePath + childName;
        }

        return basePath + "/" + childName;
    }

    private String parentRemotePath(String currentPath) {
        if (currentPath == null || currentPath.isBlank() || "/".equals(currentPath)) {
            return "/";
        }

        int lastSlash = currentPath.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }

        return currentPath.substring(0, lastSlash);
    }

    private boolean ensureConnected() {
        if (connected && ftpClient != null) {
            return true;
        }

        appendNotification("Connect first.");
        return false;
    }

    private void disconnect() throws IOException {
        if (ftpClient != null) {
            ftpClient.quit();
        }
        disconnectSilently();
    }

    private void disconnectSilently() {
        connected = false;
        ftpClient = null;
        remoteCurrentPath = "/";
        remoteItems.clear();
        remotePathLabel.setText(remoteCurrentPath);
        setConnectedState(false);
    }

    private void setConnectedState(boolean state) {
        connectButton.setDisable(false);
        connectButton.setText(state ? "Disconnect" : "Connect");
        createDirectoryButton.setDisable(!state);
        deleteButton.setDisable(!state);
        removeDirectoryButton.setDisable(!state);
        uploadButton.setDisable(!state);
        downloadButton.setDisable(!state);
    }

    private void appendProtocolLog(String message) {
        if (Platform.isFxApplicationThread()) {
            appendConsoleText(message, false);
            return;
        }

        Platform.runLater(() -> appendConsoleText(message, false));
    }

    private void appendNotification(String message) {
        appendConsoleText(message, true);
    }

    private void appendConsoleText(String message, boolean bold) {
        if (consoleFlow == null || message == null || message.isBlank()) {
            return;
        }

        String normalized = message.endsWith(System.lineSeparator())
                ? message
                : message + System.lineSeparator();

        Text text = new Text(normalized);
        if (bold) {
            text.setStyle("-fx-font-weight: bold;");
        }

        consoleFlow.getChildren().add(text);

        if (consoleScrollPane != null) {
            consoleScrollPane.layout();
            consoleScrollPane.setVvalue(1.0);
        }
    }

    private static final class RemoteItem {
        private final String name;
        private final boolean directory;
        private final boolean parent;
        private final String path;

        private RemoteItem(String name, boolean directory, boolean parent, String path) {
            this.name = name;
            this.directory = directory;
            this.parent = parent;
            this.path = path;
        }

        private static RemoteItem parent(String path) {
            return new RemoteItem("..", true, true, path);
        }
    }
}
