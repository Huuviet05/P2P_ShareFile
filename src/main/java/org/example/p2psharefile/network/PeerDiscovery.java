package org.example.p2psharefile.network;

import org.example.p2psharefile.model.PeerInfo;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Module 4: PeerDiscovery - Tự động phát hiện các peer trong mạng LAN bằng UDP
 * 
 * Cách hoạt động:
 * 1. Mỗi peer broadcast (phát sóng) thông tin của mình qua UDP
 * 2. Các peer khác lắng nghe và ghi nhận peer mới
 * 3. Định kỳ gửi "heartbeat" để peer biết mình còn online
 * 4. Nếu lâu không nhận heartbeat → coi như peer đã offline
 * 
 * UDP vs TCP:
 * - UDP: Không cần kết nối, nhanh, phù hợp cho broadcast
 * - TCP: Cần kết nối, tin cậy, dùng cho truyền file
 */
public class PeerDiscovery {
    
    private static final int DISCOVERY_PORT = 8888;      // Port UDP cố định cho P2P thuần
    private static final int HEARTBEAT_INTERVAL = 5000;  // Gửi heartbeat mỗi 5 giây
    private static final int PEER_TIMEOUT = 15000;       // Peer timeout sau 15 giây
    
    private final PeerInfo localPeer;                    // Thông tin peer của mình
    private final Map<String, PeerInfo> discoveredPeers; // Danh sách peer đã phát hiện
    private final List<PeerDiscoveryListener> listeners; // Các listener để thông báo
    
    private DatagramSocket socket;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    /**
     * Interface để nhận thông báo khi có peer mới/mất
     */
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
     * Bắt đầu dịch vụ peer discovery
     */
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        
        // Tạo UDP socket với SO_REUSEADDR
        // Cho phép nhiều clients cùng bind port 8888
        socket = new DatagramSocket(null); // null = không bind ngay
        socket.setReuseAddress(true);      // ⭐ Cho phép share port
        socket.setBroadcast(true);
        
        // QUAN TRỌNG: Bind vào 0.0.0.0 (tất cả interface) để nhận broadcast
        // Nếu bind vào IP cụ thể sẽ KHÔNG nhận được broadcast!
        socket.bind(new InetSocketAddress(DISCOVERY_PORT));
        
        System.out.println("✓ Peer Discovery bind vào 0.0.0.0:" + DISCOVERY_PORT + " (listening all interfaces)");
        System.out.println("  → Local Peer IP: " + localPeer.getIpAddress());
        
        executorService = Executors.newFixedThreadPool(3);
        
        // Thread 1: Lắng nghe broadcast từ peer khác
        executorService.submit(this::listenForPeers);
        
        // Thread 2: Định kỳ broadcast thông tin của mình
        executorService.submit(this::broadcastHeartbeat);
        
        // Thread 3: Kiểm tra peer timeout
        executorService.submit(this::checkPeerTimeout);
        
