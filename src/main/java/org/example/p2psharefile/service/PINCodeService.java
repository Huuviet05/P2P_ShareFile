package org.example.p2psharefile.service;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.network.PeerDiscovery;
import org.example.p2psharefile.network.SignalingClient;
import org.example.p2psharefile.security.SecurityManager;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;

/**
 * PINCodeService - Dịch vụ quản lý mã PIN chia sẻ file (với TLS + Signatures)
 * 
 * Giống Send Anywhere:
 * 1. User chọn file → Tạo mã PIN 6 số
 * 2. Chia sẻ PIN cho người khác (qua TLS channel)
 * 3. Người khác nhập PIN → Download file
 * 4. PIN hết hạn sau 10 phút
 * 
 * Hỗ trợ 2 chế độ:
 * - LAN Mode: P2P thuần túy - PIN được gửi qua mạng LAN
 * - Internet Mode: P2P Hybrid - PIN được đăng ký với Signaling Server
 * 
 * Security improvements:
 * - PIN messages được ký bằng ECDSA để chống forgery
 * - TLS encryption cho PIN transmission
 */
public class PINCodeService {
    
    private static final int PIN_LENGTH = 6;
    private static final long DEFAULT_EXPIRY = 600000; // 10 phút
    private static final int PIN_SERVER_PORT = 10002;   // Cố định
    
    private final PeerInfo localPeer;
    private final PeerDiscovery peerDiscovery;
    private final SecurityManager securityManager;
    private final int pinServerPort; // Port động
    private final Map<String, ShareSession> localSessions;  // PIN do mình tạo
    private final Map<String, ShareSession> globalSessions; // PIN từ tất cả peers
    
    // Connection mode: true = P2P only (LAN), false = P2P Hybrid (Internet với signaling)
    private volatile boolean p2pOnlyMode = true;
    
    // Signaling Client cho P2P Hybrid mode
    private SignalingClient signalingClient;
    
    private SSLServerSocket pinServer;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    // Listeners
    private final List<PINCodeListener> listeners;
    
    public interface PINCodeListener {
        void onPINCreated(ShareSession session);
        void onPINExpired(String pin);
        void onPINReceived(ShareSession session);
    }
    
    public PINCodeService(PeerInfo localPeer, PeerDiscovery peerDiscovery, SecurityManager securityManager) {
        this.localPeer = localPeer;
        this.peerDiscovery = peerDiscovery;
        this.securityManager = securityManager;
        this.pinServerPort = PIN_SERVER_PORT; // Cố định
        this.localSessions = new ConcurrentHashMap<>();
        this.globalSessions = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }
    
    /**
     * Khởi động dịch vụ PIN (với TLS)
     */
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        pinServer = securityManager.createSSLServerSocket(pinServerPort);
        executorService = Executors.newCachedThreadPool();
        
        // Thread lắng nghe PIN từ peer khác
        executorService.submit(this::listenForPINs);
        
        // Thread kiểm tra PIN hết hạn
        executorService.submit(this::checkExpiredPINs);
        
