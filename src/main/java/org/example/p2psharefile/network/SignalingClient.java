package org.example.p2psharefile.network;

import org.example.p2psharefile.model.PeerInfo;
import org.example.p2psharefile.model.SignedMessage;
import org.example.p2psharefile.security.SecurityManager;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * SignalingClient - Kết nối với Signaling Server để tìm peers qua Internet
 * 
 * Mô hình P2P Hybrid:
 * - Signaling Server chỉ làm nhiệm vụ trung gian để peers tìm nhau
 * - SAU KHI tìm được nhau, các peers kết nối P2P trực tiếp
 * - Signaling Server KHÔNG lưu trữ hay trung chuyển file
 * 
 * Quy trình:
 * 1. Client đăng ký với Signaling Server (REGISTER)
 * 2. Client lấy danh sách peers online (GET_PEERS)
 * 3. Client kết nối P2P trực tiếp với peer (qua IP:Port từ danh sách)
 * 4. Heartbeat định kỳ để duy trì kết nối với server
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class SignalingClient {
    
    // ==================== CẤU HÌNH SIGNALING SERVER ====================
    // Để deploy lên cloud, thay đổi URL ở đây:
    // Ví dụ Render.com: "p2p-signaling-server.onrender.com"
    // Ví dụ Railway: "p2p-signaling.up.railway.app"
    // Ví dụ tự host: "your-server-ip" hoặc "your-domain.com"
    // ===================================================================
    
    // Server mặc định - THAY ĐỔI KHI DEPLOY LÊN CLOUD
    private static final String DEFAULT_SERVER_HOST = "localhost";  // TODO: Thay bằng URL cloud khi deploy
    private static final int DEFAULT_SERVER_PORT = 9000;
    
    // Timeout settings
    private static final int HEARTBEAT_INTERVAL_MS = 30000; // 30 giây
    private static final int CONNECTION_TIMEOUT_MS = 10000; // 10 giây (tăng cho Internet)
    private static final int READ_TIMEOUT_MS = 15000; // 15 giây
    
    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final PeerDiscovery peerDiscovery;
    
    private String serverHost;
    private int serverPort;
    
    private ScheduledExecutorService heartbeatExecutor;
    private ExecutorService workerExecutor;
    private volatile boolean connected = false;
    private volatile boolean running = false;
    
    // Listeners
    private final List<SignalingListener> listeners = new CopyOnWriteArrayList<>();
    
    /**
     * Interface callback cho sự kiện từ Signaling Server
     */
    public interface SignalingListener {
        void onConnected();
        void onDisconnected();
        void onPeerListUpdated(List<PeerInfo> peers);
        void onError(String message);
    }
    
    public SignalingClient(PeerInfo localPeer, SecurityManager securityManager, PeerDiscovery peerDiscovery) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.peerDiscovery = peerDiscovery;
        this.serverHost = DEFAULT_SERVER_HOST;
        this.serverPort = DEFAULT_SERVER_PORT;
    }
    
    /**
     * Cấu hình địa chỉ Signaling Server
     */
    public void setServerAddress(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
        System.out.println("📍 Đã cấu hình Signaling Server: " + host + ":" + port);
    }
    
    /**
     * Thêm listener
     */
    public void addListener(SignalingListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Xóa listener
     */
    public void removeListener(SignalingListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Kết nối và đăng ký với Signaling Server
     */
    public void connect() {
        if (running) {
            System.out.println("⚠ SignalingClient đã đang chạy");
            return;
        }
        
        running = true;
        workerExecutor = Executors.newCachedThreadPool();
        heartbeatExecutor = Executors.newScheduledThreadPool(1);
        
        System.out.println("🌐 Đang kết nối Signaling Server: " + serverHost + ":" + serverPort + "...");
        
        // Đăng ký với server trong thread riêng
        workerExecutor.submit(this::registerWithServer);
    }
    
    /**
     * Ngắt kết nối khỏi Signaling Server
     */
    public void disconnect() {
        if (!running) return;
        
        running = false;
        connected = false;
        
        // Gửi UNREGISTER trước khi tắt
        try {
            sendUnregister();
        } catch (Exception e) {
            // Bỏ qua lỗi khi unregister
        }
        
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
        
        System.out.println("✅ Đã ngắt kết nối Signaling Server");
        notifyDisconnected();
    }
    
    /**
     * Đăng ký peer với Signaling Server
     */
    private void registerWithServer() {
        try {
            SSLSocket socket = createSSLSocket();
            
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            
            // Gửi REGISTER message
            SignedMessage registerMsg = createSignedMessage("REGISTER", localPeer);
            oos.writeObject(registerMsg);
            oos.flush();
            
            // Nhận response
            Object response = ois.readObject();
            if (response instanceof SignedMessage) {
                SignedMessage respMsg = (SignedMessage) response;
                if ("ACK".equals(respMsg.getMessageType())) {
                    connected = true;
                    System.out.println("✅ Đã đăng ký thành công với Signaling Server");
                    notifyConnected();
                    
                    // Bắt đầu heartbeat
                    startHeartbeat();
                    
                    // Lấy danh sách peers
                    refreshPeerList();
                }
            }
            
            socket.close();
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi kết nối Signaling Server: " + e.getMessage());
            notifyError("Không thể kết nối Signaling Server: " + e.getMessage());
            connected = false;
        }
    }
    
    /**
     * Gửi UNREGISTER khi tắt
     */
    private void sendUnregister() {
        try {
            SSLSocket socket = createSSLSocket();
            
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            
            SignedMessage unregisterMsg = createSignedMessage("UNREGISTER", localPeer);
            oos.writeObject(unregisterMsg);
            oos.flush();
            
            socket.close();
            System.out.println("📤 Đã gửi UNREGISTER");
            
        } catch (Exception e) {
            // Bỏ qua lỗi
        }
    }
    
    /**
     * Bắt đầu gửi heartbeat định kỳ
     */
    private void startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running || !connected) return;
            
            try {
                sendHeartbeat();
            } catch (Exception e) {
                System.err.println("⚠ Lỗi gửi heartbeat: " + e.getMessage());
                connected = false;
                notifyDisconnected();
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Gửi heartbeat đến server
     */
    private void sendHeartbeat() throws Exception {
        SSLSocket socket = createSSLSocket();
        
        ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
        ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
        
        SignedMessage heartbeatMsg = createSignedMessage("HEARTBEAT", localPeer);
        oos.writeObject(heartbeatMsg);
        oos.flush();
        
        // Nhận ACK
        Object response = ois.readObject();
        if (response instanceof SignedMessage) {
            SignedMessage respMsg = (SignedMessage) response;
            if (!"ACK".equals(respMsg.getMessageType())) {
                throw new IOException("Heartbeat không được xác nhận");
            }
        }
        
        socket.close();
    }
    
    /**
     * Lấy danh sách peers từ server
     */
    public void refreshPeerList() {
        if (!connected) {
            System.out.println("⚠ Chưa kết nối Signaling Server");
            return;
        }
        
        workerExecutor.submit(() -> {
            try {
                SSLSocket socket = createSSLSocket();
                
                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
                
                // Gửi GET_PEERS request
                SignedMessage getPeersMsg = createSignedMessage("GET_PEERS", localPeer);
                oos.writeObject(getPeersMsg);
                oos.flush();
                
                // Nhận danh sách peers
                Object response = ois.readObject();
                if (response instanceof SignedMessage) {
                    SignedMessage respMsg = (SignedMessage) response;
                    if ("PEER_LIST".equals(respMsg.getMessageType())) {
                        @SuppressWarnings("unchecked")
                        List<PeerInfo> peers = (List<PeerInfo>) respMsg.getPayload();
                        
                        System.out.println("📋 Nhận được " + peers.size() + " peer(s) từ Signaling Server");
                        
                        // Thêm peers vào PeerDiscovery
                        for (PeerInfo peer : peers) {
                            if (!peer.getPeerId().equals(localPeer.getPeerId())) {
                                peerDiscovery.addInternetPeer(peer);
                            }
                        }
                        
                        notifyPeerListUpdated(peers);
                    }
                }
                
                socket.close();
                
            } catch (Exception e) {
                System.err.println("❌ Lỗi lấy danh sách peers: " + e.getMessage());
                notifyError("Lỗi lấy danh sách peers: " + e.getMessage());
            }
        });
    }
    
    /**
     * Gửi thông tin về file đang share (để người khác có thể tìm kiếm)
     */
    public void announceSharedFile(String fileName, String fileHash, long fileSize) {
        if (!connected) return;
        
        workerExecutor.submit(() -> {
            try {
                SSLSocket socket = createSSLSocket();
                
                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                
                // Tạo thông tin file share
                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("peerId", localPeer.getPeerId());
                fileInfo.put("fileName", fileName);
                fileInfo.put("fileHash", fileHash);
                fileInfo.put("fileSize", fileSize);
                
                SignedMessage shareMsg = createSignedMessage("SHARE_FILE", fileInfo);
                oos.writeObject(shareMsg);
                oos.flush();
                
                socket.close();
                System.out.println("📤 Đã thông báo file share: " + fileName);
                
            } catch (Exception e) {
                System.err.println("⚠ Lỗi thông báo file share: " + e.getMessage());
            }
        });
    }
    
    /**
     * Tạo SSLSocket kết nối đến server
     */
    private SSLSocket createSSLSocket() throws Exception {
        SSLSocket socket = securityManager.createSSLSocket(serverHost, serverPort);
        socket.connect(new InetSocketAddress(serverHost, serverPort), CONNECTION_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.startHandshake();
        return socket;
    }
    
    /**
     * Tạo signed message
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
    
    // ===== NOTIFY METHODS =====
    
    private void notifyConnected() {
        for (SignalingListener listener : listeners) {
            listener.onConnected();
        }
    }
    
    private void notifyDisconnected() {
        for (SignalingListener listener : listeners) {
            listener.onDisconnected();
        }
    }
    
    private void notifyPeerListUpdated(List<PeerInfo> peers) {
        for (SignalingListener listener : listeners) {
            listener.onPeerListUpdated(peers);
        }
    }
    
    private void notifyError(String message) {
        for (SignalingListener listener : listeners) {
            listener.onError(message);
        }
    }
    
    // ===== GETTERS =====
    
    public boolean isConnected() {
        return connected;
    }
    
    public boolean isRunning() {
        return running;
    }
    
    public String getServerHost() {
        return serverHost;
    }
    
    public int getServerPort() {
        return serverPort;
    }
}
