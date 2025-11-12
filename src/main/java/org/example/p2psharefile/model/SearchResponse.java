package org.example.p2psharefile.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Module 1: SearchResponse - Kết quả tìm kiếm file
 * Chứa danh sách các file tìm thấy và thông tin peer có file đó
 */
public class SearchResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;           // ID của yêu cầu tìm kiếm
    private PeerInfo sourcePeer;        // Peer có file
    private List<FileInfo> foundFiles;  // Danh sách file tìm thấy
    
    public SearchResponse(String requestId, PeerInfo sourcePeer) {
        this.requestId = requestId;
        this.sourcePeer = sourcePeer;
        this.foundFiles = new ArrayList<>();
    }
    
    public SearchResponse(String requestId, PeerInfo sourcePeer, List<FileInfo> foundFiles) {
        this.requestId = requestId;
        this.sourcePeer = sourcePeer;
        this.foundFiles = foundFiles;
    }
    
    // Getters và Setters
    public String getRequestId() {
        return requestId;
    }
    
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
    
    public PeerInfo getSourcePeer() {
        return sourcePeer;
    }
    
    public void setSourcePeer(PeerInfo sourcePeer) {
        this.sourcePeer = sourcePeer;
    }
    
    public List<FileInfo> getFoundFiles() {
        return foundFiles;
    }
    
    public void setFoundFiles(List<FileInfo> foundFiles) {
        this.foundFiles = foundFiles;
    }
    
    public void addFile(FileInfo file) {
        this.foundFiles.add(file);
    }
    
    public boolean hasFiles() {
        return foundFiles != null && !foundFiles.isEmpty();
    }
    
    @Override
    public String toString() {
        return "SearchResponse{" +
                "requestId='" + requestId + '\'' +
                ", sourcePeer=" + sourcePeer +
                ", filesCount=" + foundFiles.size() +
                '}';
    }
}
