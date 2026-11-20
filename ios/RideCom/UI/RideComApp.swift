import SwiftUI
import UniformTypeIdentifiers

private enum Theme {
    static let bgCanvas = Color(red: 0.03, green: 0.04, blue: 0.06)
    static let cardBg = Color(red: 0.07, green: 0.09, blue: 0.12)
    static let cardBorder = Color(red: 0.12, green: 0.16, blue: 0.23)
    static let emerald = Color(red: 0.0, green: 0.90, blue: 0.46)
    static let cyan = Color(red: 0.0, green: 0.82, blue: 1.0)
    static let amber = Color(red: 1.0, green: 0.70, blue: 0.0)
    static let red = Color(red: 1.0, green: 0.28, blue: 0.34)
    static let textSecondary = Color(red: 0.55, green: 0.60, blue: 0.67)
}

@main
struct RideComApp: App {
    @StateObject private var controller = IntercomController()

    var body: some Scene {
        WindowGroup {
            RootView(controller: controller)
                .preferredColorScheme(.dark)
        }
    }
}

private struct RootView: View {
    @ObservedObject var controller: IntercomController
    @State private var showSettings = false
    @State private var manualIp = ""

    var body: some View {
        ZStack {
            Theme.bgCanvas.ignoresSafeArea()

            if showSettings {
                SettingsView(controller: controller, onBack: { showSettings = false })
            } else if controller.active {
                ActiveCallView(controller: controller, onOpenSettings: { showSettings = true })
            } else {
                LobbyView(
                    controller: controller,
                    discovery: controller.discovery,
                    manualIp: $manualIp,
                    onOpenSettings: { showSettings = true }
                )
            }
        }
        .onAppear {
            if manualIp.isEmpty && !controller.lastPeerIp.isEmpty {
                manualIp = controller.lastPeerIp
            }
        }
    }
}

private struct LobbyView: View {
    @ObservedObject var controller: IntercomController
    @ObservedObject var discovery: PeerDiscovery
    @Binding var manualIp: String
    let onOpenSettings: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Branded Header
                HStack(spacing: 12) {
                    Image("RideComLogo")
                        .resizable()
                        .scaledToFill()
                        .frame(width: 48, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Theme.emerald.opacity(0.45), lineWidth: 1)
                        )

