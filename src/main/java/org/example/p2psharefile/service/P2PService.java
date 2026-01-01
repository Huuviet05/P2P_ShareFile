package org.example.p2psharefile.service;

import org.example.p2psharefile.model.*;
import org.example.p2psharefile.network.*;
import org.example.p2psharefile.security.SecurityManager;
import org.example.p2psharefile.security.FileHashUtil;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P2PService - Service chính quản lý toàn bộ ứng dụng P2P (với TLS + Peer Authentication)
 *
 * Đây là lớp "facade" tổng hợp tất cả các module:
 * - Security Manager (Keypair + TLS)
 * - Peer Discovery (TLS + Signatures)
 * - File Search (TLS)
 * - Chunked File Transfer (TLS + AES + Resume) - Truyền file theo chunk với progress tracking
 * - PIN Code Service (TLS + Signatures)
 *
 * UI chỉ cần gọi P2PService, không cần biết chi tiết các module bên trong
 * 
 * Security features:
 * - ECDSA keypair cho mỗi peer
 * - TLS cho tất cả network channels
 * - Signed control messages (JOIN/HEARTBEAT/PIN)
 * - Public key distribution qua PeerInfo
 */
public class P2PService {

    // Danh sách các port cố định của các service (cần tránh khi sinh port ngẫu nhiên)
    private static final Set<Integer> RESERVED_PORTS = Set.of(
        1000,  // CHUNKED_TRANSFER_PORT
        1111,  // DISCOVERY_PORT
        2222,  // PIN_SERVER_PORT
        5555,  // PREVIEW_PORT
        9000,  // SIGNALING_SERVER_PORT
        9001   // SEARCH_PORT
    );
    
    // Phạm vi port hợp lệ cho peer
    private static final int MIN_PEER_PORT = 10000;
    private static final int MAX_PEER_PORT = 60000;

    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final PeerDiscovery peerDiscovery;
    private final FileSearchService fileSearchService;
    private final ChunkedFileTransferService chunkedTransferService;
    private final PINCodeService pinCodeService;
    
    // UltraView Preview Services
    private final PreviewCacheService previewCacheService;
    private final PreviewService previewService;
    
    // Signaling Client cho P2P Hybrid (Internet)
    private SignalingClient signalingClient;
    private ScheduledExecutorService signalingRefreshExecutor;

    private final List<P2PServiceListener> listeners;

    private boolean useChunkedTransfer = true;

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
        try {
            // Tạo peer info cho local peer
            String peerId = UUID.randomUUID().toString();
            
            // ⭐ BƯỚC 1: Khởi tạo SecurityManager TRƯỚC (để có keypair)
            System.out.println("🔐 Đang khởi tạo SecurityManager...");
            this.securityManager = new SecurityManager(peerId, displayName);
            
            // ⭐ BƯỚC 1.5: Nếu tcpPort = 0, sinh port ngẫu nhiên hợp lệ
            int actualPort = tcpPort;
            if (tcpPort == 0) {
                actualPort = generateRandomAvailablePort();
                System.out.println("🎲 Port ngẫu nhiên được sinh: " + actualPort);
            }
            
            // ⭐ BƯỚC 2: Tạo PeerInfo với public key VÀ port thực tế
            String publicKeyEncoded = securityManager.getPublicKeyEncoded();
            this.localPeer = new PeerInfo(peerId, getLocalIPAddress(), actualPort, displayName, publicKeyEncoded);
            
            System.out.println("✓ Đã tạo Peer cục bộ với khóa công khai");
            System.out.println("  → Peer ID: " + peerId);
            System.out.println("  → Tên hiển thị: " + displayName);
            System.out.println("  → Khóa công khai: " + publicKeyEncoded.substring(0, 40) + "...");

            // Khởi tạo các service (với SecurityManager)
            this.peerDiscovery = new PeerDiscovery(localPeer, securityManager);
            this.fileSearchService = new FileSearchService(localPeer, peerDiscovery, securityManager);
            this.chunkedTransferService = new ChunkedFileTransferService(localPeer, securityManager);
            this.pinCodeService = new PINCodeService(localPeer, peerDiscovery, securityManager);
            
            // UltraView: Khởi tạo preview services
            this.previewCacheService = new PreviewCacheService(peerId, securityManager);
            this.previewService = new PreviewService(localPeer, securityManager, previewCacheService);
            
            // Signaling Client: Khởi tạo cho P2P Hybrid (Internet)
            this.signalingClient = new SignalingClient(localPeer, securityManager, peerDiscovery);
            setupSignalingListener();

            this.listeners = new CopyOnWriteArrayList<>();

            // Đăng ký listener cho peer discovery
            setupPeerDiscoveryListener();
            
        } catch (Exception e) {
            System.err.println("❌ Lỗi nghiêm trọng khi khởi tạo P2PService: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Không thể khởi tạo P2PService", e);
        }
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
                    // Bỏ qua virtual interface (không log để tránh nhiễu)
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
     * Sinh port ngẫu nhiên hợp lệ cho peer
     * - Nằm trong phạm vi MIN_PEER_PORT đến MAX_PEER_PORT
     * - Không trùng với các port service cố định
     * - Kiểm tra port có sẵn (không bị chiếm)
     */
    private int generateRandomAvailablePort() {
        Random random = new Random();
        int maxAttempts = 100;
        
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // Sinh port trong phạm vi hợp lệ
            int port = MIN_PEER_PORT + random.nextInt(MAX_PEER_PORT - MIN_PEER_PORT);
            
            // Kiểm tra không trùng với port service cố định
            if (RESERVED_PORTS.contains(port)) {
                continue;
            }
            
            // Kiểm tra port có sẵn không
            if (isPortAvailable(port)) {
                return port;
            }
        }
        
