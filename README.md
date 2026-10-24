# AnonPing

**Open-source Android network impairment tool**  
Dark neon Anonymous style

## Özellikler

- **Latency** – DelayQueue (gidiş + dönüş)
- **Packet Loss** – Rastgele drop
- **Bandwidth Limiting** – KB/s
- **Floating Bubble** – Latency + Loss + Bandwidth
- **Full UDP Path** – Outbound + Reverse (NAT)
- **Modern UI** – Dark cards, neon green, animations

## APK derleme (GitHub Actions)

Her `main` push’unda otomatik derlenir.

1. Repo → **Actions** sekmesi
2. **Build AnonPing APK** workflow’unu aç
3. En son run → **Artifacts** → `AnonPing-debug` indir

Manuel tetiklemek için:
- Actions → Build AnonPing APK → **Run workflow**

## Mevcut durum (v0.9)

- [x] Latency / Loss / Bandwidth
- [x] Floating Bubble
- [x] Full UDP path
- [x] Modern UI
- [x] GitHub Actions APK build
- [ ] TCP (tun2socks gerekir)
- [ ] Özel Anonymous logo (Image Asset)

## Kurulum

```
https://github.com/furkanmeyra2173-create/AnonPing.git
```

## Lisans

MIT