                    VStack(alignment: .leading, spacing: 2) {
                        Text("RideCom")
                            .font(.system(size: 24, weight: .heavy))
                            .foregroundColor(.white)
                        Text("v1.0 • Native Opus Intercom")
                            .font(.caption.bold())
                            .foregroundColor(Theme.cyan)
                    }
                    Spacer()
                    Button(action: { controller.refreshNetwork() }) {
                        Image(systemName: "arrow.clockwise")
                            .font(.title3)
                            .foregroundColor(.white)
                            .padding(8)
                    }
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            .padding(8)
                    }
                }

                // Network Status Card
                HStack(spacing: 12) {
                    ZStack {
                        Circle()
                            .fill((controller.localIp != nil ? Theme.emerald : Theme.red).opacity(0.15))
                            .frame(width: 40, height: 40)
                        Image(systemName: "wifi")
                            .foregroundColor(controller.localIp != nil ? Theme.emerald : Theme.red)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(controller.localIp ?? "No Wi-Fi / Hotspot")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                        Text("UDP :50000 • 16kHz VoIP")
                            .font(.caption)
                            .foregroundColor(Theme.textSecondary)
                    }
                    Spacer()
                    Text(controller.localIp != nil ? "READY" : "OFFLINE")
                        .font(.caption2.bold())
                        .foregroundColor(controller.localIp != nil ? Theme.emerald : Theme.red)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background((controller.localIp != nil ? Theme.emerald : Theme.red).opacity(0.16))
                        .clipShape(Capsule())
                }
                .padding(14)
                .background(Theme.cardBg)
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.cardBorder, lineWidth: 1))
                .cornerRadius(16)

                if let err = controller.errorMessage {
                    Text(err)
                        .font(.caption)
                        .foregroundColor(Theme.red)
                }

                // 1-Tap Reconnect Card
                if !controller.lastPeerIp.isEmpty {
                    Button(action: { controller.start(targetIp: controller.lastPeerIp) }) {
                        HStack {
                            Image(systemName: "clock.arrow.circlepath")
                                .foregroundColor(Theme.cyan)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Reconnect: \(controller.lastPeerName.isEmpty ? controller.lastPeerIp : controller.lastPeerName)")
                                    .font(.subheadline.bold())
                                    .foregroundColor(.white)
                                Text(controller.lastPeerIp)
                                    .font(.caption)
                                    .foregroundColor(Theme.textSecondary)
                            }
                            Spacer()
                            Text("JOIN")
                                .font(.caption.bold())
                                .foregroundColor(.black)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Theme.cyan)
                                .cornerRadius(8)
                        }
                        .padding(14)
                        .background(Theme.cardBg)
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.cyan.opacity(0.45), lineWidth: 1))
                        .cornerRadius(16)
                    }
                }

                // Primary Host Action
                Button(action: { controller.start(targetIp: nil) }) {
                    Label("Start Hosting (Wait for Rider)", systemImage: "dot.radiowaves.left.and.right")
                        .font(.headline.bold())
                        .foregroundColor(.black)
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .background(Theme.emerald)
                        .cornerRadius(16)
                }

                if let gw = PeerDiscovery.guessHotspotGateway(localIp: controller.localIp) {
                    Button(action: { controller.start(targetIp: gw) }) {
                        Label("Connect to Hotspot Host (\(gw))", systemImage: "wifi.router")
                            .font(.subheadline.bold())
                            .foregroundColor(Theme.emerald)
                            .frame(maxWidth: .infinity)
                            .frame(height: 48)
                            .overlay(
                                RoundedRectangle(cornerRadius: 14)
                                    .stroke(Theme.emerald.opacity(0.55), lineWidth: 1)
                            )
                    }
                }

                Text("NEARBY RIDERS")
                    .font(.caption.bold())
                    .tracking(1)
                    .foregroundColor(Theme.textSecondary)

                if discovery.peers.isEmpty {
                    Text("Scanning local Wi-Fi via Bonjour/mDNS...")
                        .font(.caption)
                        .foregroundColor(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(Theme.cardBg)
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardBorder, lineWidth: 1))
                        .cornerRadius(14)
                } else {
                    ForEach(discovery.peers) { peer in
                        Button(action: { controller.start(targetIp: peer.host) }) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(peer.name)
                                        .font(.headline)
                                        .foregroundColor(.white)
                                    Text("\(peer.host):\(peer.port)")
                                        .font(.caption)
                                        .foregroundColor(Theme.textSecondary)
                                }
                                Spacer()
                                Text("CONNECT")
                                    .font(.caption.bold())
                                    .foregroundColor(Theme.emerald)
                            }
                            .padding(16)
                            .background(Theme.cardBg)
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.emerald.opacity(0.4), lineWidth: 1))
                            .cornerRadius(14)
                        }
                    }
                }

                // Direct IP Card
                HStack(spacing: 8) {
                    TextField("Direct Peer IP (e.g. 192.168.43.1)", text: $manualIp)
                        .keyboardType(.numbersAndPunctuation)
                        .padding(12)
                        .background(Theme.bgCanvas)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Theme.cardBorder, lineWidth: 1))
                        .cornerRadius(10)
                        .foregroundColor(.white)

                    Button(action: {
                        let trimmed = manualIp.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !trimmed.isEmpty { controller.start(targetIp: trimmed) }
                    }) {
                        Text("Join")
                            .font(.headline.bold())
                            .foregroundColor(.black)
                            .padding(.horizontal, 18)
                            .frame(height: 46)
                            .background(Theme.emerald)
                            .cornerRadius(10)
                    }
                }
                .padding(12)
                .background(Theme.cardBg)
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.cardBorder, lineWidth: 1))
                .cornerRadius(16)
            }
            .padding(20)
        }
    }
}

private struct ActiveCallView: View {
    @ObservedObject var controller: IntercomController
    let onOpenSettings: () -> Void
    @State private var showMusicPicker = false

    private var normLevel: CGFloat {
        CGFloat(min(max((controller.inputLevelDb + 70) / 70, 0), 1))
    }

    private var buttonGradient: RadialGradient {
        if controller.muted {
            return RadialGradient(colors: [Color.gray.opacity(0.6), Color(white: 0.16)], center: .center, startRadius: 10, endRadius: 110)
        }
        if controller.pttMode {
            return controller.pttPressed
                ? RadialGradient(colors: [Theme.emerald, Theme.emerald.opacity(0.6)], center: .center, startRadius: 10, endRadius: 110)
                : RadialGradient(colors: [Color(red: 0.12, green: 0.16, blue: 0.23), Theme.cardBg], center: .center, startRadius: 10, endRadius: 110)
        }
        return controller.voxOpen
            ? RadialGradient(colors: [Theme.emerald, Color(red: 0.0, green: 0.58, blue: 0.30)], center: .center, startRadius: 10, endRadius: 110)
            : RadialGradient(colors: [Color(red: 0.07, green: 0.21, blue: 0.14), Color(red: 0.04, green: 0.12, blue: 0.08)], center: .center, startRadius: 10, endRadius: 110)
    }

