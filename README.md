# AnonPing

**Open-source Android network impairment tool**  
Dark Anonymous theme

## Özellikler

- **Latency** – DelayQueue ile paket bazlı gecikme (gidiş + dönüş)
- **Packet Loss** – Rastgele drop
- **Bandwidth Limiting** – KB/s hız sınırı
- **Floating Bubble** – Latency + Loss + Bandwidth
- **Full UDP Path** – Outbound forward + **Reverse path (NAT + TUN’a geri yazma)**

## Mevcut durum (v0.8)

- [x] Latency / Loss / Bandwidth
- [x] Floating Bubble
- [x] Dark theme
- [x] Outbound UDP forwarding (`protect`)
- [x] **Reverse path** (cevap paketleri NAT ile TUN’a yazılıyor)
- [ ] TCP desteği (userspace stack gerekir)
- [ ] Logo (Image Asset Studio)

## Teknik özet

1. TUN’dan paket oku → Loss → DelayQueue
2. Writer: Bandwidth kontrol → IP parse → gerçek ağa UDP gönder + session kaydet
3. UdpReceiver: Cevap gelince session bul → IP+UDP paketi oluştur → TUN’a yaz

UDP tabanlı trafik (DNS, birçok oyun, WebRTC) artık gerçekten etkileniyor.

## Kurulum

```
https://github.com/furkanmeyra2173-create/AnonPing.git
```

## Lisans

MIT
