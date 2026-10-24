# AnonPing

**Open-source Android network impairment tool**

AnonPing, uygulamaları ve oyunları zayıf / kararsız ağ koşullarında test etmek için kullanılan yerel VPN tabanlı bir lag simülatörüdür.

## Özellikler

- **Latency** → `DelayQueue` ile paket bazlı gecikme
- **Packet Loss** → Rastgele paket düşürme (0-50%)
- **Floating Bubble** → Ekran üstü sürüklenen hızlı aç/kapa butonu
- Bandwidth limiting (yakında)
- Tamamen yerel, veri toplama yok

## Teknik yaklaşım

- Android `VpnService` + TUN interface
- Reader / Writer thread + `DelayQueue`
- Packet loss: rastgele drop
- Floating overlay (`TYPE_APPLICATION_OVERLAY`) + sürükle-bırak

## Mevcut durum (v0.3)

- [x] Proje iskeleti
- [x] Latency + Packet Loss SeekBar
- [x] Packet queue tabanlı latency
- [x] Packet loss
- [x] **Floating Bubble** (sürüklenebilir, tıklayınca VPN toggle)
- [ ] Bandwidth limiting
- [ ] Bubble üzerinden latency/loss ayarı
- [ ] Gerçek outbound forwarding

## Kullanım

1. Android Studio ile klonla:
   ```
   https://github.com/furkanmeyra2173-create/AnonPing.git
   ```
2. Gradle sync + icon ekle
3. Çalıştır
4. **Overlay izni** ver (Floating Bubble için)
5. VPN izni ver
6. "Floating Bubble Aç" butonuna bas → ekranda küçük bubble çıkar
7. Bubble’ı sürükleyebilirsin, tıklayınca VPN açılır/kapanır

## Lisans

MIT

---

Sıradaki?
- Bubble’a latency/loss ayarı eklemek
- Bandwidth limiting
- Daha güzel UI / ikonlar