        System.out.println("✓ PIN Code Service (TLS) đã khởi động trên port " + pinServerPort);
    }
    
    /**
     * Dừng dịch vụ
     */
    public void stop() {
        running = false;
        
        try {
            if (pinServer != null && !pinServer.isClosed()) {
                pinServer.close();
            }
        } catch (IOException e) {
            System.err.println("⚠ Lỗi khi đóng PIN server: " + e.getMessage());
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        System.out.println("✓ PIN Code Service đã dừng");
    }
    
    /**
     * Set Signaling Client cho P2P Hybrid mode
     */
    public void setSignalingClient(SignalingClient client) {
        this.signalingClient = client;
        System.out.println("✓ PINCodeService đã kết nối với SignalingClient");
    }
    
    /**
     * Tạo mã PIN mới cho file
     */
    public ShareSession createPIN(FileInfo fileInfo) {
        return createPIN(fileInfo, DEFAULT_EXPIRY);
    }
    
    /**
     * Tạo mã PIN với thời gian tùy chỉnh
     */
    public ShareSession createPIN(FileInfo fileInfo, long expiryMillis) {
        // Tạo PIN ngẫu nhiên
        String pin = generateUniquePIN();
        
        // Tạo session
        long expiryTime = System.currentTimeMillis() + expiryMillis;
        ShareSession session = new ShareSession(pin, fileInfo, localPeer, expiryTime);
        
        // Lưu local
        localSessions.put(pin, session);
        globalSessions.put(pin, session);
        
        System.out.println("✓ Đã tạo PIN: " + pin + " cho file: " + fileInfo.getFileName() + 
                          " (Chế độ: " + (p2pOnlyMode ? "P2P LAN" : "P2P Internet") + ")");

        if (p2pOnlyMode) {
            // Chế độ LAN: Gửi PIN đến tất cả peers qua TCP
            sendPINToAllPeers(session);
        } else {
            // Chế độ Internet: Đăng ký PIN với Signaling Server
            registerPINWithSignalingServer(session, expiryMillis);
            // Cũng gửi đến LAN peers nếu có
            sendPINToAllPeers(session);
        }
        
        // Thông báo listeners
        notifyPINCreated(session);
        
        return session;
    }
    
    /**
     * Đăng ký PIN với Signaling Server (cho P2P Hybrid mode)
     */
    private void registerPINWithSignalingServer(ShareSession session, long expiryMillis) {
        if (signalingClient == null || !signalingClient.isConnected()) {
            System.out.println("⚠ Signaling Server chưa kết nối, chỉ gửi PIN qua LAN");
            return;
        }
        
        try {
            // Tạo socket kết nối đến Signaling Server
            SSLSocket socket = securityManager.createSSLSocket(
                signalingClient.getServerHost(), 
                signalingClient.getServerPort()
            );
            socket.connect(new InetSocketAddress(
                signalingClient.getServerHost(), 
                signalingClient.getServerPort()
            ), 5000);
            socket.setSoTimeout(10000);
            socket.startHandshake();
            
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            
            // Tạo data để gửi
            Map<String, Object> pinData = new HashMap<>();
            pinData.put("pin", session.getPin());
            pinData.put("owner", localPeer);
            pinData.put("fileName", session.getFileInfo().getFileName());
            pinData.put("fileSize", session.getFileInfo().getFileSize());
            pinData.put("fileHash", session.getFileInfo().getFileHash());
            pinData.put("expiryMs", expiryMillis);
            
            // Tạo signed message
            SignedMessage registerMsg = createSignedMessage("REGISTER_PIN", pinData);
            oos.writeObject(registerMsg);
            oos.flush();
            
            // Nhận ACK
            Object response = ois.readObject();
            if (response instanceof SignedMessage) {
                SignedMessage respMsg = (SignedMessage) response;
                if ("ACK".equals(respMsg.getMessageType())) {
                    System.out.println("✅ Đã đăng ký PIN với Signaling Server: " + session.getPin());
                }
            }
            
            socket.close();
            
        } catch (Exception e) {
            System.err.println("⚠ Lỗi đăng ký PIN với Signaling Server: " + e.getMessage());
        }
    }
    
    /**
     * Tìm session bằng PIN
     */
    public ShareSession findByPIN(String pin) {
        System.out.println("🔍 Tìm PIN: " + pin + " (Chế độ: " + (p2pOnlyMode ? "P2P LAN" : "P2P Internet") + ")");
        
        // Bước 1: Tìm trong local/global cache
        ShareSession session = globalSessions.get(pin);
        if (session != null && !session.isExpired()) {
            System.out.println("✓ Tìm thấy PIN trong cache: " + pin);
            return session;
        }
        
        // Bước 2: Nếu ở chế độ Internet, tìm trên Signaling Server
        if (!p2pOnlyMode && signalingClient != null && signalingClient.isConnected()) {
            session = lookupPINFromSignalingServer(pin);
            if (session != null) {
                // Cache lại để sử dụng sau
                globalSessions.put(pin, session);
                return session;
            }
        }
        
        System.out.println("⚠ Không tìm thấy PIN: " + pin);
        return null;
    }
    
    /**
     * Tìm PIN từ Signaling Server
     */
    private ShareSession lookupPINFromSignalingServer(String pin) {
        try {
            System.out.println("🌐 Đang tìm PIN trên Signaling Server: " + pin);
            
            SSLSocket socket = securityManager.createSSLSocket(
                signalingClient.getServerHost(), 
                signalingClient.getServerPort()
            );
            socket.connect(new InetSocketAddress(
                signalingClient.getServerHost(), 
                signalingClient.getServerPort()
            ), 5000);
            socket.setSoTimeout(10000);
            socket.startHandshake();
            
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            
            // Gửi LOOKUP_PIN request
            SignedMessage lookupMsg = createSignedMessage("LOOKUP_PIN", pin);
            oos.writeObject(lookupMsg);
            oos.flush();
            
            // Nhận response
            Object response = ois.readObject();
            socket.close();
            
            if (response instanceof SignedMessage) {
                SignedMessage respMsg = (SignedMessage) response;
                
                if ("PIN_FOUND".equals(respMsg.getMessageType())) {
                    // Chuyển đổi response thành ShareSession
                    Object payload = respMsg.getPayload();
                    if (payload instanceof org.example.p2psharefile.signaling.SignalingServer.SharePINInfo) {
                        org.example.p2psharefile.signaling.SignalingServer.SharePINInfo pinInfo = 
                            (org.example.p2psharefile.signaling.SignalingServer.SharePINInfo) payload;
                        
                        // Tạo FileInfo từ PIN info
                        FileInfo fileInfo = new FileInfo(
                            pinInfo.getFileName(),
                            pinInfo.getFileSize(),
                            "" // Path sẽ được lấy từ owner
                        );
                        fileInfo.setFileHash(pinInfo.getFileHash());
                        
                        // Tạo ShareSession
                        ShareSession session = new ShareSession(
                            pin, 
                            fileInfo, 
                            pinInfo.getOwner(), 
                            pinInfo.getExpiresAt()
                        );
                        
                        System.out.println("✅ Tìm thấy PIN trên Signaling Server: " + pin);
                        System.out.println("   📁 File: " + pinInfo.getFileName());
                        System.out.println("   👤 Chủ sở hữu: " + pinInfo.getOwner().getDisplayName());
                        
                        return session;
                    }
                } else if ("PIN_NOT_FOUND".equals(respMsg.getMessageType())) {
                    System.out.println("⚠ PIN không tìm thấy trên Signaling Server");
                } else if ("PIN_EXPIRED".equals(respMsg.getMessageType())) {
                    System.out.println("⚠ PIN đã hết hạn trên Signaling Server");
                }
            }
            
        } catch (Exception e) {
            System.err.println("⚠ Lỗi tìm PIN trên Signaling Server: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Hủy PIN
     */
    public void cancelPIN(String pin) {
        ShareSession session = localSessions.get(pin);
        if (session != null) {
            session.cancel();
            localSessions.remove(pin);
            globalSessions.remove(pin);
            System.out.println("✓ Đã hủy PIN: " + pin);
        }
    }
    
    /**
     * Lấy tất cả PIN đang active (do mình tạo)
     */
    public List<ShareSession> getActiveSessions() {
        return new ArrayList<>(localSessions.values());
    }
    
    /**
     * Tạo PIN ngẫu nhiên duy nhất
     */
    private String generateUniquePIN() {
        String pin;
        do {
            pin = generateRandomPIN();
        } while (globalSessions.containsKey(pin));
        return pin;
    }
    
    /**
     * Tạo PIN ngẫu nhiên 6 số
     */
    private String generateRandomPIN() {
        Random random = new Random();
        int number = random.nextInt(999999);
        return String.format("%0" + PIN_LENGTH + "d", number);
    }
    
    /**
     * Gửi PIN đến tất cả peers
     */
    private void sendPINToAllPeers(ShareSession session) {
        if (peerDiscovery == null) {
            System.out.println("⚠ Không có PeerDiscovery để gửi PIN: " + session.getPin());
            return;
        }

        List<PeerInfo> peers = peerDiscovery.getDiscoveredPeers();
        
        // Lọc peer hợp lệ
        List<PeerInfo> validPeers = new ArrayList<>();
        for (PeerInfo peer : peers) {
            if (!peer.getPeerId().equals(localPeer.getPeerId())) {
                validPeers.add(peer);
            }
        }
        
        if (validPeers.isEmpty()) {
            System.out.println("✓ PIN đã tạo, nhưng không có peer nào để gửi");
            return;
        }
        
        System.out.println("📡 Gửi PIN: " + session.getPin() + " đến " + validPeers.size() + " peer(s)");

        for (PeerInfo peer : validPeers) {
            sendPINToPeerTcp(session, peer);
        }
    }
    
    /**
     * Kiểm tra IP có phải private IP (LAN) không
     */
    private boolean isPrivateIP(String ip) {
        if (ip == null) return false;
        
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            
            // 10.0.0.0/8
            if (first == 10) return true;
            // 172.16.0.0/12
            if (first == 172 && second >= 16 && second <= 31) return true;
            // 192.168.0.0/16
            if (first == 192 && second == 168) return true;
            // localhost
            if (first == 127) return true;
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Thread lắng nghe PIN từ peer khác
     */
    private void listenForPINs() {
        while (running) {
            try {
                Socket socket = pinServer.accept();
                executorService.submit(() -> handlePINMessage(socket));
            } catch (SocketException e) {
                // Server đã đóng
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Lỗi khi accept PIN connection: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Xử lý PIN message từ peer khác (với signature verification)
     */
    private void handlePINMessage(Socket socket) {
        try (ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {
            
            // Nhận SignedMessage
            SignedMessage signedMsg = (SignedMessage) ois.readObject();
            ShareSession session = (ShareSession) signedMsg.getPayload();
            
            // Verify signature
            PeerInfo senderPeer = session.getOwnerPeer();
            if (!verifyPINSignature(signedMsg, senderPeer)) {
                System.err.println("❌ [Security] Invalid PIN signature from: " + senderPeer.getDisplayName());
                return;
            }
            
            // Lưu vào global sessions
            globalSessions.put(session.getPin(), session);
            
            System.out.println("📥 Nhận PIN: " + session.getPin() + 
                             " từ " + session.getOwnerPeer().getDisplayName() + " ✅ Verified");
            
            // Thông báo listeners
            notifyPINReceived(session);
            
        } catch (Exception e) {
            System.err.println("Lỗi khi xử lý PIN message: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Thread kiểm tra PIN hết hạn
     */
    private void checkExpiredPINs() {
        while (running) {
            try {
                Thread.sleep(5000); // Check mỗi 5 giây
                
                List<String> expiredPins = new ArrayList<>();
                
                // Kiểm tra local sessions
                for (ShareSession session : localSessions.values()) {
                    if (session.isExpired()) {
                        expiredPins.add(session.getPin());
                    }
                }
                
                // Xóa expired PINs
                for (String pin : expiredPins) {
                    localSessions.remove(pin);
                    globalSessions.remove(pin);
                    System.out.println("⏰ PIN đã hết hạn: " + pin);
                    notifyPINExpired(pin);
                }
                
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    /**
     * Gửi PIN đến một peer cụ thể (với TLS + signature)
     */
    public void sendPINToPeerTcp(ShareSession session, PeerInfo peer) {
        try {
            SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), PIN_SERVER_PORT);
            socket.connect(new InetSocketAddress(peer.getIpAddress(), PIN_SERVER_PORT), 3000);
            socket.setSoTimeout(5000);
            socket.startHandshake();
            
            try (ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {
                
                // Tạo signed message
                SignedMessage signedMsg = createSignedPINMessage(session);
                
                oos.writeObject(signedMsg);
                oos.flush();

                System.out.println("📤 Đã gửi PIN (signed) đến " + peer.getDisplayName());

            } finally {
                socket.close();
            }

        } catch (IOException e) {
            System.err.println("Lỗi khi gửi PIN: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("❌ Lỗi tạo signed message: " + e.getMessage());
        }
    }
    
    // ========== Security Helper Methods ==========
    
    /**
     * Tạo signed PIN message
     */
    private SignedMessage createSignedPINMessage(ShareSession session) throws Exception {
        String message = "PIN:" + session.getPin() + ":" + session.getFileInfo().getFileName();
        String signature = securityManager.signMessage(message);
        return new SignedMessage("PIN", localPeer.getPeerId(), signature, session);
    }
    
    /**
     * Verify signature của PIN message
     */
    private boolean verifyPINSignature(SignedMessage signedMsg, PeerInfo senderPeer) {
        try {
            if (senderPeer.getPublicKey() == null) {
                System.err.println("❌ [Security] Sender has no public key");
                return false;
            }
            
            PublicKey publicKey = SecurityManager.decodePublicKey(senderPeer.getPublicKey());
            ShareSession session = (ShareSession) signedMsg.getPayload();
            
            String message = "PIN:" + session.getPin() + ":" + session.getFileInfo().getFileName();
            
            return securityManager.verifySignature(message, signedMsg.getSignature(), publicKey);
            
        } catch (Exception e) {
            System.err.println("❌ [Security] Error verifying PIN signature: " + e.getMessage());
            return false;
        }
    }
    
    // ========== Listener Management ==========
    
    public void addListener(PINCodeListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(PINCodeListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Set connection mode
     * @param p2pOnly true = P2P only (LAN), false = P2P Hybrid (Internet với signaling)
     */
    public void setP2POnlyMode(boolean p2pOnly) {
        this.p2pOnlyMode = p2pOnly;
        System.out.println("🔧 PINCodeService mode: " + (p2pOnly ? "P2P (LAN)" : "P2P Hybrid (Internet)"));
    }
    
    /**
     * Get current connection mode
     */
    public boolean isP2POnlyMode() {
        return p2pOnlyMode;
    }
    
    private void notifyPINCreated(ShareSession session) {
        for (PINCodeListener listener : listeners) {
            try {
                listener.onPINCreated(session);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
    
    private void notifyPINExpired(String pin) {
        for (PINCodeListener listener : listeners) {
            try {
                listener.onPINExpired(pin);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
    
    private void notifyPINReceived(ShareSession session) {
        for (PINCodeListener listener : listeners) {
            try {
                listener.onPINReceived(session);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Tạo signed message cho giao tiếp với Signaling Server
     */
    private SignedMessage createSignedMessage(String type, Object payload) {
        try {
            String signature = securityManager.signMessage(type + payload.toString());
            return new SignedMessage(type, localPeer.getPeerId(), signature, payload);
        } catch (Exception e) {
            System.err.println("❌ Lỗi ký message: " + e.getMessage());
            return new SignedMessage(type, localPeer.getPeerId(), "", payload);
        }
    }
}
