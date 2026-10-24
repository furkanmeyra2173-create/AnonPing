# AnonPing

**Open-source Android network impairment tool**

AnonPing, uygulamaları ve oyunları zayıf / kararsız ağ koşullarında test etmek için kullanılan yerel VPN tabanlı bir lag simülatörüdür.

## Özellikler (hedef)

- Latency (gecikme) ekleme
- Packet loss (paket kaybı) simülasyonu
- Bandwidth limiting
- Floating control bubble (ekran üstü kontrol)
- Uygulama bazlı hedefleme (ileride)
- Tamamen yerel, veri toplama yok

## Neden bu proje?

Ticari araçların kapalı kaynaklı olmasından dolayı, tamamen açık kaynaklı, özgür ve eğitici bir alternatif oluşturmak için başlatıldı.

## Teknik yaklaşım

- Android `VpnService` kullanarak trafik yakalama
- Basit packet queue + delay mekanizması ile latency
- Random drop ile packet loss
- Foreground Service + SYSTEM_ALERT_WINDOW ile floating UI

## Durum

Şu anda iskelet aşamasında. Temel proje yapısı ve VpnService iskeleti ekleniyor.

## Kurulum

1. Android Studio ile aç
2. Gradle sync
3. Emulator veya gerçek cihazda çalıştır (VPN izni gerekir)

## Lisans

MIT (veya istediğin lisans)

---

Katkıda bulunmak istersen issue aç veya PR gönder.
