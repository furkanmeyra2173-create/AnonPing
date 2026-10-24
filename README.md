# AnonPing

**Open-source Android network impairment tool**

AnonPing, uygulamaları ve oyunları zayıf / kararsız ağ koşullarında test etmek için kullanılan yerel VPN tabanlı bir lag simülatörüdür.

## Özellikler

- **Latency** → `DelayQueue` ile paket bazlı gecikme
- **Packet Loss** → Rastgele paket düşürme (0-50%)
- Bandwidth limiting (yakında)
- Floating control bubble (yakında)
- Tamamen yerel, veri toplama yok

## Teknik yaklaşım

- Android `VpnService` + TUN interface
- **Reader thread**:
  1. TUN'dan paket okur
  2. Packet loss oranına göre rastgele drop eder
  3. Kalan paketleri `DelayedPacket` olarak `DelayQueue`'ya koyar
- **Writer thread**: Süresi dolan paketleri yazar
- Latency ve Loss atomik olarak değiştirilebilir
- Queue boyutu limiti ile backpressure (max 2000 paket)

## Mevcut durum (v0.2)

- [x] Proje iskeleti
- [x] MainActivity + Latency + Packet Loss SeekBar
- [x] Packet queue tabanlı latency
- [x] **Packet loss**
- [ ] Floating bubble
- [ ] Bandwidth limiting
- [ ] Gerçek outbound forwarding

## Kurulum

1. Android Studio → **File → New → Project from Version Control**
2. Repo:
   ```
   https://github.com/furkanmeyra2173-create/AnonPing.git
   ```
3. Gradle sync
4. Eksik icon'ları ekle
5. Çalıştır → VPN izni ver → ayarları yap → Start

## Lisans

MIT

---

Sıradaki özellik?
- Floating bubble
- Bandwidth limiting
- Daha güzel UI
- Başka?
