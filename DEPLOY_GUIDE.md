# ============================================

# P2P SIGNALING SERVER - HƯỚNG DẪN DEPLOY

# ============================================

## 🎯 SIGNALING SERVER LÀ GÌ?

Signaling Server là "danh bạ điện thoại" cho các peers:

-  Lưu danh sách peers online
-  Giúp peers tìm nhau qua Internet
-  KHÔNG lưu trữ hay trung chuyển file
-  File vẫn truyền P2P trực tiếp giữa 2 máy

```
┌─────────────────────────────────────────────────────────────┐
│                    KIẾN TRÚC P2P HYBRID                     │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│     ┌─────────────────────┐                                 │
│     │  SIGNALING SERVER   │  ← Cloud (Render.com)           │
│     │  (Danh bạ online)   │                                 │
│     └──────────┬──────────┘                                 │
│                │                                            │
│    ┌───────────┼───────────┐                                │
│    │ Register  │  Register │                                │
│    │ + Get     │  + Get    │                                │
│    │ Peers     │  Peers    │                                │
│    ▼           ▼           ▼                                │
│  ┌─────┐   ┌─────┐   ┌─────┐                               │
│  │Peer │◄──┼─────┼──►│Peer │  ← Truyền file P2P trực tiếp  │
│  │  A  │   │     │   │  B  │                                │
│  └─────┘   │     │   └─────┘                                │
│            │     │                                          │
│            ▼     ▼                                          │
│       FILE TRUYỀN TRỰC TIẾP                                 │
│       (Không qua server)                                    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚀 DEPLOY LÊN RENDER.COM (MIỄN PHÍ)

### Bước 1: Push code lên GitHub

```bash
git add .
git commit -m "Add signaling server deployment"
git push origin main
```

### Bước 2: Đăng ký Render.com

1. Vào https://render.com
2. Đăng ký bằng GitHub account
3. Click "New" → "Web Service"
4. Connect GitHub repository

### Bước 3: Cấu hình deployment

-  **Name**: `p2p-signaling-server`
-  **Region**: Singapore (gần Việt Nam)
-  **Branch**: `main`
-  **Runtime**: Docker
-  **Dockerfile Path**: `./Dockerfile.signaling`
-  **Plan**: Free

### Bước 4: Deploy

Click "Create Web Service" và đợi deploy (5-10 phút)

### Bước 5: Lấy URL

Sau khi deploy xong, bạn sẽ có URL như:

```
https://p2p-signaling-server.onrender.com
```

### Bước 6: Cập nhật code

Mở file `SignalingClient.java` và thay đổi:

```java
private static final String DEFAULT_SERVER_HOST = "p2p-signaling-server.onrender.com";
private static final int DEFAULT_SERVER_PORT = 443;  // HTTPS port
```

---

## 🔧 DEPLOY BẰNG DOCKER (TỰ HOST)

Nếu bạn có VPS riêng:

```bash
# Build image
docker build -f Dockerfile.signaling -t p2p-signaling .

# Run container
docker run -d -p 9000:9000 --name signaling p2p-signaling

# Xem logs
docker logs -f signaling
```

---

## ⚠️ LƯU Ý QUAN TRỌNG

### Về Firewall và NAT

Khi dùng qua Internet thật, 2 peers cần:

1. **Public IP** hoặc **Port forwarding** trên router
2. **Firewall mở** ports 10000-10005

Nếu cả 2 peers đều sau NAT (không có public IP), file không thể truyền trực tiếp.

### Giải pháp cho NAT:

1. **STUN/TURN server** - Phức tạp, cần thêm server
2. **Relay qua cloud** - File đi qua server (không còn P2P thuần túy)
3. **VPN** - Cả 2 peers join cùng VPN (Hamachi, ZeroTier, Tailscale)

---

## 📋 CHECKLIST DEPLOY

-  [ ] Push code lên GitHub
-  [ ] Tạo tài khoản Render.com
-  [ ] Connect GitHub repo
-  [ ] Deploy Signaling Server
-  [ ] Cập nhật URL trong SignalingClient.java
-  [ ] Build lại app
-  [ ] Test kết nối

---

## 🆘 TROUBLESHOOTING

### "Connect timed out"

-  Signaling Server chưa chạy
-  URL sai
-  Firewall block

### "Không tìm thấy peers"

-  Cả 2 peers chưa đăng ký với server
-  Server restart (mất danh sách peers)

### "File không truyền được"

-  Peers sau NAT không có public IP
-  Cần port forwarding hoặc VPN
