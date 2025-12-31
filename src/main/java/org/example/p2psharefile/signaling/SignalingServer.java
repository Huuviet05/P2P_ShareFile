package org.example.p2psharefile.signaling;

import org.example.p2psharefile.model.PeerInfo;
import org.example.p2psharefile.model.SignedMessage;
import org.example.p2psharefile.security.SecurityManager;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * SignalingServer - Server trung gian cho P2P Hybrid
 * 
 * Nhiệm vụ:
 * - Nhận đăng ký từ các peers (REGISTER)
 * - Lưu trữ danh sách peers online
 * - Cung cấp danh sách peers cho client (GET_PEERS)
 * - Kiểm tra heartbeat để biết peer còn online không
 * - HỖ TRỢ chuyển tiếp PIN code giữa các peers
 * 
 * KHÔNG làm:
 * - Không lưu trữ file
 * - Không trung chuyển file
 * - Peers tự kết nối P2P với nhau sau khi biết địa chỉ
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class SignalingServer {
    
    private static final int DEFAULT_PORT = 9000;
    private static final int PEER_TIMEOUT_MS = 60000; // 60 giây không heartbeat = offline
    private static final int CLEANUP_INTERVAL_MS = 30000; // 30 giây dọn dẹp
    
    private final int port;
    private final SecurityManager securityManager;
    
    // Danh sách peers online: peerId -> PeerInfo
    private final Map<String, PeerInfo> onlinePeers = new ConcurrentHashMap<>();
    // Thời gian heartbeat cuối: peerId -> timestamp
    private final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();
    // Danh sách PIN codes: pin -> PeerInfo (peer sở hữu)
    private final Map<String, SharePINInfo> pinCodes = new ConcurrentHashMap<>();
    
    private SSLServerSocket serverSocket;
    private ExecutorService executorService;
    private ScheduledExecutorService cleanupExecutor;
    private volatile boolean running = false;
    
    /**
     * Thông tin PIN share
     */
    public static class SharePINInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String pin;
        private final PeerInfo owner;
        private final String fileName;
        private final long fileSize;
        private final String fileHash;
        private final long createdAt;
        private final long expiresAt;
        
        public SharePINInfo(String pin, PeerInfo owner, String fileName, long fileSize, String fileHash, long expiryMs) {
            this.pin = pin;
            this.owner = owner;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.fileHash = fileHash;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = createdAt + expiryMs;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
        
        // Getters
        public String getPin() { return pin; }
        public PeerInfo getOwner() { return owner; }
        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }
        public String getFileHash() { return fileHash; }
        public long getCreatedAt() { return createdAt; }
        public long getExpiresAt() { return expiresAt; }
    }
    
    public SignalingServer() throws Exception {
        this(DEFAULT_PORT);
    }
    
    public SignalingServer(int port) throws Exception {
        this.port = port;
        // Tạo SecurityManager cho server
        String serverId = "signaling-server-" + UUID.randomUUID().toString().substring(0, 8);
        this.securityManager = new SecurityManager(serverId, "Signaling Server");
    }
    
    /**
     * Khởi động server
     */
    public void start() throws IOException {
        if (running) {
            System.out.println("⚠ Signaling Server đã đang chạy");
            return;
        }
        
        running = true;
        
        // Tạo SSL Server Socket
        serverSocket = securityManager.createSSLServerSocket(port);
        serverSocket.setReuseAddress(true);
        
        executorService = Executors.newCachedThreadPool();
        cleanupExecutor = Executors.newScheduledThreadPool(1);
        
        // Thread chính lắng nghe kết nối
        executorService.submit(this::acceptConnections);
        
        // Thread dọn dẹp peers offline
        cleanupExecutor.scheduleAtFixedRate(this::cleanupOfflinePeers, 
            CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       🌐 SIGNALING SERVER ĐÃ KHỞI ĐỘNG           ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║  Port: " + port + "                                       ║");
        System.out.println("║  TLS: Đã bật ✅                                   ║");
        System.out.println("║  Chế độ: P2P Hybrid (chỉ điều phối kết nối)       ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
    }
    
    /**
     * Dừng server
     */
    public void stop() {
        if (!running) return;
        
        running = false;
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("⚠ Lỗi đóng server socket: " + e.getMessage());
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
        
        onlinePeers.clear();
        lastHeartbeat.clear();
        pinCodes.clear();
        
        System.out.println("✅ Signaling Server đã dừng");
    }
    
    /**
     * Lắng nghe kết nối từ clients
     */
    private void acceptConnections() {
        System.out.println("👂 Đang lắng nghe kết nối trên port " + port + "...");
        
        while (running) {
            try {
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                clientSocket.setSoTimeout(10000);
                clientSocket.startHandshake();
                
                // Xử lý trong thread riêng
                executorService.submit(() -> handleClient(clientSocket));
                
            } catch (SocketException e) {
                if (running) {
                    System.err.println("⚠ Socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("⚠ Lỗi accept connection: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Xử lý request từ client
     */
    private void handleClient(SSLSocket socket) {
        String clientIP = socket.getInetAddress().getHostAddress();
        
        try {
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            
            // Nhận message
            Object message = ois.readObject();
            
            if (!(message instanceof SignedMessage)) {
                System.err.println("⚠ Nhận message không hợp lệ từ " + clientIP);
                socket.close();
                return;
            }
            
            SignedMessage signedMsg = (SignedMessage) message;
            String messageType = signedMsg.getMessageType();
            
            switch (messageType) {
                case "REGISTER":
                    handleRegister(signedMsg, oos, clientIP);
                    break;
                    
                case "UNREGISTER":
                    handleUnregister(signedMsg);
                    break;
                    
                case "HEARTBEAT":
                    handleHeartbeat(signedMsg, oos);
                    break;
                    
                case "GET_PEERS":
                    handleGetPeers(signedMsg, oos);
                    break;
                    
                case "REGISTER_PIN":
                    handleRegisterPIN(signedMsg, oos);
                    break;
                    
                case "LOOKUP_PIN":
                    handleLookupPIN(signedMsg, oos);
                    break;
                    
                case "SHARE_FILE":
                    handleShareFile(signedMsg, oos);
                    break;
                    
                default:
                    System.out.println("⚠ Nhận message type không xác định: " + messageType);
            }
            
            socket.close();
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi xử lý client " + clientIP + ": " + e.getMessage());
        }
    }
    
    /**
     * Xử lý REGISTER - Đăng ký peer mới
     */
    private void handleRegister(SignedMessage msg, ObjectOutputStream oos, String clientIP) throws IOException {
        PeerInfo peer = (PeerInfo) msg.getPayload();
        
        // Cập nhật IP thực tế từ socket
        peer.setIpAddress(clientIP);
        
        // Lưu peer
        String peerId = peer.getPeerId();
        onlinePeers.put(peerId, peer);
        lastHeartbeat.put(peerId, System.currentTimeMillis());
        
        System.out.println("✅ Peer đăng ký: " + peer.getDisplayName() + " (" + clientIP + ":" + peer.getPort() + ")");
        System.out.println("   📊 Tổng peers online: " + onlinePeers.size());
        
        // Gửi ACK
        SignedMessage ack = new SignedMessage("ACK", "server", "", "Đăng ký thành công");
        oos.writeObject(ack);
        oos.flush();
    }
    
    /**
     * Xử lý UNREGISTER - Peer ngắt kết nối
     */
    private void handleUnregister(SignedMessage msg) {
        PeerInfo peer = (PeerInfo) msg.getPayload();
        String peerId = peer.getPeerId();
        
        onlinePeers.remove(peerId);
        lastHeartbeat.remove(peerId);
        
        // Xóa các PIN của peer này
        pinCodes.entrySet().removeIf(entry -> entry.getValue().getOwner().getPeerId().equals(peerId));
        
        System.out.println("👋 Peer ngắt kết nối: " + peer.getDisplayName());
        System.out.println("   📊 Còn " + onlinePeers.size() + " peers online");
    }
    
    /**
     * Xử lý HEARTBEAT
     */
    private void handleHeartbeat(SignedMessage msg, ObjectOutputStream oos) throws IOException {
        PeerInfo peer = (PeerInfo) msg.getPayload();
        String peerId = peer.getPeerId();
        
        // Cập nhật thời gian heartbeat
        lastHeartbeat.put(peerId, System.currentTimeMillis());
        
        // Gửi ACK
        SignedMessage ack = new SignedMessage("ACK", "server", "", "OK");
        oos.writeObject(ack);
        oos.flush();
    }
    
    /**
     * Xử lý GET_PEERS - Trả về danh sách peers online
     */
    private void handleGetPeers(SignedMessage msg, ObjectOutputStream oos) throws IOException {
        PeerInfo requester = (PeerInfo) msg.getPayload();
        
        // Lọc bỏ chính mình
        List<PeerInfo> peers = new ArrayList<>();
        for (PeerInfo peer : onlinePeers.values()) {
            if (!peer.getPeerId().equals(requester.getPeerId())) {
                peers.add(peer);
            }
        }
        
        System.out.println("📋 Gửi danh sách " + peers.size() + " peer(s) cho " + requester.getDisplayName());
        
        // Gửi danh sách
        SignedMessage response = new SignedMessage("PEER_LIST", "server", "", (Serializable) peers);
        oos.writeObject(response);
        oos.flush();
    }
    
    /**
     * Xử lý REGISTER_PIN - Đăng ký PIN code
     */
    @SuppressWarnings("unchecked")
    private void handleRegisterPIN(SignedMessage msg, ObjectOutputStream oos) throws IOException {
        Map<String, Object> data = (Map<String, Object>) msg.getPayload();
        
        String pin = (String) data.get("pin");
        PeerInfo owner = (PeerInfo) data.get("owner");
        String fileName = (String) data.get("fileName");
        long fileSize = (Long) data.get("fileSize");
        String fileHash = (String) data.get("fileHash");
        long expiryMs = (Long) data.get("expiryMs");
        
        // Lưu PIN
        SharePINInfo pinInfo = new SharePINInfo(pin, owner, fileName, fileSize, fileHash, expiryMs);
        pinCodes.put(pin, pinInfo);
        
        System.out.println("🔑 Đăng ký PIN: " + pin + " - " + fileName + " (từ " + owner.getDisplayName() + ")");
        
        // Gửi ACK
        SignedMessage ack = new SignedMessage("ACK", "server", "", "PIN đã đăng ký");
        oos.writeObject(ack);
        oos.flush();
    }
    
    /**
     * Xử lý LOOKUP_PIN - Tìm kiếm PIN code
     */
    private void handleLookupPIN(SignedMessage msg, ObjectOutputStream oos) throws IOException {
        String pin = (String) msg.getPayload();
        
        SharePINInfo pinInfo = pinCodes.get(pin);
        
        if (pinInfo == null) {
            System.out.println("⚠ PIN không tìm thấy: " + pin);
            SignedMessage response = new SignedMessage("PIN_NOT_FOUND", "server", "", null);
            oos.writeObject(response);
        } else if (pinInfo.isExpired()) {
            System.out.println("⚠ PIN đã hết hạn: " + pin);
            pinCodes.remove(pin);
            SignedMessage response = new SignedMessage("PIN_EXPIRED", "server", "", null);
            oos.writeObject(response);
        } else {
            System.out.println("✅ Tìm thấy PIN: " + pin + " - " + pinInfo.getFileName());
            SignedMessage response = new SignedMessage("PIN_FOUND", "server", "", pinInfo);
            oos.writeObject(response);
        }
        
        oos.flush();
    }
    
    /**
     * Xử lý SHARE_FILE - Thông báo file đang share
     */
    @SuppressWarnings("unchecked")
    private void handleShareFile(SignedMessage msg, ObjectOutputStream oos) throws IOException {
        Map<String, Object> fileInfo = (Map<String, Object>) msg.getPayload();
        
        String peerId = (String) fileInfo.get("peerId");
        String fileName = (String) fileInfo.get("fileName");
        
        System.out.println("📁 Peer " + peerId.substring(0, 8) + "... đang share: " + fileName);
        
        // Gửi ACK
        SignedMessage ack = new SignedMessage("ACK", "server", "", "OK");
        oos.writeObject(ack);
        oos.flush();
    }
    
    /**
     * Dọn dẹp peers đã offline (không heartbeat quá lâu)
     */
    private void cleanupOfflinePeers() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();
        
        for (Map.Entry<String, Long> entry : lastHeartbeat.entrySet()) {
            if (now - entry.getValue() > PEER_TIMEOUT_MS) {
                toRemove.add(entry.getKey());
            }
        }
        
        for (String peerId : toRemove) {
            PeerInfo peer = onlinePeers.remove(peerId);
            lastHeartbeat.remove(peerId);
            
            // Xóa PIN của peer này
            pinCodes.entrySet().removeIf(e -> e.getValue().getOwner().getPeerId().equals(peerId));
            
            if (peer != null) {
                System.out.println("⏰ Peer timeout: " + peer.getDisplayName() + " (không heartbeat > 60s)");
            }
        }
        
        // Xóa PIN hết hạn
        pinCodes.entrySet().removeIf(e -> e.getValue().isExpired());
    }
    
    // ===== GETTERS =====
    
    public int getPort() {
        return port;
    }
    
    public int getOnlinePeerCount() {
        return onlinePeers.size();
    }
    
    public int getActivePINCount() {
        return pinCodes.size();
    }
    
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Main method để chạy standalone server
     */
    public static void main(String[] args) {
        try {
            int port = DEFAULT_PORT;
            if (args.length > 0) {
                port = Integer.parseInt(args[0]);
            }
            
            SignalingServer server = new SignalingServer(port);
            server.start();
            
            // Giữ server chạy
            System.out.println("\n📌 Nhấn Ctrl+C để dừng server...\n");
            
            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n🛑 Đang dừng Signaling Server...");
                server.stop();
            }));
            
            // Chờ vô hạn
            Thread.currentThread().join();
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi khởi động Signaling Server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