        // Fallback: để hệ thống tự chọn port (bind port 0)
        System.err.println("⚠ Không tìm được port trong phạm vi, dùng auto-assign từ hệ thống");
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            // Trường hợp cực hiếm: trả về port mặc định trong phạm vi
            return MIN_PEER_PORT + random.nextInt(1000);
        }
    }
    
    /**
     * Kiểm tra port có sẵn không (không bị ứng dụng khác chiếm)
     */
    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
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
     * Setup listener cho Signaling Client
     */
    private void setupSignalingListener() {
        signalingClient.addListener(new SignalingClient.SignalingListener() {
            @Override
            public void onConnected() {
                System.out.println("✅ Đã kết nối Signaling Server thành công!");
            }
            
            @Override
            public void onDisconnected() {
                System.out.println("⚠ Mất kết nối với Signaling Server");
            }
            
            @Override
            public void onPeerListUpdated(List<PeerInfo> peers) {
                System.out.println("📋 Cập nhật danh sách " + peers.size() + " peer(s) từ Internet");
            }
            
            @Override
            public void onError(String message) {
                System.err.println("❌ Lỗi Signaling: " + message);
            }
        });
    }


    /**
     * Bắt đầu tất cả các service P2P (với TLS + Peer Authentication)
     */
    public void start() throws IOException {
        if (running) {
            System.out.println("⚠ P2P Service đã đang chạy");
            return;
        }

        System.out.println("🚀 ========== KHỞI ĐỘNG P2P SERVICE (TLS + Auth) ==========");
        System.out.println("   Peer ID: " + localPeer.getPeerId());
        System.out.println("   Tên hiển thị: " + localPeer.getDisplayName());
        System.out.println("   Địa chỉ IP: " + localPeer.getIpAddress());
        System.out.println("   Port TCP yêu cầu: " + localPeer.getPort() + " (tự động gán)");
        System.out.println("   Khóa công khai: " + localPeer.getPublicKey().substring(0, 40) + "...");
        System.out.println("   Bảo mật: TLS + ECDSA Signatures");
        System.out.println("   Transfer Mode: Chunked (Resume supported)");

        try {
            // ⭐ BƯỚC 1: Start ChunkedFileTransferService
            System.out.println("\n[1/5] Khởi động ChunkedFileTransferService (TLS + Resume)...");
            chunkedTransferService.start();
            System.out.println("✓ ChunkedFileTransferService đã khởi động trên port " + 
                chunkedTransferService.getPort() + " (Chunk size: " + 
                TransferState.DEFAULT_CHUNK_SIZE / 1024 + "KB)");

            // ⭐ BƯỚC 2: Start FileSearchService
            System.out.println("\n[2/5] Khởi động FileSearchService (TLS)...");
            fileSearchService.start();
            System.out.println("✓ FileSearchService (TLS) đã khởi động");
            
            // ⭐ BƯỚC 3: Start PINCodeService
            System.out.println("\n[3/5] Khởi động PINCodeService (TLS + Signatures)...");
            pinCodeService.start();
            System.out.println("✓ PINCodeService (TLS + Signatures) đã khởi động");
            
            // ⭐ BƯỚC 3.5: Start PreviewService (UltraView)
            System.out.println("\n[3.5/5] Khởi động PreviewService (UltraView)...");
            previewService.start();
            System.out.println("✓ PreviewService đã khởi động trên port: " + previewService.getPreviewPort());

            // ⭐ BƯỚC 4: Start PeerDiscovery NHƯNG CHƯA GỬI JOIN
            System.out.println("\n[4/5] Khởi động PeerDiscovery (TLS + Signatures, chế độ lắng nghe)...");
            peerDiscovery.start(false);  // ← false = không gửi JOIN ngay
            System.out.println("✓ PeerDiscovery (TLS + Signatures) đã khởi động");

            // ⭐ BƯỚC 5: GIỜ MỚI GỬI JOIN (sau khi TẤT CẢ đã sẵn sàng)
            System.out.println("\n[5/5] Gửi signed JOIN announcement...");
            peerDiscovery.sendJoinAnnouncement();

            running = true;

            System.out.println("\n✅ ========== P2P SERVICE SẴN SÀNG (BẢO MẬT + ULTRAVIEW) ==========");
            System.out.println("📌 Thông tin Peer cuối cùng:");
            System.out.println("   - Tên hiển thị: " + localPeer.getDisplayName());
            System.out.println("   - Địa chỉ IP: " + localPeer.getIpAddress());
            System.out.println("   - Port TCP: " + localPeer.getPort());
            System.out.println("   - Port Preview: " + previewService.getPreviewPort());
            System.out.println("   - Peer ID: " + localPeer.getPeerId());
            System.out.println("   - Khóa công khai: " + localPeer.getPublicKey().substring(0, 40) + "...");
            System.out.println("   - TLS: Đã bật ✅");
            System.out.println("   - ECDSA Signatures: Đã bật ✅");
            System.out.println("   - UltraView Preview: Đã bật ✅");
            System.out.println("   - Chunked Transfer: " + (useChunkedTransfer ? "Đã bật ✅" : "Tắt"));
            System.out.println("==================================================\n");

            notifyServiceStarted();

        } catch (IOException e) {
            System.err.println("❌ Lỗi khi khởi động P2P Service: " + e.getMessage());
            e.printStackTrace();
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
        
        // Dừng Signaling Client (nếu đang kết nối)
        if (signalingClient != null && signalingClient.isConnected()) {
            signalingClient.disconnect();
        }
        stopSignalingRefresh();

        pinCodeService.stop();
        previewService.stop();  // UltraView
        chunkedTransferService.stop();  // Chunked transfer
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

        try {
            // Tính SHA-256 hash cho file
            System.out.println("🔐 Đang tính hash cho: " + file.getName() + "...");
            String fileHash = FileHashUtil.calculateSHA256(file);
            String md5Checksum = FileHashUtil.calculateMD5(file);
            
            System.out.println("  ✓ SHA-256: " + fileHash.substring(0, 16) + "...");
            System.out.println("  ✓ MD5: " + md5Checksum.substring(0, 16) + "...");
            
            // Tạo FileInfo với hash
            FileInfo fileInfo = new FileInfo(
                    file.getName(),
                    file.length(),
                    file.getAbsolutePath(),
                    md5Checksum,
                    localPeer.getPeerId()
            );
            fileInfo.setFileHash(fileHash);

            fileSearchService.addSharedFile(file.getParent(), fileInfo);
            
            // UltraView: Tạo preview manifest (từ file gốc, TRƯỚC khi mã hóa)
            try {
                System.out.println("  📸 Đang tạo preview từ file gốc...");
                // Force regenerate để áp dụng code mới (xóa cache cũ)
                PreviewManifest manifest = previewCacheService.getOrCreateManifest(file, true);
                if (manifest != null) {
                    System.out.println("  ✓ Preview manifest đã tạo (chứa: " + manifest.getAvailableTypes() + ")");
                    System.out.println("  💡 Preview sẽ được gửi đến client mà KHÔNG cần giải mã");
                }
            } catch (Exception e) {
                System.err.println("  ⚠️ Không thể tạo preview: " + e.getMessage());
            }
            
            System.out.println("✅ Đã thêm file chia sẻ: " + file.getName());
            
        } catch (IOException e) {
            System.err.println("❌ Lỗi khi tính hash: " + e.getMessage());
        }
    }
    
    /**
     * Xóa file khỏi danh sách chia sẻ
     *
     * @param fileInfo File cần xóa
     */
    public void removeSharedFile(FileInfo fileInfo) {
        if (fileInfo == null) return;
        
        File file = new File(fileInfo.getFilePath());
        fileSearchService.removeSharedFile(file.getParent(), fileInfo.getFileName());
        System.out.println("🗑️ Đã xóa file khỏi chia sẻ: " + fileInfo.getFileName());
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
     * Download file từ peer (sử dụng chunked transfer mặc định)
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

        // Luôn sử dụng chunked transfer để đồng bộ progress
        downloadFileChunked(peer, fileInfo, saveDirectory, null);
    }
    
    /**
     * Download file sử dụng chunked transfer (hỗ trợ resume)
     */
    public TransferState downloadFileChunked(PeerInfo peer, FileInfo fileInfo, 
                                             String saveDirectory, 
                                             ChunkedFileTransferService.ChunkedTransferListener listener) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return null;
        }
        
        System.out.println("📥 Bắt đầu chunked download: " + fileInfo.getFileName());
        
        // Tạo listener wrapper để notify listeners
        ChunkedFileTransferService.ChunkedTransferListener wrapperListener = 
            new ChunkedFileTransferService.ChunkedTransferListener() {
                @Override
                public void onProgress(TransferState state) {
                    notifyTransferProgress(state.getFileName(), state.getBytesTransferred(), state.getFileSize());
                    if (listener != null) listener.onProgress(state);
                }
                
                @Override
                public void onChunkReceived(TransferState state, int chunkIndex) {
                    if (listener != null) listener.onChunkReceived(state, chunkIndex);
                }
                
                @Override
                public void onComplete(TransferState state, File file) {
                    notifyTransferComplete(state.getFileName(), file);
                    if (listener != null) listener.onComplete(state, file);
                }
                
                @Override
                public void onError(TransferState state, Exception e) {
                    notifyTransferError(state.getFileName(), e);
                    if (listener != null) listener.onError(state, e);
                }
                
                @Override
                public void onPaused(TransferState state) {
                    if (listener != null) listener.onPaused(state);
                }
                
                @Override
                public void onResumed(TransferState state) {
                    if (listener != null) listener.onResumed(state);
                }
            };
        
        return chunkedTransferService.downloadFile(peer, fileInfo, saveDirectory, wrapperListener);
    }
    
    /**
     * Tạm dừng download chunked
     */
    public void pauseChunkedTransfer(String transferId) {
        chunkedTransferService.pauseTransfer(transferId);
    }
    
    /**
     * Tiếp tục download chunked
     */
    public void resumeChunkedTransfer(String transferId) {
        chunkedTransferService.resumeTransfer(transferId);
    }
    
    /**
     * Hủy download chunked
     */
    public void cancelChunkedTransfer(String transferId) {
        chunkedTransferService.cancelTransfer(transferId);
    }
    
    /**
     * Lấy trạng thái transfer
     */
    public TransferState getTransferState(String transferId) {
        return chunkedTransferService.getTransferState(transferId);
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

        ShareSession session = pinCodeService.createPIN(fileInfo);

        // PIN sẽ được gửi bởi PINCodeService.sendPINToAllPeers
        
        return session;
    }

    /**
     * Nhận file bằng mã PIN (sử dụng chunked transfer)
     *
     * @param pin Mã PIN 6 số
     * @param saveDirectory Thư mục lưu file
     * @throws IllegalStateException Nếu service chưa chạy
     * @throws IllegalArgumentException Nếu PIN không hợp lệ hoặc đã hết hạn
     */
    public void receiveByPIN(String pin, String saveDirectory) {
        if (!running) {
            throw new IllegalStateException("P2P Service chưa khởi động");
        }

        // Tìm session bằng PIN
        System.out.println("🔍 Đang tìm PIN: " + pin);
        ShareSession session = pinCodeService.findByPIN(pin);

        if (session == null) {
            throw new IllegalArgumentException("Không tìm thấy PIN: " + pin);
        }

        if (session.isExpired()) {
            throw new IllegalArgumentException("PIN đã hết hạn: " + pin);
        }

        System.out.println("✓ Tìm thấy PIN: " + pin + " -> " + session.getFileInfo().getFileName());
        System.out.println("  📁 File: " + session.getFileInfo().getFileName());
        System.out.println("  📏 Size: " + session.getFileInfo().getFileSize() + " bytes");
        
        // Download file từ owner peer (luôn dùng chunked transfer)
        downloadFileChunked(session.getOwnerPeer(), session.getFileInfo(), saveDirectory, null);
    }
    
    /**
     * Nhận file bằng mã PIN với listener để theo dõi progress
     *
     * @param pin Mã PIN 6 số
     * @param saveDirectory Thư mục lưu file
     * @param listener Listener để theo dõi progress
     * @return TransferState hoặc null
     * @throws IllegalStateException Nếu service chưa chạy
     * @throws IllegalArgumentException Nếu PIN không hợp lệ hoặc đã hết hạn
     */
    public TransferState receiveByPINWithProgress(String pin, String saveDirectory,
                                                   ChunkedFileTransferService.ChunkedTransferListener listener) {
        if (!running) {
            throw new IllegalStateException("P2P Service chưa khởi động");
        }

        // Tìm session bằng PIN
        System.out.println("🔍 Đang tìm PIN: " + pin);
        ShareSession session = pinCodeService.findByPIN(pin);

        if (session == null) {
            throw new IllegalArgumentException("Không tìm thấy PIN: " + pin);
        }

        if (session.isExpired()) {
            throw new IllegalArgumentException("PIN đã hết hạn: " + pin);
        }

        System.out.println("✓ Tìm thấy PIN: " + pin + " -> " + session.getFileInfo().getFileName());
        System.out.println("  📁 File: " + session.getFileInfo().getFileName());
        System.out.println("  📏 Size: " + session.getFileInfo().getFileSize() + " bytes");
        
        // Download file từ owner peer với listener
        return downloadFileChunked(session.getOwnerPeer(), session.getFileInfo(), saveDirectory, listener);
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
    

    
    // ========== UltraView Preview Methods ==========
    
    /**
     * Request preview manifest từ peer
     * 
     * @param peer Peer có file
     * @param fileHash Hash của file
     * @return PreviewManifest hoặc null
     */
    public PreviewManifest requestPreviewManifest(PeerInfo peer, String fileHash) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return null;
        }
        
        return previewService.requestManifest(peer, fileHash);
    }
    
    /**
     * Request preview content từ peer
     * 
     * @param peer Peer có file
     * @param fileHash Hash của file
     * @param type Loại preview
     * @return PreviewContent hoặc null
     */
    public PreviewContent requestPreviewContent(PeerInfo peer, String fileHash, 
                                               PreviewManifest.PreviewType type) {
        if (!running) {
            System.err.println("❌ P2P Service chưa khởi động");
            return null;
        }
        
        return previewService.requestContent(peer, fileHash, type);
    }
    
    /**
     * Lấy preview manifest cho file local
     * 
     * @param fileHash Hash của file
     * @return PreviewManifest hoặc null
     */
    public PreviewManifest getLocalPreviewManifest(String fileHash) {
        return previewCacheService.getManifest(fileHash);
    }
    
    /**
     * Lấy hoặc tạo preview manifest cho file
     * 
     * @param file File cần tạo preview
     * @return PreviewManifest hoặc null
     */
    public PreviewManifest getOrCreatePreviewManifest(File file) {
        return previewCacheService.getOrCreateManifest(file);
    }
    
    /**
     * Lấy preview cache service
     */
    public PreviewCacheService getPreviewCacheService() {
        return previewCacheService;
    }
    
    /**
     * Lấy preview service
     */
    public PreviewService getPreviewService() {
        return previewService;
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
    

    
    /**
     * Set connection mode cho tất cả services
     * @param p2pOnly true = P2P only (LAN), false = P2P Hybrid (Internet với signaling server)
     */
    public void setP2POnlyMode(boolean p2pOnly) {
        System.out.println("\n🔧 ========== CHUYỂN CHẾ ĐỘ KẾT NỐI ==========");
        System.out.println("   Chế độ: " + (p2pOnly ? "P2P LAN (Mạng cục bộ - Bảo mật cao)" : "P2P Hybrid (Internet với Signaling Server)"));
        
        // Set mode cho PeerDiscovery
        peerDiscovery.setP2POnlyMode(p2pOnly);
        
        // Set mode cho FileSearchService
        fileSearchService.setP2POnlyMode(p2pOnly);
        
        // Set mode cho PINCodeService và cung cấp SignalingClient
        pinCodeService.setP2POnlyMode(p2pOnly);
        if (!p2pOnly) {
            pinCodeService.setSignalingClient(signalingClient);
        }
        
        // Xử lý Signaling Client
        if (p2pOnly) {
            // Chế độ LAN: Ngắt kết nối Signaling Server
            if (signalingClient != null && signalingClient.isConnected()) {
                System.out.println("   🔌 Ngắt kết nối Signaling Server...");
                signalingClient.disconnect();
                stopSignalingRefresh();
            }
        } else {
            // Chế độ Internet: Kết nối Signaling Server
            if (signalingClient != null && !signalingClient.isConnected()) {
                System.out.println("   🌐 Đang kết nối Signaling Server...");
                signalingClient.connect();
                startSignalingRefresh();
            }
        }
        
        System.out.println("✅ Đã chuyển chế độ kết nối thành công!");
        System.out.println("================================================\n");
    }
    
    /**
     * Bắt đầu refresh định kỳ danh sách peers từ Signaling Server
     */
    private void startSignalingRefresh() {
        if (signalingRefreshExecutor != null) {
            signalingRefreshExecutor.shutdownNow();
        }
        
        signalingRefreshExecutor = Executors.newScheduledThreadPool(1);
        signalingRefreshExecutor.scheduleAtFixedRate(() -> {
            if (signalingClient != null && signalingClient.isConnected()) {
                signalingClient.refreshPeerList();
            }
        }, 5, 30, TimeUnit.SECONDS); // Refresh mỗi 30 giây
        
        System.out.println("   ⏰ Đã bắt đầu refresh định kỳ danh sách peers (30s)");
    }
    
    /**
     * Dừng refresh định kỳ
     */
    private void stopSignalingRefresh() {
        if (signalingRefreshExecutor != null) {
            signalingRefreshExecutor.shutdownNow();
            signalingRefreshExecutor = null;
        }
    }
    
    /**
     * Cấu hình địa chỉ Signaling Server
     */
    public void setSignalingServerAddress(String host, int port) {
        if (signalingClient != null) {
            signalingClient.setServerAddress(host, port);
            System.out.println("📍 Đã cấu hình Signaling Server: " + host + ":" + port);
        }
    }
    
    /**
     * Lấy SignalingClient
     */
    public SignalingClient getSignalingClient() {
        return signalingClient;
    }
    
    /**
     * Kiểm tra đã kết nối Signaling Server chưa
     */
    public boolean isSignalingConnected() {
        return signalingClient != null && signalingClient.isConnected();
    }
    
    /**
     * Kiểm tra mode hiện tại
     */
    public boolean isP2POnlyMode() {
        return fileSearchService.isP2POnlyMode();
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
