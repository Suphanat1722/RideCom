import SwiftUI

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
            Color.black.ignoresSafeArea()

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
    }
}

private struct LobbyView: View {
    @ObservedObject var controller: IntercomController
    @ObservedObject var discovery: PeerDiscovery
    @Binding var manualIp: String
    let onOpenSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("RideCom")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundColor(.white)
                    Text("Offline Helmet Intercom")
                        .font(.caption)
                        .foregroundColor(.gray)
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

            HStack(spacing: 12) {
                Image(systemName: "wifi")
                    .font(.title2)
                    .foregroundColor(controller.localIp != nil ? .green : .red)
                VStack(alignment: .leading, spacing: 2) {
                    Text(controller.localIp.map { "Local IP: \($0)" } ?? "No Wi-Fi / Hotspot detected")
                        .font(.subheadline.bold())
                        .foregroundColor(.white)
                    Text("Port 50000 • UDP + Opus 16kHz")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
                Spacer()
            }
            .padding(14)
            .background(Color(red: 0.08, green: 0.09, blue: 0.12))
            .cornerRadius(12)

            if let err = controller.errorMessage {
                Text(err)
                    .font(.caption)
                    .foregroundColor(.red)
            }

            Button(action: { controller.start(targetIp: nil) }) {
                Text("Start Listening (Hotspot Host)")
                    .font(.headline)
                    .foregroundColor(.black)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(Color.green)
                    .cornerRadius(12)
            }

            if let gw = PeerDiscovery.guessHotspotGateway(localIp: controller.localIp) {
                Button(action: { controller.start(targetIp: gw) }) {
                    Text("Connect to Hotspot Host (\(gw))")
                        .font(.subheadline.bold())
                        .foregroundColor(.green)
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.green.opacity(0.6), lineWidth: 1.5)
                        )
                }
            }

            Text("Nearby Riders (Bonjour Auto-Discovery)")
                .font(.subheadline.bold())
                .foregroundColor(.gray)

            ScrollView {
                VStack(spacing: 8) {
                    if discovery.peers.isEmpty {
                        Text("Scanning local Wi-Fi...")
                            .font(.caption)
                            .foregroundColor(.gray)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 8)
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
                                            .foregroundColor(.gray)
                                    }
                                    Spacer()
                                    Text("CONNECT")
                                        .font(.caption.bold())
                                        .foregroundColor(.green)
                                }
                                .padding(14)
                                .background(Color(red: 0.10, green: 0.12, blue: 0.17))
                                .cornerRadius(12)
                            }
                        }
                    }
                }
            }

            HStack(spacing: 8) {
                TextField("Manual Peer IP (e.g. 192.168.43.1)", text: $manualIp)
                    .keyboardType(.numbersAndPunctuation)
                    .padding(12)
                    .background(Color(red: 0.10, green: 0.12, blue: 0.17))
                    .cornerRadius(10)
                    .foregroundColor(.white)

                Button(action: {
                    let trimmed = manualIp.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty { controller.start(targetIp: trimmed) }
                }) {
                    Text("Join")
                        .font(.headline)
                        .foregroundColor(.black)
                        .padding(.horizontal, 18)
                        .frame(height: 46)
                        .background(Color.green)
                        .cornerRadius(10)
                }
            }
        }
        .padding(20)
    }
}

private struct ActiveCallView: View {
    @ObservedObject var controller: IntercomController
    let onOpenSettings: () -> Void

    private var buttonColor: Color {
        if controller.muted { return Color.gray.opacity(0.45) }
        if controller.pttMode {
            return controller.pttPressed ? .green : Color(red: 0.12, green: 0.16, blue: 0.23)
        }
        return controller.voxOpen ? .green : Color(red: 0.08, green: 0.22, blue: 0.15)
    }

    private var buttonLabel: String {
        if controller.muted { return "MUTED" }
        if controller.pttMode { return controller.pttPressed ? "TALKING" : "HOLD TO TALK" }
        return controller.voxOpen ? "LIVE (VOX)" : "OPEN MIC"
    }