    private var buttonLabel: String {
        if controller.muted { return "MUTED" }
        if controller.pttMode { return controller.pttPressed ? "TALKING" : "HOLD TO TALK" }
        return controller.voxOpen ? "LIVE (VOX)" : "OPEN MIC"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Header
                HStack {
                    HStack(spacing: 10) {
                        Circle()
                            .fill(controller.peerConnected ? Theme.emerald : Theme.amber)
                            .frame(width: 12, height: 12)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(controller.peerConnected ? (controller.peerName.isEmpty ? "Rider Connected" : controller.peerName) : "Waiting for Rider...")
                                .font(.title3.bold())
                                .foregroundColor(.white)
                            Text(controller.peerIp.isEmpty ? "Hosting on UDP :50000" : controller.peerIp)
                                .font(.caption)
                                .foregroundColor(Theme.textSecondary)
                        }
                    }
                    Spacer()
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            .padding(8)
                    }
                }

                // Telemetry Strip
                HStack {
                    StatBadge(title: "RTT", value: "\(controller.rttMs) ms", color: controller.rttMs > 0 && controller.rttMs <= 60 ? Theme.emerald : .white)
                    Spacer()
                    StatBadge(title: "LOSS", value: String(format: "%.1f%%", controller.packetLossPct), color: controller.packetLossPct < 3 ? Theme.emerald : Theme.amber)
                    Spacer()
                    StatBadge(title: "BUF", value: "\(controller.bufferMs) ms", color: Theme.cyan)
                    Spacer()
                    StatBadge(title: "ROUTE", value: controller.audioRoute.rawValue, color: .white)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(Theme.cardBg)
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardBorder, lineWidth: 1))
                .cornerRadius(14)

                // Animated Giant Glove-Friendly Circle
                ZStack {
                    if controller.voxOpen && !controller.muted {
                        Circle()
                            .fill(Theme.emerald.opacity(0.22))
                            .frame(width: 210, height: 210)
                            .scaleEffect(1.0 + normLevel * 0.14)
                            .animation(.easeOut(duration: 0.12), value: normLevel)
                    }

                    Circle()
                        .fill(buttonGradient)
                        .frame(width: 196, height: 196)
                        .overlay(
                            Circle().stroke(
                                controller.muted ? Theme.red : (controller.voxOpen ? Theme.emerald : Theme.cardBorder),
                                lineWidth: 2
                            )
                        )

                    VStack(spacing: 6) {
                        Image(systemName: controller.muted ? "mic.slash.fill" : "mic.fill")
                            .font(.system(size: 56))
                            .foregroundColor(.white)
                        Text(buttonLabel)
                            .font(.headline.bold())
                            .foregroundColor(.white)
                        Text(controller.pttMode ? "Press & Hold" : "Tap to Mute")
                            .font(.caption2)
                            .foregroundColor(.white.opacity(0.7))
                    }
                }
                .frame(height: 230)
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { _ in
                            if controller.pttMode && !controller.pttPressed {
                                controller.setPttPressed(true)
                            }
                        }
                        .onEnded { _ in
                            if controller.pttMode {
                                controller.setPttPressed(false)
                            } else {
                                controller.toggleMute()
                            }
                        }
                )

                // Mode Switcher
                Picker("Mode", selection: Binding(
                    get: { controller.pttMode },
                    set: { controller.setPttMode($0) }
                )) {
                    Text("Open Mic (VOX)").tag(false)
                    Text("Push-To-Talk").tag(true)
                }
                .pickerStyle(.segmented)

                // VOX Wind Gate Card
                if !controller.pttMode {
                    VStack(spacing: 8) {
                        HStack {
                            Text("Wind Gate (VOX)")
                                .font(.caption.bold())
                                .foregroundColor(.white)
                            Spacer()
                            Text("\(Int(controller.voxThresholdDb)) dB • In: \(Int(controller.inputLevelDb)) dB")
                                .font(.caption.bold())
                                .foregroundColor(Theme.emerald)
                        }
                        ProgressView(value: Double(normLevel))
                            .tint(controller.voxOpen ? Theme.emerald : .gray)
                        Slider(value: $controller.voxThresholdDb, in: -70 ... -10)
                            .tint(Theme.emerald)
                    }
                    .padding(14)
                    .background(Theme.cardBg)
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardBorder, lineWidth: 1))
                    .cornerRadius(14)
                }

                // RideDJ Shared Music Card
                VStack(spacing: 8) {
                    HStack {
                        Image(systemName: "music.note")
                            .foregroundColor(Theme.cyan)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(controller.musicLoaded ? controller.musicTrackTitle : "Share Music (RideDJ)")
                                .font(.subheadline.bold())
                                .foregroundColor(.white)
                                .lineLimit(1)
                            Text(controller.musicLoaded ? "Auto-ducks when riding partners talk" : "Pick MP3/M4A to listen together")
                                .font(.caption2)
                                .foregroundColor(Theme.textSecondary)
                        }
                        Spacer()
                        if !controller.musicLoaded {
                            Button(action: { showMusicPicker = true }) {
                                Text("Pick Song")
                                    .font(.caption.bold())
                                    .foregroundColor(Theme.cyan)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Theme.cyan, lineWidth: 1))
                            }
                        } else {
                            HStack(spacing: 12) {
                                Button(action: { controller.toggleMusicPlayPause() }) {
                                    Image(systemName: controller.musicPlaying ? "pause.fill" : "play.fill")
                                        .foregroundColor(Theme.cyan)
                                }
                                Button(action: { controller.stopMusic() }) {
                                    Image(systemName: "xmark")
                                        .foregroundColor(Theme.textSecondary)
                                }
                            }
                        }
                    }

                    if controller.musicLoaded {
                        HStack {
                            Text("Vol \(Int(controller.musicVolume * 100))%")
                                .font(.caption2)
                                .foregroundColor(Theme.textSecondary)
                            Slider(value: $controller.musicVolume, in: 0.05 ... 0.80)
                                .tint(Theme.cyan)
                        }
                    }
                }
                .padding(14)
                .background(Theme.cardBg)
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(controller.musicPlaying ? Theme.cyan.opacity(0.5) : Theme.cardBorder, lineWidth: 1))
                .cornerRadius(14)

                // Bottom Action Bar
                HStack(spacing: 12) {
                    Button(action: { controller.toggleAudioRoute() }) {
                        Label(controller.preferBluetooth ? "Bluetooth" : "Speaker",
                              systemImage: controller.preferBluetooth ? "headphones" : "speaker.wave.2.fill")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(Theme.cardBg)
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardBorder, lineWidth: 1))
                            .cornerRadius(14)
                    }

                    Button(action: { controller.stop() }) {
                        Label("End Ride", systemImage: "phone.down.fill")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(Theme.red)
                            .cornerRadius(14)
                    }
                }
            }
            .padding(20)
        }
        .fileImporter(
            isPresented: $showMusicPicker,
            allowedContentTypes: [.audio],
            allowsMultipleSelection: false
        ) { result in
            if case let .success(urls) = result, let url = urls.first {
                controller.loadMusic(url: url)
            }
        }
    }
}

