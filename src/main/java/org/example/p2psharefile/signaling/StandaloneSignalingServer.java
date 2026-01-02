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
            
            // Lấy IP thực tế của máy
            String localIP = getLocalIPAddress();
            
            System.out.println();
            System.out.println("╔══════════════════════════════════════════════════════════╗");
            System.out.println("║                    📌 THÔNG TIN KẾT NỐI                  ║");
            System.out.println("╠══════════════════════════════════════════════════════════╣");
            System.out.println("║  IP Server: " + padRight(localIP, 43) + "║");
            System.out.println("║  Port: " + padRight(String.valueOf(port), 48) + "║");
            System.out.println("║  Địa chỉ đầy đủ: " + padRight(localIP + ":" + port, 38) + "║");
            System.out.println("╠══════════════════════════════════════════════════════════╣");
            System.out.println("║  🔒 Protocol: TLS (bảo mật end-to-end)                   ║");
            System.out.println("╚══════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.println("📋 HƯỚNG DẪN KẾT NỐI TỪ CÁC PEERS:");
            System.out.println("   1. Chạy MainApplication trên các máy khác");
            System.out.println("   2. Click nút 'Internet' trên giao diện");
            System.out.println("   3. Nhập: Host = " + localIP + ", Port = " + port);
            System.out.println();
            System.out.println("⌨  Nhấn Ctrl+C để dừng server...");
            System.out.println();
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
    
    /**
     * Lấy địa chỉ IP local (ưu tiên IPv4 không phải loopback)
     */
    private static String getLocalIPAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = 
                java.net.NetworkInterface.getNetworkInterfaces();
            
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                
                // Bỏ qua interface ảo và loopback
                if (networkInterface.isLoopback() || !networkInterface.isUp()) continue;
                String name = networkInterface.getDisplayName().toLowerCase();
                if (name.contains("virtual") || name.contains("vmware") || 
                    name.contains("vbox") || name.contains("docker")) continue;
                
                java.util.Enumeration<java.net.InetAddress> addresses = 
                    networkInterface.getInetAddresses();
                
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    // Chỉ lấy IPv4
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // Fallback
        }
        return "localhost";
    }
    
    /**
     * Padding string để căn lề
     */
    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
}
