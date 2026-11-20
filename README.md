# RideCom — Offline Motorcycle Helmet Intercom

Ultra-low-latency, 100% offline, full-duplex intercom between **Android** and **iPhone** over local Wi-Fi Hotspot — built for Bluetooth helmet headsets without built-in intercom, with true lock-screen background support and **zero Apple Developer fees**.

## Architecture
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full system design and binary protocol specification.

- **Android (`android/`):** Kotlin + Jetpack Compose + Low-Latency `AudioRecord`/`AudioTrack` + Bluetooth HFP SCO + `Concentus` Opus (16 kHz VoIP, In-band FEC & PLC) + `ForegroundService` (`PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_LOW_LATENCY`).
- **iOS (`ios/`):** Swift + SwiftUI + `AVAudioEngine` (`VoiceProcessingIO`) + `alta/swift-opus` + `UIBackgroundModes: audio` + Bonjour (`_ridecom._udp`) + POSIX UDP (zero paid entitlements — 100% Free Apple ID / SideStore compatible).

## Automated Free Build (No Mac Required)
Push this repository to GitHub (public or private). The workflow in `.github/workflows/build.yml` automatically builds:
1. **`RideCom-Android-APK`** (`app-debug.apk`)
2. **`RideCom-iOS-Unsigned-IPA`** (`RideCom-unsigned.ipa` ready for SideStore / AltStore)

### Installing on Devices
- **Android (Samsung Galaxy A12 via `adb`):**
  ```powershell
  adb install -r app-debug.apk
  ```
- **iPhone (iPhone 11 via SideStore):**
  1. Download `RideCom-unsigned.ipa` from GitHub Actions Artifacts onto your iPhone.
  2. Open **SideStore** → tap `+` → select `RideCom-unsigned.ipa`.
  3. On first launch, allow **Microphone** and **Local Network** permissions.

## Quick Usage Guide
1. Turn on **Mobile Hotspot** on one phone (no mobile data/internet needed) and connect the other phone to that Wi-Fi.
   - *Note for Galaxy A12 (`SM-A127F`):* The A12 uses 2.4 GHz Wi-Fi. If hosting from iPhone 11, enable **"Maximize Compatibility"** in Personal Hotspot settings.
2. Connect your Bluetooth helmet headsets to each phone.
3. Open **RideCom** on both phones:
   - Host phone: tap **Start Listening (Hotspot Host)**.
   - Client phone: tap the auto-discovered rider, or tap **Connect to Hotspot Host (`x.x.x.1`)**.
4. Adjust the **Wind Gate (VOX)** slider while speaking so wind noise stays muted and speech opens cleanly, then lock your screen and ride!
