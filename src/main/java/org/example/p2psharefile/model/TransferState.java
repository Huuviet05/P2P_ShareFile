package org.example.p2psharefile.model;

import java.io.Serializable;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TransferState - Trạng thái truyền file theo chunk
 * 
 * Lưu trữ thông tin về tiến trình truyền file để hỗ trợ:
 * - Resume: Tiếp tục download từ chunk đã dừng
 * - Progress tracking: Theo dõi tiến trình theo %
 * - Integrity check: Xác nhận chunk đã nhận đúng
 * 
 * @author P2PShareFile Team
 * @version 1.0
 */
public class TransferState implements Serializable {
    private static final long serialVersionUID = 1L;
    
    // Chunk configuration
    public static final int DEFAULT_CHUNK_SIZE = 64 * 1024; // 64KB per chunk
    public static final int MIN_CHUNK_SIZE = 8 * 1024;      // 8KB minimum
    public static final int MAX_CHUNK_SIZE = 1024 * 1024;   // 1MB maximum
    
    // Transfer info
    private String transferId;
    private String fileName;
    private String filePath;
    private long fileSize;
    private int chunkSize;
    private int totalChunks;
    
    // Progress tracking
    private BitSet receivedChunks;
    private AtomicLong bytesTransferred;
    private long startTime;
    private long lastUpdateTime;
    
    // State
    private TransferStatus status;
    private String errorMessage;
    private String saveDirectory;
    
    // Peer info
    private String peerIp;
    private int peerPort;
    
    public enum TransferStatus {
        PENDING,        // Chờ bắt đầu
        IN_PROGRESS,    // Đang truyền
        PAUSED,         // Tạm dừng
        COMPLETED,      // Hoàn tất
        FAILED,         // Thất bại
        CANCELLED       // Đã hủy
    }
    
    /**
     * Constructor mặc định
     */
    public TransferState() {
        this.chunkSize = DEFAULT_CHUNK_SIZE;
        this.bytesTransferred = new AtomicLong(0);
        this.status = TransferStatus.PENDING;
    }
    
    /**
     * Constructor với thông tin file
     */
    public TransferState(String fileName, String filePath, long fileSize) {
        this();
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.totalChunks = calculateTotalChunks(fileSize, chunkSize);
        this.receivedChunks = new BitSet(totalChunks);
        this.transferId = generateTransferId();
    }
    
