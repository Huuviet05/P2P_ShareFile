package org.example.p2psharefile.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Module 1: PeerInfo - Lưu thông tin về một peer trong mạng P2P
 * Mỗi peer có: ID, địa chỉ IP, port, tên hiển thị
 */
public class PeerInfo implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String peerId;          // ID duy nhất của peer
    private String ipAddress;       // Địa chỉ IP
    private int port;               // Port TCP để truyền file
    private String displayName;     // Tên hiển thị
    private long lastSeen;          // Thời gian gần nhất thấy peer này
    
    public PeerInfo(String peerId, String ipAddress, int port, String displayName) {
        this.peerId = peerId;
        this.ipAddress = ipAddress;
        this.port = port;
        this.displayName = displayName;
        this.lastSeen = System.currentTimeMillis();
    }
    
    // Getters và Setters
    public String getPeerId() {
        return peerId;
    }
    
    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }
    
    public String getIpAddress() {
        return ipAddress;
    }
    
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public long getLastSeen() {
        return lastSeen;
    }
    
    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }
    
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerInfo peerInfo = (PeerInfo) o;
        return Objects.equals(peerId, peerInfo.peerId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(peerId);
    }
    
    @Override
    public String toString() {
        return displayName + " (" + ipAddress + ":" + port + ")";
    }
}
