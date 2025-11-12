package org.example.p2psharefile.service;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.network.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Module 7: P2PService - Service chính quản lý toàn bộ ứng dụng P2P
 *
 * Đây là lớp "facade" tổng hợp tất cả các module:
 * - Peer Discovery (UDP)
 * - File Search (Flooding)
 * - File Transfer (TCP)
 *
 * UI chỉ cần gọi P2PService, không cần biết chi tiết các module bên trong
 */
public class P2PService {

    private final PeerInfo localPeer;
    private final PeerDiscovery peerDiscovery;
    private final FileSearchService fileSearchService;
    private final FileTransferService fileTransferService;
    private final PINCodeService pinCodeService;  // ← Thêm PIN Code Service

    private final List<P2PServiceListener> listeners;

    /**
     * Interface để UI nhận thông báo từ P2P Service
     */
    public interface P2PServiceListener {
        void onPeerDiscovered(PeerInfo peer);
        void onPeerLost(PeerInfo peer);
        void onSearchResult(SearchResponse response);
        void onSearchComplete();
        void onTransferProgress(String fileName, long bytesTransferred, long totalBytes);
        void onTransferComplete(String fileName, File file);
        void onTransferError(String fileName, Exception e);
        void onServiceStarted();
        void onServiceStopped();
    }

    private volatile boolean running = false;

    /**
     * Constructor
     *
     * @param displayName Tên hiển thị của peer này
     * @param tcpPort Port TCP để nhận file
     */
    public P2PService(String displayName, int tcpPort) {
        // Tạo peer info cho local peer
        String peerId = UUID.randomUUID().toString();
        this.localPeer = new PeerInfo(peerId, getLocalIPAddress(), tcpPort, displayName);

        // Khởi tạo các service
        this.peerDiscovery = new PeerDiscovery(localPeer);
        this.fileSearchService = new FileSearchService(localPeer, peerDiscovery);
        this.fileTransferService = new FileTransferService(localPeer);
        this.pinCodeService = new PINCodeService(localPeer);  // ← Khởi tạo PIN Service

        this.listeners = new CopyOnWriteArrayList<>();

        // Đăng ký listener cho peer discovery
        setupPeerDiscoveryListener();
    }

