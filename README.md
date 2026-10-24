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
- Foreground Service + SYSTEM_ALERT_WINDOW ile floating UI (henüz eklenmedi)

## Mevcut durum

- Proje iskeleti hazır
- MainActivity + SeekBar ile latency seçimi
- Temel `AnonVpnService` (şu an kaba `Thread.sleep` ile demo latency)
- Gerçek packet-level delay/loss için geliştirme gerekli

## Kurulum

1. Android Studio aç
2. **File → New → Project from Version Control** ile şu repoyu klonla:
   ```
   https://github.com/furkanmeyra2173-create/AnonPing.git
   ```
3. Gradle sync yap (wrapper eksikse Android Studio otomatik oluşturur)
4. Eksik icon’lar için Android Studio’nun varsayılan launcher icon’larını ekle veya `res/mipmap` oluştur
5. Emulator veya gerçek cihazda çalıştır (VPN izni isteyecek)

## Sonraki adımlar (birlikte yapabiliriz)

- Gerçek packet queue + scheduled delay
- Packet loss oranı
- Floating bubble
- Daha iyi notification ve UI
- Per-app routing (ileride)

## Lisans

MIT

---

Katkıda bulunmak istersen issue aç veya PR gönder.  
Hangi özelliği önce ekleyelim?