    /**
     * Constructor đầy đủ với custom chunk size
     */
    public TransferState(String fileName, String filePath, long fileSize, int chunkSize) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.chunkSize = Math.max(MIN_CHUNK_SIZE, Math.min(MAX_CHUNK_SIZE, chunkSize));
        this.totalChunks = calculateTotalChunks(fileSize, this.chunkSize);
        this.receivedChunks = new BitSet(totalChunks);
        this.bytesTransferred = new AtomicLong(0);
        this.status = TransferStatus.PENDING;
        this.transferId = generateTransferId();
    }
    
    /**
     * Tính số lượng chunk cần thiết
     */
    private int calculateTotalChunks(long fileSize, int chunkSize) {
        return (int) Math.ceil((double) fileSize / chunkSize);
    }
    
    /**
     * Tạo transfer ID unique
     */
    private String generateTransferId() {
        return fileName + "_" + System.currentTimeMillis() + "_" + 
               Integer.toHexString((int) (Math.random() * 0xFFFF));
    }
    
    // ========== Progress tracking methods ==========
    
    /**
     * Đánh dấu chunk đã nhận
     */
    public synchronized void markChunkReceived(int chunkIndex, int chunkBytes) {
        if (chunkIndex >= 0 && chunkIndex < totalChunks) {
            receivedChunks.set(chunkIndex);
            bytesTransferred.addAndGet(chunkBytes);
            lastUpdateTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Kiểm tra chunk đã nhận chưa
     */
    public boolean isChunkReceived(int chunkIndex) {
        return chunkIndex >= 0 && chunkIndex < totalChunks && receivedChunks.get(chunkIndex);
    }
    
    /**
     * Lấy chunk tiếp theo cần nhận
     */
    public int getNextMissingChunk() {
        return receivedChunks.nextClearBit(0);
    }
    
    /**
     * Lấy danh sách chunk còn thiếu
     */
    public int[] getMissingChunks() {
        int[] missing = new int[totalChunks - receivedChunks.cardinality()];
        int idx = 0;
        for (int i = 0; i < totalChunks; i++) {
            if (!receivedChunks.get(i)) {
                missing[idx++] = i;
            }
        }
        return missing;
    }
    
    /**
     * Số chunk đã nhận
     */
    public int getReceivedChunkCount() {
        return receivedChunks.cardinality();
    }
    
    /**
     * Tiến độ (0.0 - 1.0)
     */
    public double getProgress() {
        return totalChunks > 0 ? (double) receivedChunks.cardinality() / totalChunks : 0;
    }
    
    /**
     * Tiến độ theo phần trăm
     */
    public int getProgressPercent() {
        return (int) (getProgress() * 100);
    }
    
    /**
     * Kiểm tra đã hoàn tất chưa
     */
    public boolean isComplete() {
        return receivedChunks.cardinality() >= totalChunks;
    }
    
    /**
     * Tốc độ truyền (bytes/s)
     */
    public double getTransferSpeed() {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed <= 0) return 0;
        return (bytesTransferred.get() * 1000.0) / elapsed;
    }
    
    /**
     * Thời gian còn lại (seconds)
     */
    public long getEstimatedTimeRemaining() {
        double speed = getTransferSpeed();
        if (speed <= 0) return -1;
        long remaining = fileSize - bytesTransferred.get();
        return (long) (remaining / speed);
    }
    
    /**
     * Thời gian đã trải qua (seconds)
     */
    public long getElapsedTime() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
    
    // ========== State management ==========
    
    /**
     * Bắt đầu transfer
     */
    public void start() {
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = startTime;
        this.status = TransferStatus.IN_PROGRESS;
    }
    
    /**
     * Tạm dừng transfer
     */
    public void pause() {
        this.status = TransferStatus.PAUSED;
    }
    
    /**
     * Tiếp tục transfer
     */
    public void resume() {
        this.status = TransferStatus.IN_PROGRESS;
    }
    
    /**
     * Hoàn tất transfer
     */
    public void complete() {
        this.status = TransferStatus.COMPLETED;
    }
    
    /**
     * Đánh dấu thất bại
     */
    public void fail(String errorMessage) {
        this.status = TransferStatus.FAILED;
        this.errorMessage = errorMessage;
    }
    
    /**
     * Hủy transfer
     */
    public void cancel() {
        this.status = TransferStatus.CANCELLED;
    }
    
    /**
     * Reset để bắt đầu lại
     */
    public void reset() {
        this.receivedChunks.clear();
        this.bytesTransferred.set(0);
        this.status = TransferStatus.PENDING;
        this.errorMessage = null;
    }
    
    // ========== Chunk calculation helpers ==========
    
    /**
     * Lấy offset của chunk trong file
     */
    public long getChunkOffset(int chunkIndex) {
        return (long) chunkIndex * chunkSize;
    }
    
    /**
     * Lấy kích thước thực của chunk (chunk cuối có thể nhỏ hơn)
     */
    public int getChunkSize(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= totalChunks) return 0;
        
        if (chunkIndex == totalChunks - 1) {
            // Chunk cuối cùng
            int lastChunkSize = (int) (fileSize % chunkSize);
            return lastChunkSize == 0 ? chunkSize : lastChunkSize;
        }
        return chunkSize;
    }
    
    // ========== Getters and Setters ==========
    
    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }
    
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { 
        this.fileSize = fileSize;
        this.totalChunks = calculateTotalChunks(fileSize, chunkSize);
        this.receivedChunks = new BitSet(totalChunks);
    }
    
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { 
        this.chunkSize = chunkSize;
        if (fileSize > 0) {
            this.totalChunks = calculateTotalChunks(fileSize, chunkSize);
            this.receivedChunks = new BitSet(totalChunks);
        }
    }
    
    public int getTotalChunks() { return totalChunks; }
    
    public long getBytesTransferred() { return bytesTransferred.get(); }
    public void setBytesTransferred(long bytes) { this.bytesTransferred.set(bytes); }
    
    public TransferStatus getStatus() { return status; }
    public void setStatus(TransferStatus status) { this.status = status; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public String getSaveDirectory() { return saveDirectory; }
    public void setSaveDirectory(String saveDirectory) { this.saveDirectory = saveDirectory; }
    
    public String getPeerIp() { return peerIp; }
    public void setPeerIp(String peerIp) { this.peerIp = peerIp; }
    
    public int getPeerPort() { return peerPort; }
    public void setPeerPort(int peerPort) { this.peerPort = peerPort; }
    
    public long getStartTime() { return startTime; }
    public long getLastUpdateTime() { return lastUpdateTime; }
    
    @Override
    public String toString() {
        return String.format("TransferState{id=%s, file=%s, size=%d, chunks=%d/%d, progress=%.1f%%, status=%s}",
            transferId, fileName, fileSize, getReceivedChunkCount(), totalChunks, 
            getProgress() * 100, status);
    }
}
