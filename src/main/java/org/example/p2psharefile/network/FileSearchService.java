package org.example.p2psharefile.network;

import org.example.p2psharefile.model.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * FileSearchService - Tìm kiếm file qua UDP broadcast (ĐƠN GIẢN HÓA)
 * 
 * Thay vì dùng TCP riêng, gửi search qua UDP broadcast
 */
public class FileSearchService {
    
    private static final int SEARCH_UDP_PORT = 8891;
    private static final int SEARCH_TIMEOUT = 10000;
    
    private final PeerInfo localPeer;
    private final PeerDiscovery peerDiscovery;
    private final Map<String, List<FileInfo>> sharedFiles;
    private final Set<String> processedRequests;
    
    private DatagramSocket searchSocket;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutor;
    private volatile boolean running = false;
    
    private final Map<String, SearchResultCallback> activeSearches;
    
    public interface SearchResultCallback {
        void onSearchResult(SearchResponse response);
        void onSearchComplete();
    }
    
    public FileSearchService(PeerInfo localPeer, PeerDiscovery peerDiscovery) {
        this.localPeer = localPeer;
        this.peerDiscovery = peerDiscovery;
        this.sharedFiles = new ConcurrentHashMap<>();
        this.processedRequests = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.activeSearches = new ConcurrentHashMap<>();
    }
    
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        
        // UDP socket với SO_REUSEADDR
        searchSocket = new DatagramSocket(null);
        searchSocket.setReuseAddress(true);
        searchSocket.setBroadcast(true);
        searchSocket.bind(new InetSocketAddress(SEARCH_UDP_PORT));
        
        executorService = Executors.newCachedThreadPool();
        scheduledExecutor = Executors.newScheduledThreadPool(1);
        executorService.submit(this::listenForMessages);
        
