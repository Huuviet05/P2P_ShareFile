package org.example.p2psharefile.signaling;

/**
 * StandaloneSignalingServer - Khởi động Signaling Server độc lập
 * 
 * Cách sử dụng:
 *   java -cp <classpath> org.example.p2psharefile.signaling.StandaloneSignalingServer [port]
 * 
 * Mặc định:
 *   Port: 9000
 * 
 * Signaling Server chỉ làm nhiệm vụ:
 * - Lưu danh sách peers online
 * - Cho phép peers tìm nhau
 * - Lưu và tra cứu PIN codes
 * - KHÔNG lưu trữ hay trung chuyển file
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class StandaloneSignalingServer {
    
    public static void main(String[] args) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║     P2P SHARE FILE - SIGNALING SERVER (STANDALONE)       ║");
        System.out.println("║                                                          ║");
        System.out.println("║  Mô hình: P2P Hybrid                                      ║");
        System.out.println("║  Server này chỉ điều phối kết nối, không lưu trữ file    ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
        
        try {
            int port = 9000;
            
            // Đọc port từ args nếu có
            if (args.length > 0) {
                try {
                    port = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    System.err.println("⚠ Port không hợp lệ, sử dụng port mặc định: 9000");
                }
            }
            
            // Khởi động server
            SignalingServer server = new SignalingServer(port);
            server.start();
            
            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Đang tắt Signaling Server...");
                server.stop();
                System.out.println("👋 Tạm biệt!");
            }));
            
            System.out.println();
            System.out.println("📌 Thông tin kết nối:");
            System.out.println("   - Host: <your-ip>:" + port);
            System.out.println("   - Protocol: TLS (bảo mật)");
            System.out.println();
            System.out.println("📋 Để kết nối từ client:");
            System.out.println("   p2pService.setSignalingServerAddress(\"<your-ip>\", " + port + ");");
            System.out.println();
            System.out.println("⌨  Nhấn Ctrl+C để dừng server...");
            System.out.println();
            
            // Giữ server chạy
            while (server.isRunning()) {
                Thread.sleep(10000);
                
                // Log thống kê mỗi 10 giây (nếu có peers)
                if (server.getOnlinePeerCount() > 0 || server.getActivePINCount() > 0) {
                    System.out.println("📊 Thống kê: " + 
                        server.getOnlinePeerCount() + " peer(s) online, " +
                        server.getActivePINCount() + " PIN(s) hoạt động");
                }
            }
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi khởi động Signaling Server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
