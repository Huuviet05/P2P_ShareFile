package org.example.p2psharefile.network;

import org.example.p2psharefile.model.PeerInfo;
import org.example.p2psharefile.model.SignedMessage;
import org.example.p2psharefile.security.SecurityManager;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;

/**
 * PeerDiscovery với TLS + Peer Authentication
 * 
 * Cơ chế hoạt động (với TLS + Signatures):
 * 1. Mỗi peer mở SSLServerSocket để lắng nghe kết nối
 * 2. Peer quét dải IP trong subnet để tìm peer khác (dùng SSLSocket)
 * 3. Khi tìm thấy peer, thiết lập TLS connection và trao đổi thông tin
 * 4. JOIN/HEARTBEAT messages được ký bằng ECDSA private key
 * 5. Peer nhận message verify signature bằng public key từ PeerInfo
 * 6. Duy trì kết nối với heartbeat để kiểm tra peer còn online
 * 
 * Security improvements:
 * - TLS encryption cho tất cả communications
 * - ECDSA signatures chống message forgery/impersonation
 * - Public key distribution qua PeerInfo
 */
public class PeerDiscovery {

    private static final int DISCOVERY_PORT = 8888;
    private static final int HEARTBEAT_INTERVAL = 5000; // 5 giây
    private static final int PEER_TIMEOUT = 15000; // 15 giây
    private static final int SCAN_INTERVAL = 10000; // 10 giây quét lại
    private static final int CONNECTION_TIMEOUT = 2000; // 2 giây timeout kết nối

    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final Map<String, PeerInfo> discoveredPeers;
    private final Map<String, Socket> peerConnections; // Kết nối TCP với peer
    private final List<PeerDiscoveryListener> listeners;

    private SSLServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;

    public interface PeerDiscoveryListener {
        void onPeerDiscovered(PeerInfo peer);
        void onPeerLost(PeerInfo peer);
    }

    public PeerDiscovery(PeerInfo localPeer, SecurityManager securityManager) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.discoveredPeers = new ConcurrentHashMap<>();
        this.peerConnections = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Khởi động dịch vụ discovery
     */
    public void start(boolean scanImmediately) throws IOException {
        if (running) return;

        running = true;

        // Tạo SSLServerSocket để lắng nghe kết nối từ peer khác (TLS enabled)
        serverSocket = securityManager.createSSLServerSocket(DISCOVERY_PORT);
        serverSocket.setReuseAddress(true);

        System.out.println("✓ Peer Discovery TLS đã khởi động trên port " + DISCOVERY_PORT);
        System.out.println("  → Local Peer: " + localPeer.getDisplayName() +
                " (" + localPeer.getIpAddress() + ":" + localPeer.getPort() + ")");
        System.out.println("  → Public Key: " + localPeer.getPublicKey().substring(0, 40) + "...");

        executorService = Executors.newCachedThreadPool();

        // Thread 1: Lắng nghe kết nối từ peer khác
        executorService.submit(this::acceptPeerConnections);

        // Thread 2: Quét mạng tìm peer
        if (scanImmediately) {
            executorService.submit(this::scanNetwork);
        }

        // Thread 3: Heartbeat và kiểm tra timeout
        executorService.submit(this::heartbeatAndTimeoutChecker);

        System.out.println("✓ Peer Discovery đã sẵn sàng");
    }

    /**
     * Gửi announcement sau khi tất cả service đã sẵn sàng
     */
    public void sendJoinAnnouncement() {
        executorService.submit(() -> {
            try {
                Thread.sleep(500); // Đợi services sẵn sàng

                System.out.println("\n🚀 ========== BẮT ĐẦU QUÉT MẠNG ==========");
                System.out.println("   Peer: " + localPeer.getDisplayName());
                System.out.println("   IP: " + localPeer.getIpAddress());
                System.out.println("   Port: " + localPeer.getPort());

                scanNetwork();

                System.out.println("✅ Đã hoàn thành quét mạng");
                System.out.println("==========================================\n");

            } catch (Exception e) {
                System.err.println("❌ Lỗi quét mạng: " + e.getMessage());
            }
        });
    }

