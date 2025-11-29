package org.example.p2psharefile.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.example.p2psharefile.model.*;
import org.example.p2psharefile.service.P2PService;
import org.example.p2psharefile.service.PINCodeService;

import java.io.File;
import java.util.Optional;

/**
 * Module 8: MainController - Controller cho giao diện chính
 * 
 * Quản lý UI và tương tác với P2PService
 */
public class MainController implements P2PService.P2PServiceListener {
    
    // ========== FXML Components ==========
    
    // Header
    @FXML private Label peerNameLabel;
    @FXML private Label statusLabel;
    @FXML private Label statusDot;
    @FXML private Label peerCountLabel;
    
    // Tab 1: Kết nối
    @FXML private TextField displayNameField;
    @FXML private TextField portField;
    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private ListView<PeerInfo> peerListView;
    
    // Tab 2: Chia sẻ file
    @FXML private Button addFileButton;
    @FXML private Button addDirectoryButton;
    @FXML private ListView<String> sharedFilesListView;
    @FXML private Label sharedFileCountLabel;
    
    // Tab 3: Tìm kiếm & Download
    @FXML private TextField searchField;
    @FXML private Button searchButton;
    @FXML private ListView<SearchResultItem> searchResultsListView;
    @FXML private Button downloadButton;
    @FXML private TextArea logTextArea;
    
    // Tab 4: Share Code (PIN)
    @FXML private ListView<FileInfo> pinShareFileListView;
    @FXML private VBox pinDisplayPanel;
    @FXML private Label pinLabel;
    @FXML private Label pinFileNameLabel;
    @FXML private Label pinExpiryLabel;
    @FXML private TextField pinInputField;
    @FXML private Button receiveButton;
    
    // ========== Data ==========
    
    private P2PService p2pService;
    private ObservableList<PeerInfo> peerList;
    private ObservableList<FileInfo> sharedFilesList;
    private ObservableList<String> sharedFilesDisplay;
    private ObservableList<SearchResultItem> searchResults;
    
    private String downloadDirectory = System.getProperty("user.home") + "/Downloads/";
    
    // PIN-related
    private ShareSession currentPINSession = null;
    private Timeline pinExpiryTimeline = null;
    
    /**
     * Class để hiển thị kết quả tìm kiếm
     */
    public static class SearchResultItem {
        private final FileInfo fileInfo;
        private final PeerInfo peerInfo;
        
        public SearchResultItem(FileInfo fileInfo, PeerInfo peerInfo) {
            this.fileInfo = fileInfo;
            this.peerInfo = peerInfo;
        }
        
        public FileInfo getFileInfo() { return fileInfo; }
        public PeerInfo getPeerInfo() { return peerInfo; }
        
        @Override
        public String toString() {
            return fileInfo.getFileName() + " (" + fileInfo.getFormattedSize() + 
                   ") - từ " + peerInfo.getDisplayName();
        }
    }
    