        System.out.println("✓ Peer Discovery đã khởi động trên UDP port " + DISCOVERY_PORT);
    }
    
    /**
     * Dừng dịch vụ peer discovery
     */
    public void stop() {
        running = false;
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        System.out.println("✓ Peer Discovery đã dừng");
    }
    
    /**
     * Thread lắng nghe UDP broadcast từ các peer khác
     */
    private void listenForPeers() {
        byte[] buffer = new byte[1024];
        
        System.out.println("👂 Bắt đầu lắng nghe broadcast trên port " + DISCOVERY_PORT);
        
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // Block và chờ packet
                
                String senderIP = packet.getAddress().getHostAddress();
                System.out.println("📥 Nhận broadcast từ: " + senderIP + ":" + packet.getPort() + 
                                 " (" + packet.getLength() + " bytes)");
                
                // Deserialize peer info từ packet (chỉ đọc tới độ dài thật của gói tin)
                ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                ObjectInputStream ois = new ObjectInputStream(bais);
                PeerInfo receivedPeer = (PeerInfo) ois.readObject();

                // Dùng IP thực tế lấy từ header UDP để tránh trường hợp peer broadcast với IP 127.0.0.1
                // (ví dụ máy chọn sai interface). Điều này giúp peer cũ nhận đúng IP của peer mới.
                if (!senderIP.equals(receivedPeer.getIpAddress())) {
                    System.out.println("   ⚠ IP thực tế " + senderIP + " khác IP khai báo " + receivedPeer.getIpAddress() +
                                       ", dùng IP thực tế để kết nối");
                    receivedPeer.setIpAddress(senderIP);
                }

                System.out.println("   → Peer: " + receivedPeer.getDisplayName() + " (" + receivedPeer.getIpAddress() + ")");
                
                // FILTER 1: Không thêm chính mình vào danh sách
                if (receivedPeer.getPeerId().equals(localPeer.getPeerId())) {
                    System.out.println("   ⏭ Bỏ qua broadcast từ chính mình (same PeerID)");
                    continue;
                }
                
                // FILTER 2: Chỉ chấp nhận peer từ cùng subnet (10.50.x.x hoặc 192.168.x.x)
                if (!isSameSubnet(localPeer.getIpAddress(), receivedPeer.getIpAddress())) {
                    System.out.println("   ⏭ Bỏ qua peer từ subnet khác: " + receivedPeer.getIpAddress());
                    continue;
                }
                
                System.out.println("   ✓ Peer hợp lệ từ cùng subnet: " + receivedPeer.getDisplayName());
                handleDiscoveredPeer(receivedPeer);
                
            } catch (SocketException e) {
                // Socket đã đóng, thoát loop
                break;
            } catch (Exception e) {
                if (running) {
                    System.err.println("Lỗi khi nhận peer discovery: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Thread định kỳ broadcast thông tin của mình
     */
    private void broadcastHeartbeat() {
        while (running) {
            try {
                // Serialize peer info thành byte array
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos);
                oos.writeObject(localPeer);
                oos.flush();
                
                byte[] data = baos.toByteArray();
                
                // Broadcast đến địa chỉ 255.255.255.255
                InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
                DatagramPacket packet = new DatagramPacket(
                    data, data.length, broadcastAddress, DISCOVERY_PORT
                );
                
                socket.send(packet);
                System.out.println("📡 Broadcast heartbeat: " + localPeer.getDisplayName() + 
                                 " (" + localPeer.getIpAddress() + ") - " + data.length + " bytes");
                
                // Chờ HEARTBEAT_INTERVAL trước khi gửi lại
                Thread.sleep(HEARTBEAT_INTERVAL);
                
            } catch (Exception e) {
                if (running) {
                    System.err.println("❌ Lỗi khi broadcast heartbeat: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }
    
    /**
     * Thread kiểm tra peer timeout
     */
    private void checkPeerTimeout() {
        while (running) {
            try {
                long currentTime = System.currentTimeMillis();
                List<PeerInfo> timeoutPeers = new ArrayList<>();
                
                // Tìm các peer đã timeout
                for (PeerInfo peer : discoveredPeers.values()) {
                    if (currentTime - peer.getLastSeen() > PEER_TIMEOUT) {
                        timeoutPeers.add(peer);
                    }
                }
                
                // Xóa peer timeout và thông báo
                for (PeerInfo peer : timeoutPeers) {
                    discoveredPeers.remove(peer.getPeerId());
                    notifyPeerLost(peer);
                }
                
                Thread.sleep(5000); // Check mỗi 5 giây
                
            } catch (InterruptedException e) {
                break;
            }
        }
    }
    
    /**
     * Xử lý khi phát hiện peer mới hoặc nhận heartbeat
     */
    private void handleDiscoveredPeer(PeerInfo peer) {
        boolean isNewPeer = !discoveredPeers.containsKey(peer.getPeerId());
        
        peer.updateLastSeen();
        discoveredPeers.put(peer.getPeerId(), peer);
        
        if (isNewPeer) {
            System.out.println("✓ Phát hiện peer mới: " + peer);
            notifyPeerDiscovered(peer);
        }
    }
    
    /**
     * Thêm listener
     */
    public void addListener(PeerDiscoveryListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Xóa listener
     */
    public void removeListener(PeerDiscoveryListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Thông báo peer mới cho các listener
     */
    private void notifyPeerDiscovered(PeerInfo peer) {
        for (PeerDiscoveryListener listener : listeners) {
            try {
                listener.onPeerDiscovered(peer);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Thông báo peer mất cho các listener
     */
    private void notifyPeerLost(PeerInfo peer) {
        System.out.println("✗ Peer đã offline: " + peer);
        for (PeerDiscoveryListener listener : listeners) {
            try {
                listener.onPeerLost(peer);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
    
    /**
     * Lấy danh sách tất cả peer đã phát hiện
     */
    public List<PeerInfo> getDiscoveredPeers() {
        return new ArrayList<>(discoveredPeers.values());
    }
    
    /**
     * Lấy số lượng peer online
     */
    public int getPeerCount() {
        return discoveredPeers.size();
    }
    
    /**
     * Tìm peer theo ID
     */
    public PeerInfo getPeerById(String peerId) {
        return discoveredPeers.get(peerId);
    }
    
    /**
     * Kiểm tra 2 IP có cùng subnet không (so sánh 3 octet đầu của class C)
     * Ví dụ: 10.50.61.204 và 10.50.61.252 → cùng subnet
     *        10.50.61.204 và 192.168.56.1 → khác subnet
     */
    private boolean isSameSubnet(String ip1, String ip2) {
        try {
            String[] parts1 = ip1.split("\\.");
            String[] parts2 = ip2.split("\\.");
            
            if (parts1.length != 4 || parts2.length != 4) {
                return false;
            }
            
            // So sánh 3 octet đầu (Class C subnet /24)
            // Hoặc 2 octet đầu cho Class B (/16)
            boolean classC = parts1[0].equals(parts2[0]) && 
                           parts1[1].equals(parts2[1]) && 
                           parts1[2].equals(parts2[2]);
            
            boolean classB = parts1[0].equals(parts2[0]) && 
                           parts1[1].equals(parts2[1]);
            
            return classC || classB;
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi so sánh subnet: " + e.getMessage());
            return false;
        }
    }
}