    /**
     * Lấy địa chỉ IP local (ưu tiên IPv4 không phải loopback, tránh virtual adapter)
     */
    private String getLocalIPAddress() {
        try {
            java.net.NetworkInterface networkInterface;
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            
            String fallbackIP = null;
            
            while (interfaces.hasMoreElements()) {
                networkInterface = interfaces.nextElement();
                
                // Bỏ qua interface down hoặc loopback
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                String interfaceName = networkInterface.getName().toLowerCase();
                String displayName = networkInterface.getDisplayName().toLowerCase();
                
                // BỎ QUA virtual interfaces (Docker, Hyper-V, VMware, VirtualBox, vEthernet)
                if (interfaceName.contains("virtual") || 
                    interfaceName.contains("vmware") || 
                    interfaceName.contains("vbox") ||
                    interfaceName.contains("docker") ||
                    interfaceName.startsWith("veth") ||
                    displayName.contains("virtual") ||
                    displayName.contains("hyper-v") ||
                    displayName.contains("vmware") ||
                    displayName.contains("vbox")) {
                    System.out.println("⏭ Bỏ qua virtual interface: " + networkInterface.getName() + " (" + networkInterface.getDisplayName() + ")");
                    continue;
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    
                    // Chỉ lấy IPv4 và không phải loopback
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        
                        // ƯU TIÊN WiFi và Ethernet thật (Mac, Windows, Linux)
                        // Mac: en0, en1
                        // Windows: wlan, eth (display name có "wireless" hoặc "wi-fi")
                        // Linux: wlan0, eth0
                        if (interfaceName.startsWith("en") || 
                            interfaceName.startsWith("eth") || 
                            interfaceName.startsWith("wlan") ||
                            displayName.contains("wireless") ||
                            displayName.contains("wi-fi") ||
                            displayName.contains("802.11")) {
                            System.out.println("✓ Chọn IP từ physical interface: " + ip + " (" + networkInterface.getName() + " - " + networkInterface.getDisplayName() + ")");
                            return ip;
                        }
                        
                        // Lưu làm fallback (nhưng ưu tiên IP trong dải private phổ biến)
                        if (fallbackIP == null || ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            fallbackIP = ip;
                            System.out.println("  → Fallback IP: " + ip + " (" + networkInterface.getName() + " - " + networkInterface.getDisplayName() + ")");
                        }
                    }
                }
            }
            
            // Nếu không tìm thấy physical interface, dùng fallback
            if (fallbackIP != null) {
                System.out.println("⚠ Dùng fallback IP: " + fallbackIP);
                return fallbackIP;
            }
            
            // Fallback cuối cùng
            System.err.println("⚠ Không tìm thấy IP mạng LAN, dùng localhost");
            return "127.0.0.1";
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi lấy IP: " + e.getMessage());
            return "127.0.0.1";
        }
    }

    /**
     * Setup listener cho peer discovery
     */
    private void setupPeerDiscoveryListener() {
        peerDiscovery.addListener(new PeerDiscovery.PeerDiscoveryListener() {
            @Override
            public void onPeerDiscovered(PeerInfo peer) {
                notifyPeerDiscovered(peer);
            }

            @Override
            public void onPeerLost(PeerInfo peer) {
                notifyPeerLost(peer);
            }
        });
    }

    /**
     * Bắt đầu tất cả các service P2P
     */
    public void start() throws IOException {
        if (running) {
            System.out.println("⚠ P2P Service đã đang chạy");
            return;
        }

        System.out.println("🚀 Đang khởi động P2P Service...");
        System.out.println("   Peer ID: " + localPeer.getPeerId());
        System.out.println("   Display Name: " + localPeer.getDisplayName());
        System.out.println("   IP Address: " + localPeer.getIpAddress());
        System.out.println("   TCP Port: " + localPeer.getPort());

        // Khởi động các service theo thứ tự
        try {
            peerDiscovery.start();
            fileSearchService.start();
            fileTransferService.start();
            // pinCodeService.start();  // ← TẠM THỜI VÔ HIỆU HÓA (conflict port 8887)

            running = true;
            System.out.println("✅ P2P Service đã khởi động thành công!");
            notifyServiceStarted();

        } catch (IOException e) {
            System.err.println("❌ Lỗi khi khởi động P2P Service: " + e.getMessage());
            stop(); // Dừng các service đã khởi động
            throw e;
        }
    }

    /**
     * Dừng tất cả các service P2P
     */
    public void stop() {
        if (!running) return;

        System.out.println("🛑 Đang dừng P2P Service...");

        // pinCodeService.stop();  // ← TẠM THỜI VÔ HIỆU HÓA
        fileTransferService.stop();
        fileSearchService.stop();
        peerDiscovery.stop();

        running = false;
        System.out.println("✅ P2P Service đã dừng");
        notifyServiceStopped();
    }

    /**
     * Lấy port thực tế đang sử dụng (sau khi được auto-assign)
     */
    public int getActualPort() {
        return localPeer.getPort();
    }

    /**
     * Thêm file để chia sẻ
     *
     * @param file File cần chia sẻ
     */
    public void addSharedFile(File file) {
        if (!file.exists() || !file.isFile()) {
            System.err.println("❌ File không tồn tại: " + file.getAbsolutePath());
            return;
        }

        FileInfo fileInfo = new FileInfo(
                file.getName(),
                file.length(),
                file.getAbsolutePath()
        );

        fileSearchService.addSharedFile(file.getParent(), fileInfo);
        System.out.println("✓ Đã thêm file chia sẻ: " + file.getName());
    }

    /**
     * Thêm thư mục để chia sẻ (tất cả file trong thư mục)
     *
     * @param directory Thư mục cần chia sẻ
     */
    public void addSharedDirectory(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            System.err.println("❌ Thư mục không tồn tại: " + directory.getAbsolutePath());
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) return;

        int count = 0;
        for (File file : files) {
            if (file.isFile()) {
                addSharedFile(file);
                count++;
            }
        }

        System.out.println("✓ Đã thêm " + count + " file từ thư mục: " + directory.getName());
    }

    /**
     * Tìm kiếm file trong mạng P2P
     *
     * @param query Từ khóa tìm kiếm
     */
    public void searchFile(String query) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return;
        }

        if (query == null || query.trim().isEmpty()) {
            System.err.println("❌ Từ khóa tìm kiếm không hợp lệ");
            return;
        }

        fileSearchService.searchFile(query.trim(), new FileSearchService.SearchResultCallback() {
            @Override
            public void onSearchResult(SearchResponse response) {
                notifySearchResult(response);
            }

            @Override
            public void onSearchComplete() {
                notifySearchComplete();
            }
        });
    }

    /**
     * Download file từ peer
     *
     * @param peer Peer có file
     * @param fileInfo Thông tin file cần download
     * @param saveDirectory Thư mục lưu file
     */
    public void downloadFile(PeerInfo peer, FileInfo fileInfo, String saveDirectory) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return;
        }

        fileTransferService.downloadFile(
                peer,
                fileInfo,
                saveDirectory,
                new FileTransferService.TransferProgressListener() {
                    @Override
                    public void onProgress(long bytesTransferred, long totalBytes) {
                        notifyTransferProgress(fileInfo.getFileName(), bytesTransferred, totalBytes);
                    }

                    @Override
                    public void onComplete(File file) {
                        notifyTransferComplete(fileInfo.getFileName(), file);
                    }

                    @Override
                    public void onError(Exception e) {
                        notifyTransferError(fileInfo.getFileName(), e);
                    }
                }
        );
    }

    /**
     * Lấy danh sách peer đã phát hiện
     */
    public List<PeerInfo> getDiscoveredPeers() {
        return peerDiscovery.getDiscoveredPeers();
    }

    /**
     * Lấy số lượng peer online
     */
    public int getPeerCount() {
        return peerDiscovery.getPeerCount();
    }

    /**
     * Lấy danh sách file đang chia sẻ
     */
    public List<FileInfo> getSharedFiles() {
        return fileSearchService.getAllSharedFiles();
    }

    // ========== PIN Code Methods ==========

    /**
     * Tạo mã PIN cho file (Send Anywhere style)
     *
     * @param fileInfo File cần chia sẻ
     * @return ShareSession chứa PIN code
     */
    public ShareSession createSharePIN(FileInfo fileInfo) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return null;
        }

        return pinCodeService.createPIN(fileInfo);
    }

    /**
     * Nhận file bằng mã PIN
     *
     * @param pin Mã PIN 6 số
     * @param saveDirectory Thư mục lưu file
     */
    public void receiveByPIN(String pin, String saveDirectory) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return;
        }

        // Tìm session bằng PIN
        ShareSession session = pinCodeService.findByPIN(pin);

        if (session == null) {
            System.err.println("❌ Không tìm thấy PIN: " + pin);
            return;
        }

        if (session.isExpired()) {
            System.err.println("❌ PIN đã hết hạn: " + pin);
            return;
        }

        // Download file từ owner peer
        downloadFile(session.getOwnerPeer(), session.getFileInfo(), saveDirectory);
    }

    /**
     * Hủy mã PIN
     */
    public void cancelPIN(String pin) {
        pinCodeService.cancelPIN(pin);
    }

    /**
     * Lấy danh sách PIN đang active
     */
    public List<ShareSession> getActivePINs() {
        return pinCodeService.getActiveSessions();
    }

    /**
     * Thêm listener cho PIN events
     */
    public void addPINListener(PINCodeService.PINCodeListener listener) {
        pinCodeService.addListener(listener);
    }

    /**
     * Lấy số lượng file đang chia sẻ
     */
    public int getSharedFileCount() {
        return fileSearchService.getSharedFileCount();
    }

    /**
     * Lấy thông tin local peer
     */
    public PeerInfo getLocalPeer() {
        return localPeer;
    }

    /**
     * Kiểm tra service có đang chạy không
     */
    public boolean isRunning() {
        return running;
    }

    // ========== Listener Management ==========

    public void addListener(P2PServiceListener listener) {
        listeners.add(listener);
    }

    public void removeListener(P2PServiceListener listener) {
        listeners.remove(listener);
    }

    private void notifyPeerDiscovered(PeerInfo peer) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onPeerDiscovered(peer);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyPeerLost(PeerInfo peer) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onPeerLost(peer);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifySearchResult(SearchResponse response) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onSearchResult(response);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifySearchComplete() {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onSearchComplete();
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyTransferProgress(String fileName, long bytesTransferred, long totalBytes) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onTransferProgress(fileName, bytesTransferred, totalBytes);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyTransferComplete(String fileName, File file) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onTransferComplete(fileName, file);
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyTransferError(String fileName, Exception e) {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onTransferError(fileName, e);
            } catch (Exception ex) {
                System.err.println("Lỗi trong listener: " + ex.getMessage());
            }
        }
    }

    private void notifyServiceStarted() {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onServiceStarted();
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }

    private void notifyServiceStopped() {
        for (P2PServiceListener listener : listeners) {
            try {
                listener.onServiceStopped();
            } catch (Exception e) {
                System.err.println("Lỗi trong listener: " + e.getMessage());
            }
        }
    }
}
