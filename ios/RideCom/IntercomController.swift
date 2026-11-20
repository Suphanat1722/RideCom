import AVFoundation
import Foundation
import UIKit

@MainActor
final class IntercomController: ObservableObject {
    @Published var active = false
    @Published var peerConnected = false
    @Published var peerName = ""
    @Published var peerIp = ""
    @Published var muted = false
    @Published var pttMode = false
    @Published var pttPressed = false
    @Published var voxOpen = false
    @Published var inputLevelDb: Float = -90.0
    @Published var voxThresholdDb: Float = -58.0 {
        didSet { voxGate.thresholdDb = voxThresholdDb }
    }
    @Published var rttMs = 0
    @Published var packetLossPct: Float = 0.0
    @Published var bufferMs = 40
    @Published var jitterPreset: JitterPreset = .balanced {
        didSet {
            jitterBuffer.setPreset(jitterPreset)
            bufferMs = jitterBuffer.currentBufferMs()
        }
    }
    @Published var audioRoute: AudioRouteKind = .speaker
    @Published var preferBluetooth = true
    @Published var deviceName: String = UIDevice.current.name
    @Published var localIp: String? = PeerDiscovery.getLocalWifiIp()
    @Published var errorMessage: String?

    let discovery = PeerDiscovery()

    private let audioEngine = AudioEngine()
    private let jitterBuffer = JitterBuffer()
    private let voxGate = VoxGate()
    private var opusCodec: OpusCodec?
    private var udpManager: UdpIntercomManager?

    private let stateLock = NSLock()
    private var txMuted = false
    private var txPttMode = false
    private var txPttPressed = false
    private var uiTick = 0

    init() {
        discovery.start(deviceName: deviceName)
    }

    func refreshNetwork() {
        localIp = PeerDiscovery.getLocalWifiIp()
        discovery.start(deviceName: deviceName)
    }

    func start(targetIp: String?) {
        guard !active else { return }
        errorMessage = nil

        do {
            let codec = try OpusCodec()
            opusCodec = codec
            jitterBuffer.reset()
            jitterBuffer.setPreset(jitterPreset)
            voxGate.thresholdDb = voxThresholdDb

            let udp = UdpIntercomManager(localName: deviceName)
            udpManager = udp

            try udp.start(
                targetHost: targetIp,
                onAudioPacket: { [weak self] packet in
                    self?.jitterBuffer.push(seq: packet.sequence, payload: packet.payload, flags: packet.flags)
                },
                onPeerUpdate: { [weak self] connected, name, ip, rtt in
                    guard let self = self else { return }
                    let loss = self.jitterBuffer.packetLossPercent()
                    let buf = self.jitterBuffer.currentBufferMs()
                    Task { @MainActor in
                        self.peerConnected = connected
                        self.peerName = name
                        self.peerIp = ip
                        self.rttMs = rtt
                        self.packetLossPct = loss
                        self.bufferMs = buf
                    }
                }
            )

            try audioEngine.start(
                preferBluetooth: preferBluetooth,
                onCaptureFrame: { [weak self] pcmBuffer in
                    self?.handleCapture(pcmBuffer)
                },
                onNeedPlaybackFrame: { [weak self] in
                    self?.providePlaybackBuffer() ?? codec.makeSilenceBuffer()
                },
                onRouteChanged: { [weak self] route in
                    Task { @MainActor in
                        self?.audioRoute = route
                    }
                }
            )

            peerIp = targetIp ?? ""
            peerConnected = false
            active = true
        } catch {
            stop()
            errorMessage = error.localizedDescription
        }
    }

    func stop() {
        audioEngine.stop()
        udpManager?.stop()
        udpManager = nil
        opusCodec = nil
        jitterBuffer.reset()

        active = false
        peerConnected = false
        voxOpen = false
        inputLevelDb = -90.0
        rttMs = 0
        packetLossPct = 0.0
    }

    func toggleMute() {
        muted.toggle()
        syncTxFlags()
    }

    func setPttMode(_ enabled: Bool) {
        pttMode = enabled
        pttPressed = false
        syncTxFlags()
    }

    func setPttPressed(_ pressed: Bool) {
        pttPressed = pressed
        syncTxFlags()
    }

    func toggleAudioRoute() {
        preferBluetooth.toggle()
        audioEngine.switchRoute(preferBluetooth: preferBluetooth)
    }

    private func syncTxFlags() {
        stateLock.lock()
        txMuted = muted
        txPttMode = pttMode
        txPttPressed = pttPressed
        stateLock.unlock()
    }

    private func handleCapture(_ buffer: AVAudioPCMBuffer) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        let gateOpen = voxGate.process(samples: channel, count: Int(buffer.frameLength))
        let levelDb = voxGate.lastLevelDb

        stateLock.lock()
        let isMuted = txMuted
        let isPttMode = txPttMode
        let isPttHeld = txPttPressed
        stateLock.unlock()

        let shouldTx: Bool
        if isMuted {
            shouldTx = false
        } else if isPttMode {
            shouldTx = isPttHeld
        } else {
            shouldTx = gateOpen
        }

        if shouldTx, let opus = opusCodec?.encode(buffer) {
            var flags = RideComProtocol.flagVoiceActive
            if isPttMode && isPttHeld {
                flags |= RideComProtocol.flagPttActive
            }
            udpManager?.sendAudio(opusPayload: opus, flags: flags)
        }

        uiTick += 1
        if uiTick >= 4 {
            uiTick = 0
            Task { @MainActor [weak self] in
                self?.inputLevelDb = levelDb
                self?.voxOpen = shouldTx
            }
        }
    }

    private func providePlaybackBuffer() -> AVAudioPCMBuffer {
        guard let codec = opusCodec else {
            let fmt = AVAudioFormat(standardFormatWithSampleRate: RideComProtocol.sampleRate, channels: RideComProtocol.channels)!
            let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(RideComProtocol.frameSamples))!
            buf.frameLength = AVAudioFrameCount(RideComProtocol.frameSamples)
            return buf
        }
        switch jitterBuffer.pop() {
        case let .frame(payload, _):
            return codec.decode(payload) ?? codec.makeSilenceBuffer()
        case .missing, .buffering:
            return codec.makeSilenceBuffer()
        }
    }
}