        System.out.println("✓ File Search Service (UDP) đã khởi động trên port " + SEARCH_UDP_PORT);
    }
    
    public void stop() {
        running = false;
        if (searchSocket != null && !searchSocket.isClosed()) {
            searchSocket.close();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
        }
    }
    
    /**
     * Lắng nghe UDP messages
     */
    private void listenForMessages() {
        byte[] buffer = new byte[65536];
        
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                searchSocket.receive(packet);
                
                ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                ObjectInputStream ois = new ObjectInputStream(bais);
                Object message = ois.readObject();
                
                if (message instanceof SearchRequest) {
                    handleSearchRequest((SearchRequest) message, packet.getAddress());
                } else if (message instanceof SearchResponse) {
                    handleSearchResponse((SearchResponse) message);
                }
                
            } catch (SocketException e) {
                if (running) System.err.println("Socket error: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Lỗi nhận message: " + e.getMessage());
            }
        }
    }
    
    /**
     * Xử lý search request
     */
    private void handleSearchRequest(SearchRequest request, InetAddress senderAddress) {
        // **FIX 2**: Bỏ qua request từ chính mình
        if (request.getOriginPeerId().equals(localPeer.getPeerId())) {
            System.out.println("⏭ Bỏ qua search request từ chính mình");
            return;
        }
        
        // Tránh xử lý trùng
        if (processedRequests.contains(request.getRequestId())) {
            System.out.println("⚠ Request đã xử lý rồi: " + request.getRequestId());
            return;
        }
        processedRequests.add(request.getRequestId());
        
        System.out.println("🔍 Nhận search request: \"" + request.getSearchQuery() + "\" từ " + senderAddress);
        System.out.println("  → Số file đang chia sẻ: " + getSharedFileCount());
        
        // Tìm file local
        List<FileInfo> foundFiles = new ArrayList<>();
        String query = request.getSearchQuery().toLowerCase();
        
        for (Map.Entry<String, List<FileInfo>> entry : sharedFiles.entrySet()) {
            System.out.println("  → Kiểm tra thư mục: " + entry.getKey() + " (" + entry.getValue().size() + " files)");
            for (FileInfo file : entry.getValue()) {
                System.out.println("    - File: " + file.getFileName());
                if (file.getFileName().toLowerCase().contains(query)) {
                    foundFiles.add(file);
                    System.out.println("      ✓ KHỚP!");
                }
            }
        }
        
        // Nếu tìm thấy, gửi response về (broadcast để chắc chắn nhận được)
        if (!foundFiles.isEmpty()) {
            SearchResponse response = new SearchResponse(request.getRequestId(), localPeer, foundFiles);
            broadcastSearchResponse(response);
            System.out.println("📦 Tìm thấy " + foundFiles.size() + " file, broadcast response");
        } else {
            System.out.println("⚠ Không tìm thấy file nào khớp với: \"" + query + "\"");
        }
    }
    
    /**
     * Xử lý search response
     */
    private void handleSearchResponse(SearchResponse response) {
        // Chỉ xử lý nếu đây là response cho request của mình
        SearchResultCallback callback = activeSearches.get(response.getRequestId());
        if (callback != null) {
            System.out.println("📥 Nhận response: " + response.getFoundFiles().size() + " files từ " + 
                             response.getSourcePeer().getDisplayName());
            callback.onSearchResult(response);
        }
        // Nếu không có callback, nghĩa là response này không phải cho mình → bỏ qua
    }
    
    /**
     * Tìm kiếm file
     */
    public void searchFile(String query, SearchResultCallback callback) {
        String requestId = UUID.randomUUID().toString();
        SearchRequest request = new SearchRequest(requestId, localPeer.getPeerId(), query, 5);
        
        activeSearches.put(requestId, callback);
        
        // **KHÔNG TÌM LOCAL** - Chỉ tìm từ peer khác
        // Vì peer đã có file rồi, không cần tìm để tải về
        System.out.println("🔍 Bắt đầu tìm kiếm từ các peer khác: \"" + query + "\"");
        
        // Broadcast request để tìm từ peer khác
        broadcastSearchRequest(request);
        
        // Timeout sau SEARCH_TIMEOUT
        scheduledExecutor.schedule(() -> {
            activeSearches.remove(requestId);
            callback.onSearchComplete();
        }, SEARCH_TIMEOUT, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Broadcast search request qua UDP
     */
    private void broadcastSearchRequest(SearchRequest request) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(request);
            oos.flush();
            
            byte[] data = baos.toByteArray();
            DatagramPacket packet = new DatagramPacket(
                data, 
                data.length,
                InetAddress.getByName("255.255.255.255"),
                SEARCH_UDP_PORT
            );
            
            searchSocket.send(packet);
            System.out.println("📡 Broadcast search request: \"" + request.getSearchQuery() + "\" (" + data.length + " bytes)");
            
        } catch (IOException e) {
            System.err.println("❌ Lỗi broadcast search: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Broadcast search response qua UDP (thay vì unicast)
     */
    private void broadcastSearchResponse(SearchResponse response) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(response);
            oos.flush();
            
            byte[] data = baos.toByteArray();
            DatagramPacket packet = new DatagramPacket(
                data, 
                data.length,
                InetAddress.getByName("255.255.255.255"),
                SEARCH_UDP_PORT
            );
            
            searchSocket.send(packet);
            System.out.println("📡 Broadcast search response với " + response.getFoundFiles().size() + " files");
            
        } catch (IOException e) {
            System.err.println("❌ Lỗi broadcast response: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Thêm file chia sẻ
     */
    public void addSharedFile(String directory, FileInfo fileInfo) {
        sharedFiles.computeIfAbsent(directory, k -> new CopyOnWriteArrayList<>()).add(fileInfo);
        System.out.println("✓ [FileSearchService] Đã thêm file: " + fileInfo.getFileName() + 
                           " vào thư mục: " + directory);
        System.out.println("  → Tổng số file đang chia sẻ: " + getSharedFileCount());
    }
    
    public void removeSharedFile(String directory, String fileName) {
        List<FileInfo> files = sharedFiles.get(directory);
        if (files != null) {
            files.removeIf(f -> f.getFileName().equals(fileName));
        }
    }
    
    public Map<String, List<FileInfo>> getSharedFiles() {
        return new HashMap<>(sharedFiles);
    }
    
    /**
     * Lấy tất cả file đang chia sẻ (flatten)
     */
    public List<FileInfo> getAllSharedFiles() {
        List<FileInfo> allFiles = new ArrayList<>();
        for (List<FileInfo> files : sharedFiles.values()) {
            allFiles.addAll(files);
        }
        return allFiles;
    }
    
    /**
     * Đếm số lượng file đang chia sẻ
     */
    public int getSharedFileCount() {
        int count = 0;
        for (List<FileInfo> files : sharedFiles.values()) {
            count += files.size();
        }
        return count;
    }
}
