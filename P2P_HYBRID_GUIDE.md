# 🌐 P2P Hybrid (Internet) - Hướng Dẫn Sử Dụng

## Tổng Quan

P2P Hybrid là chế độ kết nối qua Internet sử dụng **Signaling Server** để điều phối kết nối giữa các peers. Khác với mô hình Client-Server truyền thống, Signaling Server **KHÔNG** lưu trữ hay trung chuyển file.

### So sánh với P2P LAN

| Tính năng      | P2P LAN              | P2P Hybrid (Internet)   |
| -------------- | -------------------- | ----------------------- |
| Phạm vi        | Mạng cục bộ (LAN)    | Internet toàn cầu       |
| Khám phá peers | Multicast UDP        | Qua Signaling Server    |
| Truyền file    | P2P trực tiếp        | P2P trực tiếp           |
| PIN Share      | LAN broadcast        | Signaling Server        |
| Tìm kiếm       | Gửi đến tất cả peers | Gửi đến peers từ server |

## Kiến Trúc

```
┌─────────────────────────────────────────────────────────────┐
│                    SIGNALING SERVER                          │
│                    (Port 9000 - TLS)                         │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  Chức năng:                                          │    │
│  │  • Lưu danh sách peers online                        │    │
│  │  • Quản lý heartbeat (30s interval)                  │    │
│  │  • Lưu và tra cứu PIN codes                          │    │
│  │  • KHÔNG lưu file, KHÔNG trung chuyển file           │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
           ▼               ▼               ▼
      ┌─────────┐     ┌─────────┐     ┌─────────┐
      │ Peer A  │────▶│ Peer B  │     │ Peer C  │
      │ Client  │ P2P │ Client  │     │ Client  │
      └─────────┘◀────└─────────┘     └─────────┘
           │               │               │
           └───────────────┴───────────────┘
                    P2P Direct Transfer
```

## Cách Sử Dụng

### 1. Khởi động Signaling Server (trên máy chủ)

```bash
# Cách 1: Chạy từ IDE
# Run class: org.example.p2psharefile.signaling.StandaloneSignalingServer

# Cách 2: Chạy từ JAR
java -cp p2p-sharefile.jar org.example.p2psharefile.signaling.StandaloneSignalingServer 9000
```

**Output mẫu:**

```
╔══════════════════════════════════════════════════════════╗
║     P2P SHARE FILE - SIGNALING SERVER (STANDALONE)       ║
║                                                          ║
║  Mô hình: P2P Hybrid                                     ║
║  Server này chỉ điều phối kết nối, không lưu trữ file    ║
╚══════════════════════════════════════════════════════════╝

✅ Signaling Server đã khởi động trên port 9000
📌 Nhấn Ctrl+C để dừng server...
```

### 2. Cấu hình Client

Trong ứng dụng P2P Share File:

1. **Chuyển sang chế độ Internet** - Nhấn toggle button "Internet (P2P)"
2. Ứng dụng sẽ tự động kết nối đến Signaling Server mặc định

**Để cấu hình Signaling Server tùy chỉnh (trong code):**

```java
p2pService.setSignalingServerAddress("your-server-ip", 9000);
```

### 3. Sử dụng các chức năng

#### Chia sẻ file qua PIN

1. Chọn file để chia sẻ
2. Nhấn "Tạo mã PIN"
3. Gửi mã PIN cho người nhận
4. Người nhận nhập PIN và download

#### Tìm kiếm file

1. Nhập từ khóa tìm kiếm
2. Nhấn "Tìm kiếm"
3. Kết quả sẽ hiển thị file từ tất cả peers online
4. Chọn file và download

#### Preview file

1. Chọn file từ kết quả tìm kiếm
2. Nhấn "Xem trước"
3. Preview sẽ được tải từ peer sở hữu file

## Các Protocol Messages

### Client → Server

| Message Type   | Mô tả                               |
| -------------- | ----------------------------------- |
| `REGISTER`     | Đăng ký peer với server             |
| `UNREGISTER`   | Hủy đăng ký khi ngắt kết nối        |
| `HEARTBEAT`    | Gửi mỗi 30s để thông báo còn online |
| `GET_PEERS`    | Lấy danh sách peers online          |
| `REGISTER_PIN` | Đăng ký mã PIN share                |
| `LOOKUP_PIN`   | Tìm kiếm thông tin PIN              |

### Server → Client

| Message Type    | Mô tả                             |
| --------------- | --------------------------------- |
| `REGISTER_OK`   | Xác nhận đăng ký thành công       |
| `PEER_LIST`     | Danh sách peers online            |
| `PIN_OK`        | Xác nhận đăng ký PIN              |
| `PIN_INFO`      | Thông tin file được share qua PIN |
| `PIN_NOT_FOUND` | Không tìm thấy PIN                |
| `PIN_EXPIRED`   | PIN đã hết hạn                    |

## Bảo Mật

### TLS Encryption

-  Tất cả kết nối đều được mã hóa TLS
-  Server và client đều có certificate tự ký

### ECDSA Signatures

-  Mỗi peer có cặp khóa ECDSA
-  Tất cả messages đều được ký bởi peer gửi
-  Receiver xác minh chữ ký trước khi xử lý

### AES-256 Encryption

-  File data được mã hóa AES-256 trước khi truyền
-  Key được trao đổi an toàn qua TLS

## Triển Khai Signaling Server

### Yêu cầu

-  Java 17+
-  Port 9000 mở (hoặc port tùy chỉnh)
-  RAM: tối thiểu 256MB


## Troubleshooting

### 1. Không kết nối được Signaling Server

-  Kiểm tra firewall mở port 9000
-  Kiểm tra server có đang chạy không
-  Kiểm tra IP/hostname đúng

### 2. Không tìm thấy peers

-  Đảm bảo peers đã đăng ký với cùng Signaling Server
-  Kiểm tra heartbeat có gửi đều đặn không

### 3. PIN không hoạt động

-  Kiểm tra PIN chưa hết hạn (5 phút mặc định)
-  Đảm bảo peer share file vẫn online

### 4. Download chậm

-  Kiểm tra tốc độ mạng của cả 2 peers
-  File truyền P2P trực tiếp, không qua server

## API Reference

### P2PService

```java
// Cấu hình Signaling Server
p2pService.setSignalingServerAddress(String host, int port);

// Kiểm tra kết nối
boolean connected = p2pService.isSignalingConnected();

// Lấy SignalingClient
SignalingClient client = p2pService.getSignalingClient();
```

### SignalingClient

```java
// Kết nối
client.connect();

// Ngắt kết nối
client.disconnect();

// Lấy danh sách peers
client.refreshPeerList();

// Đăng ký PIN
client.registerPIN(pin, session);

// Tìm PIN
SharePINInfo info = client.lookupPIN(pin);
```

## Liên Hệ

Nếu có thắc mắc về P2P Hybrid, vui lòng liên hệ team phát triển.

---

**Version:** 1.0  
**Last Updated:** 2024
