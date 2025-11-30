package org.example.p2psharefile.service;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.network.PeerDiscovery;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * PINCodeService - Dịch vụ quản lý mã PIN chia sẻ file
 * 
 * Giống Send Anywhere:
 * 1. User chọn file → Tạo mã PIN 6 số
 * 2. Chia sẻ PIN cho người khác
 * 3. Người khác nhập PIN → Download file
 * 4. PIN hết hạn sau 10 phút
 */
public class PINCodeService {
    
    private static final int PIN_LENGTH = 6;
    private static final long DEFAULT_EXPIRY = 600000; // 10 phút
    private static final int PIN_SERVER_PORT = 8887;   // Port để sync PIN giữa peers
    
    private final PeerInfo localPeer;
    private final PeerDiscovery peerDiscovery;
    private final Map<String, ShareSession> localSessions;  // PIN do mình tạo
    private final Map<String, ShareSession> globalSessions; // PIN từ tất cả peers
    
    private ServerSocket pinServer;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    // Listeners
    private final List<PINCodeListener> listeners;
    
    public interface PINCodeListener {
        void onPINCreated(ShareSession session);
        void onPINExpired(String pin);
        void onPINReceived(ShareSession session);
    }
    
    public PINCodeService(PeerInfo localPeer, PeerDiscovery peerDiscovery) {
        this.localPeer = localPeer;
        this.peerDiscovery = peerDiscovery;
        this.localSessions = new ConcurrentHashMap<>();
        this.globalSessions = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }
    
    /**
     * Khởi động dịch vụ PIN
     */
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        pinServer = new ServerSocket(PIN_SERVER_PORT);
        executorService = Executors.newCachedThreadPool();
        
        // Thread lắng nghe PIN từ peer khác
        executorService.submit(this::listenForPINs);
        
        // Thread kiểm tra PIN hết hạn
        executorService.submit(this::checkExpiredPINs);
        
        System.out.println("✓ PIN Code Service đã khởi động trên port " + PIN_SERVER_PORT);
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
            System.err.println("Lỗi khi đóng PIN server: " + e.getMessage());
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        System.out.println("✓ PIN Code Service đã dừng");
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
        
        System.out.println("✓ Đã tạo PIN: " + pin + " cho file: " + fileInfo.getFileName());

        // Gửi PIN đến các peer khác (sử dụng PeerDiscovery để lấy danh sách peers)
        sendPINToAllPeers(session);
        
        // Thông báo listeners
        notifyPINCreated(session);
        
        return session;
    }
    
    /**
     * Tìm session bằng PIN
     */
    public ShareSession findByPIN(String pin) {
        ShareSession session = globalSessions.get(pin);
        if (session != null && !session.isExpired()) {
            return session;
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
        System.out.println("📡 Gửi PIN: " + session.getPin() + " đến " + peers.size() + " peer(s)");

        for (PeerInfo peer : peers) {
            // Không gửi cho chính mình
            if (peer.getPeerId().equals(localPeer.getPeerId())) continue;

            sendPINToPeerTcp(session, peer);
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
     * Xử lý PIN message từ peer khác
     */
    private void handlePINMessage(Socket socket) {
        try (ObjectInputStream ois = new ObjectInputStream(socket.getInputStream())) {
            
            ShareSession session = (ShareSession) ois.readObject();
            
            // Lưu vào global sessions
            globalSessions.put(session.getPin(), session);
            
            System.out.println("📥 Nhận PIN: " + session.getPin() + 
                             " từ " + session.getOwnerPeer().getDisplayName());
            
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
     * Gửi PIN đến một peer cụ thể
     */
    public void sendPINToPeerTcp(ShareSession session, PeerInfo peer) {
        try (Socket socket = new Socket(peer.getIpAddress(), PIN_SERVER_PORT);
             ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream())) {

            oos.writeObject(session);
            oos.flush();

            System.out.println("📤 Đã gửi PIN đến " + peer.getDisplayName());

        } catch (IOException e) {
            System.err.println("Lỗi khi gửi PIN: " + e.getMessage());
        }
    }
    
    // ========== Listener Management ==========
    
    public void addListener(PINCodeListener listener) {
        listeners.add(listener);
    }
    
    public void removeListener(PINCodeListener listener) {
        listeners.remove(listener);
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
}
