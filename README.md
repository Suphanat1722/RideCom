<p align="center">
  <img src="assets/ridecom-logo.png" width="140" alt="RideCom Logo" />
</p>

# RideCom v1.0 — Offline Motorcycle Helmet Intercom

Ultra-low-latency, 100% offline, full-duplex intercom between **Android**, **iPhone**, and **Windows PC** over local Wi-Fi Hotspot — built for Bluetooth helmet headsets without built-in intercom, with true lock-screen background operation and **zero Apple Developer fees**.

## Features (v1.0.0)
- **Native C `libopus` (v1.5.2) + Hardware-Clock Audio:** ~0.2–0.4 ms/frame on Android (zero JIT warmup stutter), `AVAudioEngine` (`VoiceProcessingIO`) on iOS, and pull-driven `JitterWaveProvider` on Windows PC.
- **100% Offline Instant Auto-Heal:** Stateless Unicast UDP (`:50000`) + Bonjour/mDNS auto-discovery + 1-Tap Hotspot Host & 1-Tap Reconnect to Last Rider.
- **In-Helmet Audio Cues:** Soft synthesized chimes on connect, disconnect, and mute so you know your status with the phone locked in your pocket.
- **RideDJ Shared Music + Auto Voice Ducking:** Share local MP3/M4A/WAV songs with your riding partner in sync — music smoothly ducks to 25% whenever either rider speaks.
- **GPS & Background Coexistence:** Plays nicely with Google Maps navigation prompts (`.mixWithOthers` on iOS) and auto-switches when your Bluetooth helmet headset connects (`AudioDeviceCallback` on Android).
- **Glove-Friendly OLED Cockpit UI:** Giant animated pulsing Mic/PTT button, live RTT/Loss/Buffer telemetry, and real-time Wind Gate (VOX) tuning.

## Automated Free Builds (GitHub Actions)
Every push to `main` builds:
1. **`RideCom-Android-APK`** (`app-debug.apk` with Native C `libopus` for `arm64-v8a` & `armeabi-v7a`)
2. **`RideCom-iOS-Unsigned-IPA`** (`RideCom-unsigned.ipa` ready for SideStore / Sideloadly)
3. **`RideCom-Windows-PC`** (Standalone single-file `RideComPC.exe`)

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full technical design and RideCom Protocol v1 specification.