    public void stop() {
        running = false;

        // Đóng tất cả kết nối peer
        for (Socket socket : peerConnections.values()) {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        peerConnections.clear();

        // Đóng server socket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Lỗi đóng server socket: " + e.getMessage());
        }

        if (executorService != null) {
            executorService.shutdownNow();
        }

        System.out.println("✓ Peer Discovery đã dừng");
    }

    /**
     * Lắng nghe kết nối từ peer khác
     */
    private void acceptPeerConnections() {
        System.out.println("👂 Đang lắng nghe kết nối peer trên port " + DISCOVERY_PORT);

        while (running) {
            try {
                // Accept SSL connection
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                clientSocket.setSoTimeout(5000);
                
                // Start SSL handshake
                clientSocket.startHandshake();

                // Xử lý kết nối trong thread riêng
                executorService.submit(() -> handlePeerConnection(clientSocket));

            } catch (SocketException e) {
                if (running) {
                    System.err.println("Socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("Lỗi accept connection: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Xử lý kết nối từ peer (với TLS + signature verification)
     */
    private void handlePeerConnection(SSLSocket socket) {
        try {
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());

            // Nhận SignedMessage
            SignedMessage signedMsg = (SignedMessage) ois.readObject();
            String messageType = signedMsg.getMessageType();
            PeerInfo remotePeer = (PeerInfo) signedMsg.getPayload();

            // Kiểm tra không phải chính mình
            if (remotePeer.getPeerId().equals(localPeer.getPeerId())) {
                socket.close();
                return;
            }

            // Cập nhật IP từ socket
            String realIP = socket.getInetAddress().getHostAddress();
            remotePeer.setIpAddress(realIP);

            // Kiểm tra cùng subnet
            if (!isSameSubnet(localPeer.getIpAddress(), realIP)) {
                System.out.println("⏭ Bỏ qua peer khác subnet: " + realIP);
                socket.close();
                return;
            }

            // ✅ VERIFY SIGNATURE
            if (!verifyPeerSignature(signedMsg, remotePeer)) {
                System.err.println("❌ [Security] Invalid signature from peer: " + remotePeer.getDisplayName());
                socket.close();
                return;
            }
            
            System.out.println("✅ [Security] Signature verified for peer: " + remotePeer.getDisplayName());

            if ("JOIN".equals(messageType) || "HEARTBEAT".equals(messageType)) {
                // Tạo signed response
                SignedMessage response = createSignedMessage("ACK", localPeer);
                
                // Gửi response với thông tin của mình
                oos.writeObject(response);
                oos.flush();

                System.out.println("📩 Nhận " + messageType + " từ: " + remotePeer.getDisplayName() +
                        " (" + realIP + ":" + remotePeer.getPort() + ")");

                handleDiscoveredPeer(remotePeer);
            }

            socket.close();

        } catch (Exception e) {
            if (running) {
                System.err.println("Lỗi xử lý peer connection: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Quét mạng LAN để tìm peer
     */
    private void scanNetwork() {
        while (running) {
            try {
                String baseIP = getBaseIP(localPeer.getIpAddress());
                System.out.println("🔍 Quét mạng: " + baseIP + ".*");

                List<Future<?>> scanTasks = new ArrayList<>();

                // Quét dải IP từ 1-254
                for (int i = 1; i <= 254; i++) {
                    final String targetIP = baseIP + "." + i;

                    // Bỏ qua IP của chính mình
                    if (targetIP.equals(localPeer.getIpAddress())) {
                        continue;
                    }

                    // Quét từng IP trong thread riêng
                    Future<?> task = executorService.submit(() -> tryConnectToPeer(targetIP));
                    scanTasks.add(task);
                }

                // Đợi tất cả scan task hoàn thành
                for (Future<?> task : scanTasks) {
                    try {
                        task.get(CONNECTION_TIMEOUT + 1000, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        // Timeout hoặc lỗi - bỏ qua
                    }
                }

                System.out.println("✓ Hoàn thành quét mạng. Tìm thấy " + discoveredPeers.size() + " peer(s)");

                // Đợi trước khi quét lại
                Thread.sleep(SCAN_INTERVAL);

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                System.err.println("Lỗi quét mạng: " + e.getMessage());
            }
        }
    }

    /**
     * Thử kết nối đến peer tại IP cụ thể (với TLS + signatures)
     */
    private void tryConnectToPeer(String targetIP) {
        SSLSocket socket = null;
        try {
            // Tạo SSLSocket và kết nối với timeout
            socket = securityManager.createSSLSocket(targetIP, DISCOVERY_PORT);
            socket.connect(new InetSocketAddress(targetIP, DISCOVERY_PORT), CONNECTION_TIMEOUT);
            socket.setSoTimeout(5000);
            
            // Start SSL handshake
            socket.startHandshake();

            // Tạo signed JOIN message
            SignedMessage joinMsg = createSignedMessage("JOIN", localPeer);
            
            // Gửi signed JOIN message
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.writeObject(joinMsg);
            oos.flush();

            // Nhận signed response
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            SignedMessage response = (SignedMessage) ois.readObject();

            if ("ACK".equals(response.getMessageType())) {
                PeerInfo remotePeer = (PeerInfo) response.getPayload();
                remotePeer.setIpAddress(targetIP);

                // Verify signature
                if (!verifyPeerSignature(response, remotePeer)) {
                    System.err.println("❌ [Security] Invalid signature from peer: " + targetIP);
                    return;
                }

                handleDiscoveredPeer(remotePeer);
            }

        } catch (IOException | ClassNotFoundException e) {
            // Không có peer tại IP này - bỏ qua
        } catch (Exception e) {
            System.err.println("❌ Lỗi kết nối đến " + targetIP + ": " + e.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Heartbeat và kiểm tra timeout
     */
    private void heartbeatAndTimeoutChecker() {
        int count = 0;

        while (running) {
            try {
                count++;
                long currentTime = System.currentTimeMillis();

                // Gửi heartbeat đến các peer đã biết
                for (PeerInfo peer : new ArrayList<>(discoveredPeers.values())) {
                    executorService.submit(() -> sendHeartbeat(peer));

                    // Kiểm tra timeout
                    if (currentTime - peer.getLastSeen() > PEER_TIMEOUT) {
                        discoveredPeers.remove(peer.getPeerId());
                        notifyPeerLost(peer);
                    }
                }

                // Log mỗi 30 giây
                if (count % 6 == 1) {
                    System.out.println("💓 Heartbeat #" + count + " | Online peers: " + discoveredPeers.size());
                }

                Thread.sleep(HEARTBEAT_INTERVAL);

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    System.err.println("Lỗi heartbeat: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Gửi heartbeat đến peer (với TLS + signature)
     */
    private void sendHeartbeat(PeerInfo peer) {
        SSLSocket socket = null;
        try {
            socket = securityManager.createSSLSocket(peer.getIpAddress(), DISCOVERY_PORT);
            socket.connect(new InetSocketAddress(peer.getIpAddress(), DISCOVERY_PORT), CONNECTION_TIMEOUT);
            socket.setSoTimeout(3000);
            socket.startHandshake();

            // Tạo signed HEARTBEAT message
            SignedMessage heartbeatMsg = createSignedMessage("HEARTBEAT", localPeer);
            
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.writeObject(heartbeatMsg);
            oos.flush();

            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            SignedMessage response = (SignedMessage) ois.readObject();

            if ("ACK".equals(response.getMessageType())) {
                // Verify signature
                if (verifyPeerSignature(response, peer)) {
                    peer.updateLastSeen();
                }
            }

        } catch (IOException | ClassNotFoundException e) {
            // Peer không phản hồi - sẽ bị timeout sau
        } catch (Exception e) {
            System.err.println("❌ Lỗi heartbeat: " + e.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Xử lý peer mới phát hiện
     */
    private void handleDiscoveredPeer(PeerInfo peer) {
        boolean isNewPeer = !discoveredPeers.containsKey(peer.getPeerId());

        peer.updateLastSeen();
        discoveredPeers.put(peer.getPeerId(), peer);

        if (isNewPeer) {
            System.out.println("\n✅ ========== PEER MỚI ==========");
            System.out.println("   Name: " + peer.getDisplayName());
            System.out.println("   IP: " + peer.getIpAddress());
            System.out.println("   Port: " + peer.getPort());
            System.out.println("   ID: " + peer.getPeerId());
            System.out.println("   Total peers: " + discoveredPeers.size());
            System.out.println("==================================\n");

            notifyPeerDiscovered(peer);
        }
    }

    /**
     * Lấy base IP (3 octet đầu) từ IP address
     */
    private String getBaseIP(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }
        return "192.168.1"; // Fallback
    }

    /**
     * Kiểm tra cùng subnet
     */
    private boolean isSameSubnet(String ip1, String ip2) {
        try {
            String[] parts1 = ip1.split("\\.");
            String[] parts2 = ip2.split("\\.");

            if (parts1.length != 4 || parts2.length != 4) {
                return false;
            }

            // Class C (/24)
            return parts1[0].equals(parts2[0]) &&
                    parts1[1].equals(parts2[1]) &&
                    parts1[2].equals(parts2[2]);

        } catch (Exception e) {
            return false;
        }
    }
    
    // ========== Security Helper Methods ==========
    
    /**
     * Tạo signed message
     */
    private SignedMessage createSignedMessage(String messageType, PeerInfo payload) throws Exception {
        String message = messageType + ":" + localPeer.getPeerId() + ":" + payload.toString();
        String signature = securityManager.signMessage(message);
        return new SignedMessage(messageType, localPeer.getPeerId(), signature, payload);
    }
    
    /**
     * Verify signature của message từ peer
     */
    private boolean verifyPeerSignature(SignedMessage signedMsg, PeerInfo peer) {
        try {
            // Lấy public key từ peer
            if (peer.getPublicKey() == null) {
                System.err.println("❌ [Security] Peer has no public key: " + peer.getDisplayName());
                return false;
            }
            
            PublicKey publicKey = SecurityManager.decodePublicKey(peer.getPublicKey());
            
            // Tạo message cần verify
            String message = signedMsg.getMessageType() + ":" + signedMsg.getSenderId() + ":" + 
                           signedMsg.getPayload().toString();
            
            // Verify
            boolean valid = securityManager.verifySignature(message, signedMsg.getSignature(), publicKey);
            
            if (valid) {
                // Thêm vào trusted peers
                securityManager.addTrustedPeerKey(peer.getPeerId(), publicKey);
            }
            
            return valid;
            
        } catch (Exception e) {
            System.err.println("❌ [Security] Error verifying signature: " + e.getMessage());
            return false;
        }
    }

    // Listener methods
    public void addListener(PeerDiscoveryListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PeerDiscoveryListener listener) {
        listeners.remove(listener);
    }

    private void notifyPeerDiscovered(PeerInfo peer) {
        for (PeerDiscoveryListener listener : listeners) {
            try {
                listener.onPeerDiscovered(peer);
            } catch (Exception e) {
                System.err.println("Lỗi listener: " + e.getMessage());
            }
        }
    }

    private void notifyPeerLost(PeerInfo peer) {
        System.out.println("❌ Peer offline: " + peer.getDisplayName() + " (" + peer.getIpAddress() + ")");
        for (PeerDiscoveryListener listener : listeners) {
            try {
                listener.onPeerLost(peer);
            } catch (Exception e) {
                System.err.println("Lỗi listener: " + e.getMessage());
            }
        }
    }

    // Getter methods
    public List<PeerInfo> getDiscoveredPeers() {
        return new ArrayList<>(discoveredPeers.values());
    }

    public int getPeerCount() {
        return discoveredPeers.size();
    }

    public PeerInfo getPeerById(String peerId) {
        return discoveredPeers.get(peerId);
    }
}