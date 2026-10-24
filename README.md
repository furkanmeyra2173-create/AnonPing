# AnonPing

**Open-source Android network impairment tool**  
Logo: Classic Anonymous style

AnonPing, uygulamaları ve oyunları zayıf / kararsız ağ koşullarında test etmek için kullanılan yerel VPN tabanlı bir lag simülatörüdür.

## Özellikler

- **Latency** → `DelayQueue` ile paket bazlı gecikme
- **Packet Loss** → Rastgele paket düşürme (0-50%)
- **Floating Bubble** → Sürüklenebilir + **tıklayınca açılan panel** (latency + loss ayarı + Uygula)
- Bandwidth limiting (yakında)
- Tamamen yerel, veri toplama yok

## Floating Bubble kullanımı

1. "Floating Bubble Aç" butonuna bas
2. Overlay izni ver
3. Bubble’ı **sürükleyebilirsin**
4. **Kısa tık** → panel açılır/kapanır
5. Panelde latency ve packet loss ayarla
6. **Uygula / Toggle** → yeni ayarlarla VPN’i başlatır

## Mevcut durum (v0.4)

- [x] Latency + Packet Loss
- [x] Packet queue
- [x] Floating Bubble (expandable controls)
- [ ] Bandwidth limiting
- [ ] Özel logo (mipmap’lere eklenmeli)
- [ ] Gerçek outbound forwarding

## Logo

Senin gönderdiğin Anonymous logosu uygulama ikonu olacak.  
Android Studio’da `res/mipmap-*` klasörlerine ilgili boyutlarda ekle (veya Image Asset Studio ile oluştur).

## Kurulum

```
https://github.com/furkanmeyra2173-create/AnonPing.git
```

## Lisans

MIT
