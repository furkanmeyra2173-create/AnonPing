# AnonPing

**Open-source Android network impairment tool**  
Dark neon Anonymous style

## Özellikler

- **Latency** – DelayQueue (gidiş + dönüş)
- **Packet Loss** – Rastgele drop
- **Bandwidth Limiting** – KB/s
- **Floating Bubble** – Latency + Loss + Bandwidth
- **Full UDP Path** – Outbound + Reverse (NAT)
- **Modern UI** – Dark cards, neon green, entrance animations, status pulse

## Mevcut durum (v0.9)

- [x] Latency / Loss / Bandwidth
- [x] Floating Bubble
- [x] Full UDP path (outbound + reverse)
- [x] Modern animated dark UI
- [ ] **TCP** – requires tun2socks / lwIP (not pure Kotlin)
- [ ] Logo (Image Asset Studio)

## TCP hakkında

Tam TCP desteği için userspace TCP stack gerekir (sequence numbers, retransmit, congestion control…).  
Bunu temiz yapmak için genellikle **tun2socks** veya **lwIP** gibi native kütüphaneler kullanılır.  
Saf Kotlin ile yazmak hem çok büyük hem de hataya açık olur.  
İleride native modül eklenebilir.

## Kurulum

```
https://github.com/furkanmeyra2173-create/AnonPing.git
```

## Lisans

MIT