private struct SettingsView: View {
    @ObservedObject var controller: IntercomController
    let onBack: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .font(.title3.bold())
                            .foregroundColor(.white)
                            .padding(8)
                    }
                    Text("Settings")
                        .font(.title2.bold())
                        .foregroundColor(.white)
                    Spacer()
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("RIDER DISPLAY NAME")
                        .font(.caption.bold())
                        .foregroundColor(Theme.textSecondary)
                    TextField("Rider Name", text: $controller.deviceName)
                        .padding(12)
                        .background(Theme.cardBg)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.cardBorder, lineWidth: 1))
                        .cornerRadius(12)
                        .foregroundColor(.white)
                }

                Toggle(isOn: $controller.audioCuesEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("In-Helmet Audio Cues")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                        Text("Soft chime on connect, disconnect & mute")
                            .font(.caption)
                            .foregroundColor(Theme.textSecondary)
                    }
                }
                .tint(Theme.emerald)
                .padding(14)
                .background(Theme.cardBg)
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.cardBorder, lineWidth: 1))
                .cornerRadius(14)

                VStack(alignment: .leading, spacing: 8) {
                    Text("JITTER BUFFER PRESET")
                        .font(.caption.bold())
                        .foregroundColor(Theme.textSecondary)
                    ForEach(JitterPreset.allCases) { preset in
                        let selected = controller.jitterPreset == preset
                        Button(action: { controller.jitterPreset = preset }) {
                            HStack {
                                Text(preset.rawValue)
                                    .foregroundColor(.white)
                                    .fontWeight(selected ? .bold : .regular)
                                Spacer()
                                if selected {
                                    Text("ACTIVE")
                                        .font(.caption2.bold())
                                        .foregroundColor(Theme.emerald)
                                }
                            }
                            .padding(14)
                            .background(selected ? Theme.emerald.opacity(0.14) : Theme.cardBg)
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? Theme.emerald : Theme.cardBorder, lineWidth: 1))
                            .cornerRadius(12)
                        }
                    }
                }

                Text("RideCom v1.0.0 • Native Opus • 100% Offline UDP")
                    .font(.caption)
                    .foregroundColor(Theme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.top, 12)
            }
            .padding(20)
        }
    }
}

private struct StatBadge: View {
    let title: String
    let value: String
    let color: Color

    var body: some View {
        VStack(spacing: 2) {
            Text(title)
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(Theme.textSecondary)
            Text(value)
                .font(.caption.bold())
                .foregroundColor(color)
        }
    }
}