    var body: some View {
        VStack(spacing: 20) {
            VStack(spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(controller.peerConnected ? (controller.peerName.isEmpty ? "Rider Connected" : controller.peerName) : "Waiting for Rider...")
                            .font(.title2.bold())
                            .foregroundColor(controller.peerConnected ? .green : .yellow)
                        Text(controller.peerIp.isEmpty ? "Listening on UDP :50000" : controller.peerIp)
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                    Spacer()
                    Button(action: onOpenSettings) {
                        Image(systemName: "gearshape.fill")
                            .font(.title3)
                            .foregroundColor(.white)
                            .padding(8)
                    }
                }

                HStack {
                    StatBadge(title: "RTT", value: "\(controller.rttMs) ms")
                    Spacer()
                    StatBadge(title: "LOSS", value: String(format: "%.1f%%", controller.packetLossPct))
                    Spacer()
                    StatBadge(title: "BUF", value: "\(controller.bufferMs) ms")
                    Spacer()
                    StatBadge(title: "AUDIO", value: controller.audioRoute.rawValue)
                }
                .padding(12)
                .background(Color(red: 0.08, green: 0.09, blue: 0.12))
                .cornerRadius(12)
            }

            Spacer()

            ZStack {
                Circle()
                    .fill(buttonColor)
                    .frame(width: 210, height: 210)
                VStack(spacing: 8) {
                    Image(systemName: controller.muted ? "mic.slash.fill" : "mic.fill")
                        .font(.system(size: 56))
                        .foregroundColor(.white)
                    Text(buttonLabel)
                        .font(.headline.bold())
                        .foregroundColor(.white)
                }
            }
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

            Spacer()

            VStack(spacing: 12) {
                Picker("Mode", selection: Binding(
                    get: { controller.pttMode },
                    set: { controller.setPttMode($0) }
                )) {
                    Text("Open Mic (VOX)").tag(false)
                    Text("Push-To-Talk").tag(true)
                }
                .pickerStyle(.segmented)

                if !controller.pttMode {
                    VStack(spacing: 6) {
                        HStack {
                            Text("Wind Gate (VOX)")
                                .font(.caption)
                                .foregroundColor(.gray)
                            Spacer()
                            Text("\(Int(controller.voxThresholdDb)) dB (In: \(Int(controller.inputLevelDb)) dB)")
                                .font(.caption.bold())
                                .foregroundColor(.green)
                        }

                        let norm = Double(min(max((controller.inputLevelDb + 70) / 70, 0), 1))
                        ProgressView(value: norm)
                            .tint(controller.voxOpen ? .green : .gray)

                        Slider(value: $controller.voxThresholdDb, in: -70 ... -10)
                            .tint(.green)
                    }
                }

                HStack(spacing: 12) {
                    Button(action: { controller.toggleAudioRoute() }) {
                        Label(controller.preferBluetooth ? "Bluetooth" : "Speaker",
                              systemImage: controller.preferBluetooth ? "headphones" : "speaker.wave.2.fill")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(Color(red: 0.14, green: 0.16, blue: 0.21))
                            .cornerRadius(12)
                    }

                    Button(action: { controller.stop() }) {
                        Label("End", systemImage: "phone.down.fill")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(Color.red)
                            .cornerRadius(12)
                    }
                }
            }
        }
        .padding(20)
    }
}

private struct SettingsView: View {
    @ObservedObject var controller: IntercomController
    let onBack: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Settings")
                .font(.title.bold())
                .foregroundColor(.white)

            VStack(alignment: .leading, spacing: 6) {
                Text("Rider Name")
                    .font(.caption)
                    .foregroundColor(.gray)
                TextField("Rider Name", text: $controller.deviceName)
                    .padding(12)
                    .background(Color(red: 0.10, green: 0.12, blue: 0.17))
                    .cornerRadius(10)
                    .foregroundColor(.white)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Jitter Buffer Preset")
                    .font(.subheadline.bold())
                    .foregroundColor(.gray)
                ForEach(JitterPreset.allCases) { preset in
                    Button(action: { controller.jitterPreset = preset }) {
                        HStack {
                            Text(preset.rawValue)
                                .foregroundColor(.white)
                            Spacer()
                            if controller.jitterPreset == preset {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                            }
                        }
                        .padding(12)
                        .background(Color(red: 0.10, green: 0.12, blue: 0.17))
                        .cornerRadius(10)
                    }
                }
            }

            Spacer()

            Button(action: onBack) {
                Text("Done")
                    .font(.headline)
                    .foregroundColor(.black)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Color.green)
                    .cornerRadius(12)
            }
        }
        .padding(20)
    }
}

private struct StatBadge: View {
    let title: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(title)
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(.gray)
            Text(value)
                .font(.caption.bold())
                .foregroundColor(.white)
        }
    }
}
