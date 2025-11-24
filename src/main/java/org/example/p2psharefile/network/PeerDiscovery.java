package org.example.p2psharefile.network;

import org.example.p2psharefile.model.PeerInfo;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Module 4: PeerDiscovery - Tự động phát hiện các peer trong mạng LAN bằng UDP
 *
 * FIX: Tách riêng start() và sendJoinAnnouncement() để tránh race condition
 */
public class PeerDiscovery {

    private static final int DISCOVERY_PORT = 8888;
    private static final int HEARTBEAT_INTERVAL = 5000;
    private static final int PEER_TIMEOUT = 15000;

    private final PeerInfo localPeer;
    private final Map<String, PeerInfo> discoveredPeers;
    private final List<PeerDiscoveryListener> listeners;

    private DatagramSocket socket;
    private ExecutorService executorService;
    private volatile boolean running = false;

    public interface PeerDiscoveryListener {
        void onPeerDiscovered(PeerInfo peer);
        void onPeerLost(PeerInfo peer);
    }

    public PeerDiscovery(PeerInfo localPeer) {
        this.localPeer = localPeer;
        this.discoveredPeers = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }

    /**
     * ⭐ FIX: start() CHỈ khởi động socket và threads, KHÔNG gửi JOIN
     * JOIN sẽ được gửi sau khi TẤT CẢ services đã sẵn sàng
     */
    public void start(boolean sendJoinImmediately) throws IOException {
        if (running) return;

        running = true;

        // Tạo UDP socket với SO_REUSEADDR
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(DISCOVERY_PORT));

        System.out.println("✓ Peer Discovery bind vào 0.0.0.0:" + DISCOVERY_PORT);
        System.out.println("  → Local Peer: " + localPeer.getDisplayName() + " (" + localPeer.getIpAddress() + ":" + localPeer.getPort() + ")");

        executorService = Executors.newFixedThreadPool(3);

        // Thread 1: Lắng nghe broadcast
        executorService.submit(this::listenForPeers);

        // Thread 2: Heartbeat (CHỜ 2 giây trước khi bắt đầu để JOIN được gửi trước)
        executorService.submit(() -> {
            try {
                Thread.sleep(2000); // Đợi JOIN được gửi trước
            } catch (InterruptedException e) {
                return;
            }
            broadcastHeartbeat();
        });

        // Thread 3: Kiểm tra timeout
        executorService.submit(this::checkPeerTimeout);

