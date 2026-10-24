# AnonPing

**Open-source Android network impairment tool**  
Dark Anonymous theme + classic logo style

## Özellikler

- **Latency** – DelayQueue ile paket bazlı gecikme
- **Packet Loss** – Rastgele drop (0-50%)
- **Bandwidth Limiting** – KB/s cinsinden hız sınırı (0 = unlimited)
- **Floating Bubble** – Sürüklenebilir + genişleyen panel (**Latency + Loss + Bandwidth** + Uygula)
- Tamamen yerel, veri toplama yok

## Mevcut durum (v0.6)

- [x] Latency
- [x] Packet Loss
- [x] Bandwidth Limiting
- [x] Floating Bubble (full controls)
- [x] Dark neon-green theme
- [ ] Logo mipmap’lere manuel eklenmeli (Image Asset Studio)
- [ ] Gerçek outbound forwarding (ileride)

## Logo

Gönderdiğin Anonymous logosu için:
1. Android Studio → `app` sağ tık → **New → Image Asset**
2. Foreground image olarak logoyu seç
3. Generate → tüm boyutlar otomatik oluşur

## Kullanım

```
https://github.com/furkanmeyra2173-create/AnonPing.git
```

1. Klonla + Gradle sync
2. Overlay + VPN izni ver
3. Ayarları yap → Start veya Floating Bubble kullan

## Lisans

MIT