    /**
     * Initialize - được gọi tự động sau khi FXML load
     */
    @FXML
    public void initialize() {
        // Khởi tạo observable lists
        peerList = FXCollections.observableArrayList();
        sharedFilesList = FXCollections.observableArrayList();
        sharedFilesDisplay = FXCollections.observableArrayList();
        searchResults = FXCollections.observableArrayList();
        
        // Bind data vào UI
        peerListView.setItems(peerList);
        sharedFilesListView.setItems(sharedFilesDisplay);
        searchResultsListView.setItems(searchResults);
        pinShareFileListView.setItems(sharedFilesList);
        
        // Setup custom cell factory cho sharedFilesListView với nút Hủy
        setupSharedFilesListView();
        
        // Set default values - Port tự động random, không cần nhập
        displayNameField.setText("Peer_" + System.getProperty("user.name"));
        // Port ngẫu nhiên: sẽ được tự động chọn khi Start
        portField.setText("AUTO");
        portField.setDisable(true); // Khóa vì port tự động
        portField.setStyle("-fx-background-color: #f0f0f0;");
        
        // Initial state
        stopButton.setDisable(true);
        searchButton.setDisable(true);
        downloadButton.setDisable(true);
        receiveButton.setDisable(true);
        
        // Set PIN input field max length to 6 digits
        pinInputField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*")) {
                pinInputField.setText(newValue.replaceAll("[^\\d]", ""));
            }
            if (newValue.length() > 6) {
                pinInputField.setText(newValue.substring(0, 6));
            }
        });
        searchButton.setDisable(true);
        downloadButton.setDisable(true);
        
        updateStatus("Offline", "#ff4757");
        
        log("📱 Ứng dụng P2P Share File đã sẵn sàng!");
        log("📁 Thư mục download mặc định: " + downloadDirectory);
        log("ℹ️  P2P thuần túy - Mỗi peer tự chọn port ngẫu nhiên");
        log("ℹ️  Có thể chạy nhiều client trên cùng máy để test");
    }
    
    /**
     * Xử lý khi nhấn nút Start
     */
    @FXML
    private void handleStart() {
        try {
            String displayName = displayNameField.getText().trim();
            if (displayName.isEmpty()) {
                showError("Vui lòng nhập tên hiển thị");
                return;
            }
            
            // Port = 0 nghĩa là hệ thống tự động chọn port trống
            int port = 0; // Auto-assign random available port
            
            // Tạo và khởi động P2P Service
            p2pService = new P2PService(displayName, port);
            p2pService.addListener(this);
            p2pService.start();
            
            // Lấy port thực tế được assign và hiển thị
            int actualPort = p2pService.getActualPort();
            portField.setText(String.valueOf(actualPort));
            
            // Hiển thị tên peer ở header
            peerNameLabel.setText(displayName);
            
            // Update UI
            startButton.setDisable(true);
            stopButton.setDisable(false);
            displayNameField.setDisable(true);
            portField.setDisable(true);
            searchButton.setDisable(false);
            receiveButton.setDisable(false);
            
            log("🚀 Đã khởi động P2P Service");
            log("📡 Port được chọn tự động: " + actualPort);
            
        } catch (Exception e) {
            showError("Lỗi khi khởi động: " + e.getMessage());
            log("❌ Lỗi: " + e.getMessage());
        }
    }
    
    /**
     * Xử lý khi nhấn nút Stop
     */
    @FXML
    private void handleStop() {
        if (p2pService != null) {
            p2pService.stop();
            p2pService = null;
        }
        
        // Xóa tên peer khỏi header
        peerNameLabel.setText("");
        
        // Reset UI
        startButton.setDisable(false);
        stopButton.setDisable(true);
        displayNameField.setDisable(false);
        portField.setDisable(true);
        portField.setText("AUTO"); // Reset về AUTO
        searchButton.setDisable(true);
        downloadButton.setDisable(true);
        receiveButton.setDisable(true);
        
        peerList.clear();
        searchResults.clear();
        
        // Stop PIN expiry timer if running
        if (pinExpiryTimeline != null) {
            pinExpiryTimeline.stop();
            pinExpiryTimeline = null;
        }
        currentPINSession = null;
        pinDisplayPanel.setVisible(false);
        
        updateStatus("Disconnected", "#95a5a6");
        peerCountLabel.setText("Peers: 0");
        
        log("🛑 Đã dừng P2P Service");
    }
    
    /**
     * Xử lý khi nhấn nút Add File
     */
    @FXML
    private void handleAddFile() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để chia sẻ");
        File file = fileChooser.showOpenDialog(addFileButton.getScene().getWindow());
        
        if (file != null) {
            p2pService.addSharedFile(file);
            refreshSharedFiles();
            log("✓ Đã thêm file: " + file.getName());
        }
    }
    
    /**
     * Xử lý khi nhấn nút Add Directory
     */
    @FXML
    private void handleAddDirectory() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục để chia sẻ");
        File directory = dirChooser.showDialog(addDirectoryButton.getScene().getWindow());
        
        if (directory != null) {
            p2pService.addSharedDirectory(directory);
            refreshSharedFiles();
            log("✓ Đã thêm thư mục: " + directory.getName());
        }
    }
    
    /**
     * Xử lý khi nhấn nút Search
     */
    @FXML
    private void handleSearch() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            showError("Vui lòng nhập từ khóa tìm kiếm");
            return;
        }
        
        searchResults.clear();
        searchButton.setDisable(true);
        log("🔍 Đang tìm kiếm: " + query);
        
        p2pService.searchFile(query);
    }
    
    /**
     * Xử lý khi nhấn nút Download
     */
    @FXML
    private void handleDownload() {
        SearchResultItem selected = searchResultsListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Vui lòng chọn file cần download");
            return;
        }
        
        // Cho phép chọn thư mục download
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục lưu file");
        dirChooser.setInitialDirectory(new File(downloadDirectory));
        File saveDir = dirChooser.showDialog(downloadButton.getScene().getWindow());
        
        if (saveDir != null) {
            downloadDirectory = saveDir.getAbsolutePath();
            p2pService.downloadFile(
                selected.getPeerInfo(),
                selected.getFileInfo(),
                downloadDirectory
            );
            log("📥 Đang download: " + selected.getFileInfo().getFileName());
        }
    }
    
    /**
     * Setup ListView cho shared files với nút Hủy
     */
    private void setupSharedFilesListView() {
        sharedFilesListView.setCellFactory(param -> new javafx.scene.control.ListCell<String>() {
            private final javafx.scene.control.Button removeBtn = new javafx.scene.control.Button("❌ Hủy");
            private final javafx.scene.layout.HBox hbox = new javafx.scene.layout.HBox(10);
            private final javafx.scene.control.Label label = new javafx.scene.control.Label();
            
            {
                removeBtn.setStyle("-fx-background-color: #ff4757; -fx-text-fill: white; " +
                                  "-fx-font-weight: bold; -fx-padding: 5 10; " +
                                  "-fx-background-radius: 5; -fx-cursor: hand;");
                removeBtn.setOnAction(event -> {
                    String item = getItem();
                    if (item != null && p2pService != null) {
                        // Tìm FileInfo tương ứng và xóa
                        for (FileInfo fileInfo : sharedFilesList) {
                            if (item.startsWith(fileInfo.getFileName())) {
                                p2pService.removeSharedFile(fileInfo);
                                refreshSharedFiles();
                                log("🗑️ Đã hủy chia sẻ: " + fileInfo.getFileName());
                                break;
                            }
                        }
                    }
                });
                
                javafx.scene.layout.Region region = new javafx.scene.layout.Region();
                javafx.scene.layout.HBox.setHgrow(region, javafx.scene.layout.Priority.ALWAYS);
                hbox.getChildren().addAll(label, region, removeBtn);
                hbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            }
            
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    label.setText(item);
                    setGraphic(hbox);
                }
            }
        });
    }
    
    /**
     * Refresh danh sách file chia sẻ
     */
    private void refreshSharedFiles() {
        if (p2pService != null) {
            sharedFilesList.clear();
            sharedFilesList.addAll(p2pService.getSharedFiles());
            
            // Cập nhật display list
            sharedFilesDisplay.clear();
            for (FileInfo fileInfo : sharedFilesList) {
                sharedFilesDisplay.add(fileInfo.getFileName() + " (" + fileInfo.getFormattedSize() + ")");
            }
            
            sharedFileCountLabel.setText("Files: " + p2pService.getSharedFileCount());
        }
    }
    
    // ========== PIN Code Handlers ==========
    
    /**
     * Xử lý khi chọn file để tạo mã PIN chia sẻ
     */
    @FXML
    private void handleSelectFileForPIN() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để chia sẻ bằng mã PIN");
        File file = fileChooser.showOpenDialog(pinShareFileListView.getScene().getWindow());
        
        if (file != null) {
            // Add to shared files first
            p2pService.addSharedFile(file);
            refreshSharedFiles();
            
            // Create PIN code for this file
            try {
                FileInfo fileInfo = new FileInfo(
                    file.getName(),
                    file.length(),
                    file.getAbsolutePath()
                );
                currentPINSession = p2pService.createSharePIN(fileInfo);
                
                if (currentPINSession != null) {
                    // Display PIN in UI
                    pinLabel.setText(currentPINSession.getPin());
                    pinFileNameLabel.setText(fileInfo.getFileName());
                    pinDisplayPanel.setVisible(true);
                    
                    // Start countdown timer
                    startPINExpiryTimer();
                    
                    log("🔑 Đã tạo mã PIN: " + currentPINSession.getPin() + " cho file: " + fileInfo.getFileName());
                    showInfo("Mã PIN đã được tạo!\n\n" +
                            "Mã: " + currentPINSession.getPin() + "\n" +
                            "File: " + fileInfo.getFileName() + "\n" +
                            "Hết hạn sau: 10 phút\n\n" +
                            "Mã này đã được broadcast tới tất cả peers.");
                } else {
                    showError("Không thể tạo mã PIN");
                }
            } catch (Exception e) {
                showError("Lỗi khi tạo mã PIN: " + e.getMessage());
                log("❌ Lỗi tạo PIN: " + e.getMessage());
            }
        }
    }
    
    /**
     * Xử lý khi nhấn nút hủy PIN
     */
    @FXML
    private void handleCancelPIN() {
        if (currentPINSession != null) {
            p2pService.cancelPIN(currentPINSession.getPin());
            currentPINSession = null;
            pinDisplayPanel.setVisible(false);
            
            if (pinExpiryTimeline != null) {
                pinExpiryTimeline.stop();
                pinExpiryTimeline = null;
            }
            
            log("❌ Đã hủy mã PIN");
        }
    }
    
    /**
     * Xử lý khi nhấn nút nhận file bằng mã PIN
     */
    @FXML
    private void handleReceiveByPIN() {
        if (p2pService == null) {
            showError("Vui lòng khởi động service trước");
            return;
        }
        
        String pin = pinInputField.getText().trim();
        
        if (pin.isEmpty()) {
            showError("Vui lòng nhập mã PIN");
            return;
        }
        
        if (pin.length() != 6) {
            showError("Mã PIN phải có 6 chữ số");
            return;
        }
        
        // Chọn thư mục lưu file
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Chọn thư mục lưu file");
        dirChooser.setInitialDirectory(new File(downloadDirectory));
        File saveDir = dirChooser.showDialog(pinInputField.getScene().getWindow());
        
        if (saveDir != null) {
            downloadDirectory = saveDir.getAbsolutePath();
            
            try {
                p2pService.receiveByPIN(pin, downloadDirectory);
                log("📥 Đang nhận file bằng mã PIN: " + pin);
                pinInputField.clear();
                showInfo("Đã bắt đầu nhận file!\nSẽ tự động download khi tìm thấy peer có mã này.");
            } catch (Exception e) {
                showError("Lỗi khi nhận file: " + e.getMessage());
                log("❌ Lỗi nhận file: " + e.getMessage());
            }
        }
    }
    
    /**
     * Bắt đầu đếm ngược thời gian hết hạn của PIN
     */
    private void startPINExpiryTimer() {
        if (pinExpiryTimeline != null) {
            pinExpiryTimeline.stop();
        }
        
        pinExpiryTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            if (currentPINSession != null) {
                if (currentPINSession.isExpired()) {
                    // PIN expired
                    pinExpiryLabel.setText("⏰ Đã hết hạn!");
                    pinExpiryLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    pinExpiryTimeline.stop();
                    
                    Platform.runLater(() -> {
                        showInfo("Mã PIN đã hết hạn");
                        pinDisplayPanel.setVisible(false);
                        currentPINSession = null;
                    });
                } else {
                    // Update remaining time
                    String timeLeft = currentPINSession.getRemainingTimeFormatted();
                    pinExpiryLabel.setText("⏱ Hết hạn sau: " + timeLeft);
                    pinExpiryLabel.setStyle("-fx-text-fill: #666;");
                }
            }
        }));
        
        pinExpiryTimeline.setCycleCount(Timeline.INDEFINITE);
        pinExpiryTimeline.play();
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Update status label with dot indicator
     */
    private void updateStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16;");
            
            // Update status dot color
            if (statusDot != null) {
                statusDot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 20;");
            }
        });
    }
    
    /**
     * Log message
     */
    private void log(String message) {
        Platform.runLater(() -> {
            String timestamp = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            );
            logTextArea.appendText("[" + timestamp + "] " + message + "\n");
        });
    }
    
    /**
     * Show error dialog
     */
    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Lỗi");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    /**
     * Show info dialog
     */
    private void showInfo(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Thông báo");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    // ========== P2PServiceListener Implementation ==========
    
    @Override
    public void onPeerDiscovered(PeerInfo peer) {
        Platform.runLater(() -> {
            if (!peerList.contains(peer)) {
                peerList.add(peer);
            }
            peerCountLabel.setText(peerList.size() + " Peers");
            log("✓ Phát hiện peer: " + peer.getDisplayName());
        });
    }
    
    @Override
    public void onPeerLost(PeerInfo peer) {
        Platform.runLater(() -> {
            peerList.remove(peer);
            peerCountLabel.setText(peerList.size() + " Peers");
            log("✗ Mất kết nối: " + peer.getDisplayName());
        });
    }
    
    @Override
    public void onSearchResult(SearchResponse response) {
        Platform.runLater(() -> {
            for (FileInfo file : response.getFoundFiles()) {
                searchResults.add(new SearchResultItem(file, response.getSourcePeer()));
            }
            
            // Enable download button ngay khi có kết quả đầu tiên
            if (!searchResults.isEmpty()) {
                downloadButton.setDisable(false);
            }
            
            log("📦 Tìm thấy " + response.getFoundFiles().size() + 
                " file từ " + response.getSourcePeer().getDisplayName());
        });
    }
    
    @Override
    public void onSearchComplete() {
        Platform.runLater(() -> {
            searchButton.setDisable(false);
            if (searchResults.isEmpty()) {
                log("⚠ Không tìm thấy file nào");
            } else {
                log("✓ Tìm kiếm hoàn tất: " + searchResults.size() + " kết quả");
                downloadButton.setDisable(false);
            }
        });
    }
    
    @Override
    public void onTransferProgress(String fileName, long bytesTransferred, long totalBytes) {
        Platform.runLater(() -> {
            int percent = (int) ((bytesTransferred * 100) / totalBytes);
            log("⏳ " + fileName + ": " + percent + "%");
        });
    }
    
    @Override
    public void onTransferComplete(String fileName, File file) {
        Platform.runLater(() -> {
            log("✅ Download hoàn tất: " + fileName);
            showInfo("Download thành công!\nFile: " + file.getAbsolutePath());
        });
    }
    
    @Override
    public void onTransferError(String fileName, Exception e) {
        Platform.runLater(() -> {
            log("❌ Lỗi download " + fileName + ": " + e.getMessage());
            showError("Lỗi khi download: " + e.getMessage());
        });
    }
    
    @Override
    public void onServiceStarted() {
        updateStatus("Online", "#00b894");
        log("✅ Service đã khởi động");
    }
    
    @Override
    public void onServiceStopped() {
        updateStatus("Disconnected", "#95a5a6");
        log("🛑 Service đã dừng");
    }
}