        System.out.println("✓ Peer Discovery đã khởi động (chưa gửi JOIN)");
    }

    /**
     * ⭐ FIX: Gửi JOIN announcement SAU KHI tất cả services đã start
     * Phải gọi method này CUỐI CÙNG sau khi FileSearchService và FileTransferService đã sẵn sàng
     */
    public void sendJoinAnnouncement() {
        executorService.submit(() -> {
            try {
                // Chờ 500ms để chắc chắn tất cả services đã bind port
                Thread.sleep(500);

                System.out.println("\n🚀 ========== GỬI JOIN ANNOUNCEMENT ==========");
                System.out.println("   Peer: " + localPeer.getDisplayName());
                System.out.println("   IP: " + localPeer.getIpAddress());
                System.out.println("   Port: " + localPeer.getPort());

                // ⭐ GỬI 3 LẦN với delay để đảm bảo peer khác nhận được
                for (int i = 1; i <= 3; i++) {
                    sendBroadcast("join-" + i);
                    System.out.println("📢 Gửi JOIN lần " + i + "/3");

                    if (i < 3) {
                        Thread.sleep(1000); // Delay 1 giây giữa các lần
                    }
                }

                System.out.println("✅ Đã gửi xong JOIN announcement");
                System.out.println("==============================================\n");

            } catch (Exception e) {
                System.err.println("❌ Lỗi gửi JOIN: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public void stop() {
        running = false;

        // Gửi LEAVE announcement
        try {
            System.out.println("👋 Gửi LEAVE announcement...");
            sendBroadcast("leave");
        } catch (Exception e) {
            System.err.println("Lỗi gửi LEAVE: " + e.getMessage());
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        if (executorService != null) {
            executorService.shutdownNow();
        }

        System.out.println("✓ Peer Discovery đã dừng");
    }

    /**
     * ⭐ FIX: Cải thiện listenForPeers để xử lý chính xác hơn
     */
    private void listenForPeers() {
        byte[] buffer = new byte[2048]; // Tăng buffer size

        System.out.println("👂 Bắt đầu lắng nghe broadcast trên port " + DISCOVERY_PORT);

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String senderIP = packet.getAddress().getHostAddress();

                // Deserialize peer info
                ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                ObjectInputStream ois = new ObjectInputStream(bais);
                PeerInfo receivedPeer = (PeerInfo) ois.readObject();

                // ⭐ FIX: KIỂM TRA CHÍNH MÌNH TRƯỚC KHI XỬ LÝ
                if (receivedPeer.getPeerId().equals(localPeer.getPeerId())) {
                    // Không log để giảm spam
                    continue;
                }

                // Clone peer với IP thực từ UDP header
                PeerInfo clonedPeer = new PeerInfo(
                        receivedPeer.getPeerId(),
                        senderIP,
                        receivedPeer.getPort(),
                        receivedPeer.getDisplayName()
                );

                // ⭐ FIX: KIỂM TRA SUBNET
                if (!isSameSubnet(localPeer.getIpAddress(), clonedPeer.getIpAddress())) {
                    System.out.println("⏭ Bỏ qua peer khác subnet: " + clonedPeer.getIpAddress());
                    continue;
                }

                // Log nhận được broadcast
                System.out.println("📩 Nhận broadcast từ: " + clonedPeer.getDisplayName() +
                        " (" + clonedPeer.getIpAddress() + ":" + clonedPeer.getPort() + ")");

                handleDiscoveredPeer(clonedPeer);

            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    System.err.println("⚠ Lỗi nhận broadcast: " + e.getMessage());
                }
            }
        }
    }

    /**
     * ⭐ FIX: Tính broadcast address chính xác
     */
    private InetAddress getCorrectBroadcastAddress() throws Exception {
        String localIP = localPeer.getIpAddress();
        String[] parts = localIP.split("\\.");

        if (parts.length == 4) {
            // Class C: x.x.x.255
            String broadcastIP = parts[0] + "." + parts[1] + "." + parts[2] + ".255";
            return InetAddress.getByName(broadcastIP);
        }

        // Fallback
        return InetAddress.getByName("255.255.255.255");
    }

    /**
     * ⭐ FIX: Gửi broadcast với logging rõ ràng
     */
    private void sendBroadcast(String context) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(localPeer);
            oos.flush();

            byte[] data = baos.toByteArray();
            InetAddress broadcastAddress = getCorrectBroadcastAddress();

            DatagramPacket packet = new DatagramPacket(
                    data, data.length, broadcastAddress, DISCOVERY_PORT
            );

            socket.send(packet);

            if (context.startsWith("join")) {
                System.out.println("📢 Broadcast " + context + " → " +
                        broadcastAddress.getHostAddress() + ":" + DISCOVERY_PORT +
                        " (" + data.length + " bytes)");
            }

        } catch (Exception e) {
            System.err.println("❌ Lỗi gửi broadcast: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Heartbeat thread
     */
    private void broadcastHeartbeat() {
        int count = 0;
        while (running) {
            try {
                sendBroadcast("heartbeat");
                count++;

                // Log mỗi 30 giây
                if (count % 6 == 1) {
                    System.out.println("💓 Heartbeat #" + count + " | Online peers: " + discoveredPeers.size());
                }

                Thread.sleep(HEARTBEAT_INTERVAL);

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running) {
                    System.err.println("❌ Lỗi heartbeat: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Timeout checker
     */
    private void checkPeerTimeout() {
        while (running) {
            try {
                long currentTime = System.currentTimeMillis();
                List<PeerInfo> timeoutPeers = new ArrayList<>();

                for (PeerInfo peer : discoveredPeers.values()) {
                    if (currentTime - peer.getLastSeen() > PEER_TIMEOUT) {
                        timeoutPeers.add(peer);
                    }
                }

                for (PeerInfo peer : timeoutPeers) {
                    discoveredPeers.remove(peer.getPeerId());
                    notifyPeerLost(peer);
                }

                Thread.sleep(5000);

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * ⭐ FIX: Xử lý peer với logging rõ ràng
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

    public List<PeerInfo> getDiscoveredPeers() {
        return new ArrayList<>(discoveredPeers.values());
    }

    public int getPeerCount() {
        return discoveredPeers.size();
    }

    public PeerInfo getPeerById(String peerId) {
        return discoveredPeers.get(peerId);
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
            boolean classC = parts1[0].equals(parts2[0]) &&
                    parts1[1].equals(parts2[1]) &&
                    parts1[2].equals(parts2[2]);

            // Class B (/16)
            boolean classB = parts1[0].equals(parts2[0]) &&
                    parts1[1].equals(parts2[1]);

            return classC || classB;

        } catch (Exception e) {
            return false;
        }
    }
}