package org.example.p2psharefile;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * MainApplication - Ứng dụng chính P2P Share File
 * 
 * Đây là entry point của ứng dụng JavaFX
 * 
 
 */
public class MainApplication extends Application {
    
    private static int customPort = 0; // 0 = auto, được truyền từ args
    
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(
            MainApplication.class.getResource("main-view.fxml")
        );
        
        // Load FXML và lấy controller
        javafx.scene.Parent root = fxmlLoader.load();
        
        // Truyền port vào controller
        var controller = (org.example.p2psharefile.controller.MainController) fxmlLoader.getController();
        controller.setCustomPort(customPort);
        
        Scene scene = new Scene(root, 1100, 750);
        
        // Load custom CSS stylesheet
        String css = MainApplication.class.getResource("styles.css").toExternalForm();
        scene.getStylesheets().add(css);
        
        stage.setTitle("P2P ShareFile - Modern File Sharing");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(550);
        
        // Xử lý khi đóng app
        stage.setOnCloseRequest(event -> {
            System.out.println("👋 Đang đóng ứng dụng...");
            System.exit(0);
        });
        
        stage.show();
        
        System.out.println("=".repeat(60));
        System.out.println("🎉 P2P SHARE FILE APPLICATION");
        System.out.println("=".repeat(60));
        System.out.println("📚 Ứng dụng chia sẻ file Peer-to-Peer");
        System.out.println("✨ Tính năng:");
        System.out.println("   - P2P LAN: Kết nối trực tiếp trong mạng LAN (TCP + TLS)");
        System.out.println("   - P2P Internet: Kết nối qua Signaling Server");
        System.out.println("   - File Transfer với Compression & Encryption");
        System.out.println("   - PIN Code Sharing (Send Anywhere style)");
        System.out.println("   - UltraView Preview (Image, PDF, Archive)");
        System.out.println("   - Security: TLS + AES-256 + ECDSA Signatures");
        System.out.println("=".repeat(60));
    }

    public static void main(String[] args) {
        // Parse port từ command line arguments
        if (args.length > 0) {
            try {
                customPort = Integer.parseInt(args[0]);
                System.out.println("⚙️  Port được chỉ định: " + customPort);
            } catch (NumberFormatException e) {
                System.err.println("⚠️  Port không hợp lệ: " + args[0] + ". Sử dụng port mặc định (auto).");
                customPort = 0;
            }
        } else {
            System.out.println("⚙️  Không có port được chỉ định. Sử dụng port mặc định (auto).");
            customPort = 0;
        }
        
        launch();
    }
}
