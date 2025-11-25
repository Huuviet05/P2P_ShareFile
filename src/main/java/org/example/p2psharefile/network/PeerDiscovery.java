package org.example.p2psharefile.network;

import org.example.p2psharefile.model.PeerInfo;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * PeerDiscovery với TCP Socket - Phát hiện peer qua kết nối trực tiếp
 *
 * Cơ chế hoạt động:
 * 1. Mỗi peer mở ServerSocket để lắng nghe kết nối
 * 2. Peer quét dải IP trong subnet để tìm peer khác
 * 3. Khi tìm thấy peer, thiết lập kết nối TCP và trao đổi thông tin
 * 4. Duy trì kết nối với heartbeat để kiểm tra peer còn online
 */
public class PeerDiscovery {

    private static final int DISCOVERY_PORT = 8888;
    private static final int HEARTBEAT_INTERVAL = 5000; // 5 giây
    private static final int PEER_TIMEOUT = 15000; // 15 giây
    private static final int SCAN_INTERVAL = 10000; // 10 giây quét lại
    private static final int CONNECTION_TIMEOUT = 2000; // 2 giây timeout kết nối

    private final PeerInfo localPeer;
    private final Map<String, PeerInfo> discoveredPeers;
    private final Map<String, Socket> peerConnections; // Kết nối TCP với peer
    private final List<PeerDiscoveryListener> listeners;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;

    public interface PeerDiscoveryListener {
        void onPeerDiscovered(PeerInfo peer);
        void onPeerLost(PeerInfo peer);
    }

    public PeerDiscovery(PeerInfo localPeer) {
        this.localPeer = localPeer;
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

        // Tạo ServerSocket để lắng nghe kết nối từ peer khác
        serverSocket = new ServerSocket(DISCOVERY_PORT);
        serverSocket.setReuseAddress(true);

        System.out.println("✓ Peer Discovery TCP đã khởi động trên port " + DISCOVERY_PORT);
        System.out.println("  → Local Peer: " + localPeer.getDisplayName() +
                " (" + localPeer.getIpAddress() + ":" + localPeer.getPort() + ")");

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
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(5000);

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
     * Xử lý kết nối từ peer
     */
    private void handlePeerConnection(Socket socket) {
        try {
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());

            // Nhận thông tin peer
            String messageType = ois.readUTF();
            PeerInfo remotePeer = (PeerInfo) ois.readObject();

            // Kiểm tra không phải chính mình
            if (remotePeer.getPeerId().equals(localPeer.getPeerId())) {
                socket.close();
                return;
            }

            // Cập nhật IP từ socket
            String realIP = socket.getInetAddress().getHostAddress();
            remotePeer = new PeerInfo(
                    remotePeer.getPeerId(),
                    realIP,
                    remotePeer.getPort(),
                    remotePeer.getDisplayName()
            );

            // Kiểm tra cùng subnet
            if (!isSameSubnet(localPeer.getIpAddress(), realIP)) {
                System.out.println("⏭ Bỏ qua peer khác subnet: " + realIP);
                socket.close();
                return;
            }

            if ("JOIN".equals(messageType) || "HEARTBEAT".equals(messageType)) {
                // Gửi response với thông tin của mình
                oos.writeUTF("ACK");
                oos.writeObject(localPeer);
                oos.flush();

                System.out.println("📩 Nhận " + messageType + " từ: " + remotePeer.getDisplayName() +
                        " (" + realIP + ":" + remotePeer.getPort() + ")");

                handleDiscoveredPeer(remotePeer);
            }

            socket.close();

        } catch (Exception e) {
            if (running) {
                System.err.println("Lỗi xử lý peer connection: " + e.getMessage());
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
     * Thử kết nối đến peer tại IP cụ thể
     */
    private void tryConnectToPeer(String targetIP) {
        Socket socket = null;
        try {
            // Thử kết nối với timeout ngắn
            socket = new Socket();
            socket.connect(new InetSocketAddress(targetIP, DISCOVERY_PORT), CONNECTION_TIMEOUT);
            socket.setSoTimeout(5000);

            // Gửi JOIN message
            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.writeUTF("JOIN");
            oos.writeObject(localPeer);
            oos.flush();

            // Nhận response
            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            String response = ois.readUTF();

            if ("ACK".equals(response)) {
                PeerInfo remotePeer = (PeerInfo) ois.readObject();

                // Cập nhật IP thực
                remotePeer = new PeerInfo(
                        remotePeer.getPeerId(),
                        targetIP,
                        remotePeer.getPort(),
                        remotePeer.getDisplayName()
                );

                handleDiscoveredPeer(remotePeer);
            }

        } catch (IOException | ClassNotFoundException e) {
            // Không có peer tại IP này - bỏ qua
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
     * Gửi heartbeat đến peer
     */
    private void sendHeartbeat(PeerInfo peer) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(peer.getIpAddress(), DISCOVERY_PORT), CONNECTION_TIMEOUT);
            socket.setSoTimeout(3000);

            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            oos.writeUTF("HEARTBEAT");
            oos.writeObject(localPeer);
            oos.flush();

            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            String response = ois.readUTF();

            if ("ACK".equals(response)) {
                peer.updateLastSeen();
            }

        } catch (IOException e) {
            // Peer không phản hồi - sẽ bị timeout sau
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