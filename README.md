# AnonPing

**Open-source Android network impairment tool**  
Dark Anonymous theme

## Özellikler

- **Latency** – DelayQueue ile paket bazlı gecikme
- **Packet Loss** – Rastgele drop (0-50%)
- **Bandwidth Limiting** – KB/s hız sınırı
- **Floating Bubble** – Latency + Loss + Bandwidth kontrolleri
- **Outbound Forwarding (UDP)** – `protect()` + IP parse ile gerçek ağa çıkış

## Mevcut durum (v0.7)

- [x] Latency / Loss / Bandwidth
- [x] Floating Bubble (full controls)
- [x] Dark theme
- [x] **Gerçek outbound UDP forwarding** (DNS, oyunlar, WebRTC vb.)
- [ ] TCP forwarding (userspace stack / tun2socks gerekir)
- [ ] Reverse path (cevap paketlerini düzgün NAT + TUN’a yazma)
- [ ] Logo (Image Asset Studio)

## Teknik not

- Outbound UDP paketleri artık gerçek internete gidiyor (`VpnService.protect` sayesinde loop olmuyor).
- TCP hâlâ desteklenmiyor (full TCP stack lazım).
- Cevap paketlerinin TUN’a geri yazılması bir sonraki adım.

## Kurulum

```
https://github.com/furkanmeyra2173-create/AnonPing.git
```

## Lisans

MIT
