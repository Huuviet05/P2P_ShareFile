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
        socket.bind(new InetSocketAddress(DISCOVERY_PORT));
        
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
        
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet); // Block và chờ packet
                
                // Deserialize peer info từ packet
                ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData());
                ObjectInputStream ois = new ObjectInputStream(bais);
                PeerInfo receivedPeer = (PeerInfo) ois.readObject();
                
                // Không thêm chính mình vào danh sách
                if (!receivedPeer.getPeerId().equals(localPeer.getPeerId())) {
                    handleDiscoveredPeer(receivedPeer);
                }
                
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
                
                // Chờ HEARTBEAT_INTERVAL trước khi gửi lại
                Thread.sleep(HEARTBEAT_INTERVAL);
                
            } catch (Exception e) {
                if (running) {
                    System.err.println("Lỗi khi broadcast heartbeat: " + e.getMessage());
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
}
