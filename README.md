# AnonPing

**Open-source Android network impairment tool**

AnonPing, uygulamaları ve oyunları zayıf / kararsız ağ koşullarında test etmek için kullanılan yerel VPN tabanlı bir lag simülatörüdür.

## Özellikler

- **Latency (gecikme)** → `DelayQueue` ile paket bazlı gerçekçi gecikme
- Packet loss (yakında)
- Bandwidth limiting (yakında)
- Floating control bubble (yakında)
- Tamamen yerel, veri toplama yok

## Teknik yaklaşım

- Android `VpnService` + TUN interface
- **Reader thread**: TUN'dan paketleri okur → `DelayedPacket` olarak `DelayQueue`'ya koyar
- **Writer thread**: Süresi dolmuş paketleri queue'dan alıp yazar
- Latency atomik olarak değiştirilebilir
- Queue boyutu limiti ile backpressure (max 2000 paket)

## Mevcut durum (v0.1)

- [x] Proje iskeleti
- [x] MainActivity + SeekBar
- [x] **Packet queue tabanlı latency** (`DelayQueue` + ayrı reader/writer thread)
- [ ] Packet loss
- [ ] Floating bubble
- [ ] Gerçek outbound forwarding (şu an eğitim amaçlı model)

## Kurulum

1. Android Studio → **File → New → Project from Version Control**
2. Repo adresi:
   ```
   https://github.com/furkanmeyra2173-create/AnonPing.git
   ```
3. Gradle sync
4. Eksik `mipmap` icon'ları ekle
5. Çalıştır → VPN izni ver → latency ayarla → Start

## Not

Latency artık `Thread.sleep` yerine düzgün packet queue ile çalışıyor.  
Her paket kendi release zamanına göre sıraya giriyor.

## Lisans

MIT

---

Sıradaki özellik ne olsun?
- Packet loss
- Floating bubble
- Daha iyi UI
- Yoksa başka?
