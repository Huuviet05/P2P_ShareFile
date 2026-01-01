package org.example.p2psharefile.network;

import org.example.p2psharefile.compression.FileCompression;
import org.example.p2psharefile.model.FileInfo;
import org.example.p2psharefile.model.PeerInfo;
import org.example.p2psharefile.model.TransferState;
import org.example.p2psharefile.model.TransferState.TransferStatus;
import org.example.p2psharefile.security.AESEncryption;
import org.example.p2psharefile.security.SecurityManager;

import javax.crypto.SecretKey;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * ChunkedFileTransferService - Truyền file theo chunk với hỗ trợ resume
 * 
 * Đặc điểm:
 * - Chia file thành các chunk nhỏ (mặc định 64KB)
 * - Mỗi chunk được mã hóa và gửi riêng biệt
 * - Hỗ trợ pause/resume download
 * - Progress tracking chi tiết
 * - Khôi phục từ chunk cuối cùng khi resume
 * 
 * Protocol:
 * - REQUEST_METADATA: Yêu cầu thông tin file
 * - REQUEST_CHUNK: Yêu cầu chunk cụ thể
 * - RESPONSE_METADATA: Trả về metadata
 * - RESPONSE_CHUNK: Trả về dữ liệu chunk
 * 
 * @author P2PShareFile Team
 * @version 2.0 - Chunked Transfer with Server Socket
 */
public class ChunkedFileTransferService {
    
    private static final Logger LOGGER = Logger.getLogger(ChunkedFileTransferService.class.getName());
    private static final String DEFAULT_KEY = "P2PShareFileSecretKey123456789";
    private static final int CONNECTION_TIMEOUT = 10000;  // 10s (tăng từ 5s)
    private static final int READ_TIMEOUT = 120000;       // 120s (tăng từ 60s)
    private static final int CHUNKED_TRANSFER_PORT = 9999; // Port cố định cho chunked transfer
    
    // Protocol commands
    private static final byte CMD_REQUEST_METADATA = 0x01;
    private static final byte CMD_REQUEST_CHUNK = 0x02;
    private static final byte CMD_RESPONSE_METADATA = 0x11;
    private static final byte CMD_RESPONSE_CHUNK = 0x12;
    private static final byte CMD_ERROR = (byte) 0xFF;
    
    private final PeerInfo localPeer;
    private final SecurityManager securityManager;
    private final SecretKey encryptionKey;
    
    // Server socket để nhận requests từ peers khác
    private SSLServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    // Active transfers
    private final Map<String, TransferState> activeTransfers = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> transferTasks = new ConcurrentHashMap<>();
    
    /**
     * Interface callback cho progress
     */
    public interface ChunkedTransferListener {
        void onProgress(TransferState state);
        void onChunkReceived(TransferState state, int chunkIndex);
        void onComplete(TransferState state, File file);
        void onError(TransferState state, Exception e);
        void onPaused(TransferState state);
        void onResumed(TransferState state);
    }
    
    public ChunkedFileTransferService(PeerInfo localPeer, SecurityManager securityManager) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.encryptionKey = AESEncryption.createKeyFromString(DEFAULT_KEY);
    }
    
    public ChunkedFileTransferService(PeerInfo localPeer, SecurityManager securityManager, SecretKey customKey) {
        this.localPeer = localPeer;
        this.securityManager = securityManager;
        this.encryptionKey = customKey;
    }
    
    /**
     * Bắt đầu service với server socket để nhận requests
     */
    public void start() throws IOException {
        if (running) return;
        
        running = true;
        executorService = Executors.newCachedThreadPool();
        
        // Tạo SSLServerSocket để lắng nghe chunk requests
        serverSocket = securityManager.createSSLServerSocket(CHUNKED_TRANSFER_PORT);
        
        // Thread lắng nghe requests
        executorService.submit(this::listenForRequests);
        
        System.out.println("✓ Chunked File Transfer Service đã khởi động trên port " + CHUNKED_TRANSFER_PORT);
    }
    
    /**
     * Thread lắng nghe requests từ peers
     */
    private void listenForRequests() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleClientRequest((SSLSocket) clientSocket));
            } catch (SocketException e) {
                // Server socket đã đóng
                if (running) {
                    LOGGER.warning("Server socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    LOGGER.warning("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Lấy port đang dùng
     */
    public int getPort() {
        return CHUNKED_TRANSFER_PORT;
    }
    
    /**
     * Dừng service
     */
    public void stop() {
        running = false;
        
        // Cancel tất cả active transfers
        for (Future<?> task : transferTasks.values()) {
            task.cancel(true);
        }
        transferTasks.clear();
        activeTransfers.clear();
        
        // Đóng server socket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOGGER.warning("Error closing server socket: " + e.getMessage());
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        System.out.println("✓ Chunked File Transfer Service đã dừng");
    }
    
    /**
     * Xử lý request từ client (được gọi từ FileTransferService)
     */
    private void handleClientRequest(SSLSocket socket) {
        try {
            socket.setSoTimeout(READ_TIMEOUT);
            socket.startHandshake();
            
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            
            byte command = dis.readByte();
            
            switch (command) {
                case CMD_REQUEST_METADATA:
                    handleMetadataRequest(dis, dos);
                    break;
                case CMD_REQUEST_CHUNK:
                    handleChunkRequest(dis, dos);
                    break;
                default:
                    dos.writeByte(CMD_ERROR);
                    dos.writeUTF("Unknown command: " + command);
            }
            
        } catch (Exception e) {
            LOGGER.warning("Error handling client request: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
    
    /**
     * Xử lý yêu cầu metadata
     */
    private void handleMetadataRequest(DataInputStream dis, DataOutputStream dos) throws IOException {
        String filePath = dis.readUTF();
        File file = new File(filePath);
        
        if (!file.exists() || !file.isFile()) {
            dos.writeByte(CMD_ERROR);
            dos.writeUTF("File không tồn tại: " + filePath);
            return;
        }
        
        dos.writeByte(CMD_RESPONSE_METADATA);
        dos.writeUTF(file.getName());                           // fileName
        dos.writeLong(file.length());                           // fileSize
        dos.writeInt(TransferState.DEFAULT_CHUNK_SIZE);         // chunkSize
        dos.writeBoolean(FileCompression.shouldCompress(file.getName())); // compressed
        dos.flush();
        
        System.out.println("📋 Đã gửi metadata: " + file.getName() + " (" + file.length() + " bytes)");
    }
    
    /**
     * Xử lý yêu cầu chunk
     */
    private void handleChunkRequest(DataInputStream dis, DataOutputStream dos) throws IOException {
        String filePath = dis.readUTF();
        int chunkIndex = dis.readInt();
        int chunkSize = dis.readInt();
        
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            dos.writeByte(CMD_ERROR);
            dos.writeUTF("File không tồn tại");
            return;
        }
        
        long offset = (long) chunkIndex * chunkSize;
        int actualChunkSize = (int) Math.min(chunkSize, file.length() - offset);
        
        if (offset >= file.length() || actualChunkSize <= 0) {
            dos.writeByte(CMD_ERROR);
            dos.writeUTF("Invalid chunk index: " + chunkIndex);
            return;
        }
        
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            
            byte[] chunkData = new byte[actualChunkSize];
            int bytesRead = raf.read(chunkData);
            
            if (bytesRead != actualChunkSize) {
                dos.writeByte(CMD_ERROR);
                dos.writeUTF("Failed to read chunk data");
                return;
            }
            
            // Nén nếu cần
            boolean shouldCompress = FileCompression.shouldCompress(file.getName());
            if (shouldCompress) {
                chunkData = FileCompression.compress(chunkData);
            }
            
            // Mã hóa
            byte[] encryptedChunk = null;
            try {
                encryptedChunk = AESEncryption.encrypt(chunkData, encryptionKey);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // Gửi response
            dos.writeByte(CMD_RESPONSE_CHUNK);
            dos.writeInt(chunkIndex);                    // chunkIndex
            dos.writeInt(actualChunkSize);               // originalSize
            dos.writeBoolean(shouldCompress);            // compressed
            dos.writeInt(encryptedChunk.length);         // encryptedSize
            dos.write(encryptedChunk);                   // data
            dos.flush();
        }
    }
    
    // ========== Download methods ==========
    
    /**
     * Download file với chunked transfer (hỗ trợ resume)
     */
    public TransferState downloadFile(PeerInfo peer, FileInfo fileInfo, 
                                      String saveDirectory, ChunkedTransferListener listener) {
        // Tạo hoặc lấy TransferState existing
        String transferKey = peer.getPeerId() + "_" + fileInfo.getFilePath();
        TransferState state = activeTransfers.get(transferKey);
        
        if (state == null) {
            state = new TransferState(fileInfo.getFileName(), fileInfo.getFilePath(), fileInfo.getFileSize());
            state.setSaveDirectory(saveDirectory);
            state.setPeerIp(peer.getIpAddress());
            state.setPeerPort(CHUNKED_TRANSFER_PORT);
            activeTransfers.put(transferKey, state);
        }
        
        final TransferState finalState = state;
        
        // Bắt đầu download task
        Future<?> task = executorService.submit(() -> {
            try {
                downloadChunks(peer, fileInfo, finalState, listener);
            } catch (Exception e) {
                finalState.fail(e.getMessage());
                if (listener != null) {
                    listener.onError(finalState, e);
                }
            }
        });
        
        transferTasks.put(transferKey, task);
        return state;
    }
    
    /**
     * Download các chunk
     */
    private void downloadChunks(PeerInfo peer, FileInfo fileInfo, 
                               TransferState state, ChunkedTransferListener listener) throws Exception {
        
        System.out.println("📥 Bắt đầu chunked download: " + fileInfo.getFileName());
        
        // 1. Lấy metadata từ peer
        if (state.getTotalChunks() == 0 || state.getStatus() == TransferStatus.PENDING) {
            requestMetadata(peer, fileInfo, state);
        }
        
        state.start();
        
        // 2. Tạo file tạm để lưu chunks
        File saveDir = new File(state.getSaveDirectory());
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        
        File tempFile = new File(saveDir, state.getFileName() + ".part");
        File finalFile = new File(saveDir, state.getFileName());
        
        // 3. Tạo file với kích thước đầy đủ nếu chưa có
        if (!tempFile.exists()) {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                raf.setLength(state.getFileSize());
            }
        }
        
        // 4. Download từng chunk
        int totalChunks = state.getTotalChunks();
        int startChunk = state.getNextMissingChunk();
        
        System.out.println("  📦 Tổng chunks: " + totalChunks + ", bắt đầu từ: " + startChunk);
        
        for (int i = startChunk; i < totalChunks; i++) {
            // Kiểm tra thread interrupted
            if (Thread.currentThread().isInterrupted()) {
                System.out.println("  ❌ Thread bị interrupted - dừng download");
                state.cancel();
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                return;
            }
            
            // Kiểm tra trạng thái CANCELLED trước
            if (state.getStatus() == TransferStatus.CANCELLED) {
                System.out.println("  ❌ Download đã bị hủy (status: CANCELLED)");
                // Xóa file tạm
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                return;
            }
            
            // Chờ nếu đang pause - với kiểm tra CANCELLED trong loop
            while (state.getStatus() == TransferStatus.PAUSED) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // Thread bị interrupt (cancel) trong khi đang pause - đây là bình thường
                    System.out.println("  ⏹ Thread interrupted trong khi pause - dừng download");
                    state.cancel();
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                    return;
                }
                // Kiểm tra nếu bị cancel trong khi đang pause
                if (state.getStatus() == TransferStatus.CANCELLED) {
                    System.out.println("  ❌ Download đã bị hủy (từ trạng thái pause)");
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                    return;
                }
            }
            
            // Kiểm tra lại CANCELLED sau khi resume
            if (state.getStatus() == TransferStatus.CANCELLED) {
                System.out.println("  ❌ Download đã bị hủy");
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                return;
            }
            
            // Skip chunk đã nhận
            if (state.isChunkReceived(i)) {
                continue;
            }
            
            // Download chunk
            byte[] chunkData = downloadChunk(peer, fileInfo.getFilePath(), i, state.getChunkSize());
            
            if (chunkData != null) {
                // Kiểm tra trạng thái trước khi ghi
                if (state.getStatus() == TransferStatus.CANCELLED) {
                    System.out.println("  ❌ Download đã bị hủy (trước khi ghi chunk)");
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                    return;
                }
                
                // Ghi chunk vào file
                long offset = state.getChunkOffset(i);
                try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
                    raf.seek(offset);
                    raf.write(chunkData);
                }
                
                // Cập nhật state
                state.markChunkReceived(i, chunkData.length);
                
                // Notify listener
                if (listener != null) {
                    listener.onChunkReceived(state, i);
                    listener.onProgress(state);
                }
                
                // Log progress mỗi 10%
                int percent = state.getProgressPercent();
                if (percent % 10 == 0) {
                    System.out.printf("  ⏳ Progress: %d%% (%d/%d chunks)%n", 
                        percent, state.getReceivedChunkCount(), totalChunks);
                }
            } else {
                throw new IOException("Failed to download chunk " + i);
            }
        }
        
        // 5. Hoàn tất
        if (state.isComplete()) {
            // Rename temp file to final
            if (finalFile.exists()) {
                finalFile.delete();
            }
            tempFile.renameTo(finalFile);
            
            state.complete();
            System.out.println("  ✅ Download hoàn tất: " + finalFile.getAbsolutePath());
            
            if (listener != null) {
                listener.onComplete(state, finalFile);
            }
            
            // Cleanup
            String transferKey = peer.getPeerId() + "_" + fileInfo.getFilePath();
            activeTransfers.remove(transferKey);
            transferTasks.remove(transferKey);
        }
    }
    
    /**
     * Yêu cầu metadata từ peer
     */
    private void requestMetadata(PeerInfo peer, FileInfo fileInfo, TransferState state) throws Exception {
        SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), CHUNKED_TRANSFER_PORT);
        socket.connect(new InetSocketAddress(peer.getIpAddress(), CHUNKED_TRANSFER_PORT), CONNECTION_TIMEOUT);
        socket.setSoTimeout(READ_TIMEOUT);
        socket.startHandshake();
        
        try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            
            dos.writeByte(CMD_REQUEST_METADATA);
            dos.writeUTF(fileInfo.getFilePath());
            dos.flush();
            
            byte response = dis.readByte();
            if (response == CMD_ERROR) {
                throw new IOException(dis.readUTF());
            }
            
            if (response == CMD_RESPONSE_METADATA) {
                String fileName = dis.readUTF();
                long fileSize = dis.readLong();
                int chunkSize = dis.readInt();
                boolean compressed = dis.readBoolean();
                
                state.setFileName(fileName);
                state.setFileSize(fileSize);
                state.setChunkSize(chunkSize);
                
                System.out.println("  📋 Metadata: " + fileName + " (" + fileSize + " bytes, " + 
                    state.getTotalChunks() + " chunks)");
            }
        } finally {
            socket.close();
        }
    }
    
    /**
     * Download một chunk từ peer
     */
    private byte[] downloadChunk(PeerInfo peer, String filePath, int chunkIndex, int chunkSize) throws Exception {
        SSLSocket socket = securityManager.createSSLSocket(peer.getIpAddress(), CHUNKED_TRANSFER_PORT);
        socket.connect(new InetSocketAddress(peer.getIpAddress(), CHUNKED_TRANSFER_PORT), CONNECTION_TIMEOUT);
        socket.setSoTimeout(READ_TIMEOUT);
        socket.startHandshake();
        
        try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            
            dos.writeByte(CMD_REQUEST_CHUNK);
            dos.writeUTF(filePath);
            dos.writeInt(chunkIndex);
            dos.writeInt(chunkSize);
            dos.flush();
            
            byte response = dis.readByte();
            if (response == CMD_ERROR) {
                throw new IOException(dis.readUTF());
            }
            
            if (response == CMD_RESPONSE_CHUNK) {
                int receivedIndex = dis.readInt();
                int originalSize = dis.readInt();
                boolean compressed = dis.readBoolean();
                int encryptedSize = dis.readInt();
                
                byte[] encryptedData = new byte[encryptedSize];
                dis.readFully(encryptedData);
                
                // Giải mã
                byte[] decrypted = AESEncryption.decrypt(encryptedData, encryptionKey);
                
                // Giải nén nếu cần
                if (compressed) {
                    decrypted = FileCompression.decompress(decrypted);
                }
                
                return decrypted;
            }
            
            return null;
        } finally {
            socket.close();
        }
    }
    
    // ========== Control methods ==========
    
    /**
     * Tạm dừng download
     */
    public void pauseTransfer(String transferId) {
        System.out.println("⏸ Yêu cầu pause transfer: " + transferId);
        System.out.println("  📋 Active transfers: " + activeTransfers.size());
        
        for (TransferState state : activeTransfers.values()) {
            System.out.println("  → Checking: " + state.getTransferId());
            if (state.getTransferId().equals(transferId)) {
                state.pause();
                System.out.println("⏸ Đã tạm dừng: " + state.getFileName() + " (status: " + state.getStatus() + ")");
                return;
            }
        }
        System.out.println("⚠ Không tìm thấy transfer với ID: " + transferId);
    }
    
    /**
     * Tiếp tục download
     */
    public void resumeTransfer(String transferId) {
        System.out.println("▶ Yêu cầu resume transfer: " + transferId);
        
        for (TransferState state : activeTransfers.values()) {
            if (state.getTransferId().equals(transferId)) {
                state.resume();
                System.out.println("▶ Tiếp tục: " + state.getFileName() + " (status: " + state.getStatus() + ")");
                return;
            }
        }
        System.out.println("⚠ Không tìm thấy transfer với ID: " + transferId);
    }
    
    /**
     * Hủy download
     */
    public void cancelTransfer(String transferId) {
        System.out.println("❌ Yêu cầu cancel transfer: " + transferId);
        System.out.println("  📋 Active transfers: " + activeTransfers.size());
        
        for (Map.Entry<String, TransferState> entry : activeTransfers.entrySet()) {
            System.out.println("  → Checking: " + entry.getValue().getTransferId());
            if (entry.getValue().getTransferId().equals(transferId)) {
                // Đặt status CANCELLED TRƯỚC
                entry.getValue().cancel();
                System.out.println("  ✓ Status set to CANCELLED: " + entry.getValue().getStatus());
                
                // Cancel task với interrupt
                Future<?> task = transferTasks.get(entry.getKey());
                if (task != null) {
                    boolean cancelled = task.cancel(true);
                    System.out.println("  ✓ Task cancelled: " + cancelled);
                }
                
                // Xóa file tạm
                File tempFile = new File(entry.getValue().getSaveDirectory(), 
                    entry.getValue().getFileName() + ".part");
                if (tempFile.exists()) {
                    boolean deleted = tempFile.delete();
                    System.out.println("  ✓ Temp file deleted: " + deleted);
                }
                
                activeTransfers.remove(entry.getKey());
                transferTasks.remove(entry.getKey());
                
                System.out.println("❌ Đã hủy hoàn toàn: " + entry.getValue().getFileName());
                return;
            }
        }
        System.out.println("⚠ Không tìm thấy transfer với ID: " + transferId);
    }
    
    /**
     * Lấy trạng thái transfer
     */
    public TransferState getTransferState(String transferId) {
        for (TransferState state : activeTransfers.values()) {
            if (state.getTransferId().equals(transferId)) {
                return state;
            }
        }
        return null;
    }
    
    /**
     * Lấy tất cả active transfers
     */
    public Map<String, TransferState> getActiveTransfers() {
        return new ConcurrentHashMap<>(activeTransfers);
    }
}
