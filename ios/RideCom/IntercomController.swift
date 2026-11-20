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
        didSet {
            voxGate.thresholdDb = voxThresholdDb
            UserDefaults.standard.set(voxThresholdDb, forKey: "ridecom_vox_db")
        }
    }
    @Published var rttMs = 0
    @Published var packetLossPct: Float = 0.0
    @Published var bufferMs = 40
    @Published var jitterPreset: JitterPreset = .balanced {
        didSet {
            jitterBuffer.setPreset(jitterPreset)
            bufferMs = jitterBuffer.currentBufferMs()
            UserDefaults.standard.set(jitterPreset.rawValue, forKey: "ridecom_jitter_preset")
        }
    }
    @Published var audioRoute: AudioRouteKind = .speaker
    @Published var preferBluetooth = true
    @Published var audioCuesEnabled: Bool = true {
        didSet {
            cueMixer.enabled = audioCuesEnabled
            UserDefaults.standard.set(audioCuesEnabled, forKey: "ridecom_audio_cues")
        }
    }
    @Published var deviceName: String = UIDevice.current.name {
        didSet { UserDefaults.standard.set(deviceName, forKey: "ridecom_device_name") }
    }
    @Published var lastPeerIp: String = ""
    @Published var lastPeerName: String = ""
    @Published var localIp: String? = PeerDiscovery.getLocalWifiIp()
    @Published var errorMessage: String?

    @Published var musicLoaded = false
    @Published var musicPlaying = false
    @Published var musicTrackTitle = ""
    @Published var musicVolume: Float = 0.35 {
        didSet {
            musicSharer.volume = musicVolume
            UserDefaults.standard.set(musicVolume, forKey: "ridecom_music_vol")
        }
    }

    let discovery = PeerDiscovery()

    private let audioEngine = AudioEngine()
    private let jitterBuffer = JitterBuffer()
    private let voxGate = VoxGate()
    private let cueMixer = AudioCueMixer()
    private let musicSharer = MusicSharer()
    private var opusCodec: OpusCodec?
    private var udpManager: UdpIntercomManager?

    private let stateLock = NSLock()
    private var txMuted = false
    private var txPttMode = false
    private var txPttPressed = false
    private var wasPeerConnected = false
    private var wasPlayingRemote = false
    private var lastRemoteAudioMs: Double = 0
    private var localMusicQueue: [[Float]] = []
    private var uiTick = 0

    init() {
        let ud = UserDefaults.standard
        if let savedName = ud.string(forKey: "ridecom_device_name"), !savedName.isEmpty {
            deviceName = savedName
        }
        lastPeerIp = ud.string(forKey: "ridecom_last_ip") ?? ""
        lastPeerName = ud.string(forKey: "ridecom_last_peer_name") ?? ""

        if ud.object(forKey: "ridecom_vox_db") != nil {
            voxThresholdDb = ud.float(forKey: "ridecom_vox_db")
        }
        if let savedPreset = ud.string(forKey: "ridecom_jitter_preset"),
           let preset = JitterPreset(rawValue: savedPreset) {
            jitterPreset = preset
        }
        if ud.object(forKey: "ridecom_audio_cues") != nil {
            audioCuesEnabled = ud.bool(forKey: "ridecom_audio_cues")
        }
        if ud.object(forKey: "ridecom_music_vol") != nil {
            musicVolume = ud.float(forKey: "ridecom_music_vol")
        }

        voxGate.thresholdDb = voxThresholdDb
        jitterBuffer.setPreset(jitterPreset)
        bufferMs = jitterBuffer.currentBufferMs()
        cueMixer.enabled = audioCuesEnabled
        musicSharer.volume = musicVolume

        discovery.start(deviceName: deviceName)
    }

    func refreshNetwork() {
        localIp = PeerDiscovery.getLocalWifiIp()
        discovery.start(deviceName: deviceName)
    }

    func start(targetIp: String?) {
        guard !active else { return }
        errorMessage = nil

        if let ip = targetIp, !ip.isEmpty {
            lastPeerIp = ip
            UserDefaults.standard.set(ip, forKey: "ridecom_last_ip")
        }

        do {
            let codec = try OpusCodec()
            opusCodec = codec
            jitterBuffer.reset()
            jitterBuffer.setPreset(jitterPreset)
            voxGate.thresholdDb = voxThresholdDb
            cueMixer.enabled = audioCuesEnabled
            cueMixer.clear()

            stateLock.lock()
            wasPeerConnected = false
            wasPlayingRemote = false
            lastRemoteAudioMs = 0
            localMusicQueue.removeAll()
            stateLock.unlock()

            let udp = UdpIntercomManager(localName: deviceName)
            udpManager = udp

            try udp.start(
                targetHost: targetIp,
                onAudioPacket: { [weak self] packet in
                    self?.jitterBuffer.push(seq: packet.sequence, payload: packet.payload, flags: packet.flags)
                },
                onPeerUpdate: { [weak self] connected, name, ip, rtt in
                    guard let self = self else { return }
                    self.stateLock.lock()
                    let prevConn = self.wasPeerConnected
                    if connected && !prevConn {
                        self.wasPeerConnected = true
                        self.cueMixer.play(.connected)
                    } else if !connected && prevConn {
                        self.wasPeerConnected = false
                        self.cueMixer.play(.disconnected)
                    }
                    self.stateLock.unlock()

                    let loss = self.jitterBuffer.packetLossPercent()
                    let buf = self.jitterBuffer.currentBufferMs()
                    Task { @MainActor in
                        self.peerConnected = connected
                        self.peerName = name
                        self.peerIp = ip
                        self.rttMs = rtt
                        self.packetLossPct = loss
                        self.bufferMs = buf
                        if connected && !ip.isEmpty {
                            self.lastPeerIp = ip
                            UserDefaults.standard.set(ip, forKey: "ridecom_last_ip")
                            if !name.isEmpty {
                                self.lastPeerName = name
                                UserDefaults.standard.set(name, forKey: "ridecom_last_peer_name")
                            }
                        }
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
        musicSharer.stop()
        cueMixer.clear()
        audioEngine.stop()
        udpManager?.stop()
        udpManager = nil
        opusCodec = nil
        jitterBuffer.reset()

        stateLock.lock()
        localMusicQueue.removeAll()
        stateLock.unlock()

        active = false
        peerConnected = false
        voxOpen = false
        inputLevelDb = -90.0
        rttMs = 0
        packetLossPct = 0.0
        musicLoaded = false
        musicPlaying = false
        musicTrackTitle = ""
    }

    func toggleMute() {
        muted.toggle()
        cueMixer.play(muted ? .muteOn : .muteOff)
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

    func loadMusic(url: URL) {
        do {
            try musicSharer.loadAndPlay(url: url)
            musicLoaded = musicSharer.isLoaded
            musicPlaying = musicSharer.isPlaying
            musicTrackTitle = musicSharer.trackTitle
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func toggleMusicPlayPause() {
        musicPlaying = musicSharer.togglePlayPause()
    }

    func stopMusic() {
        musicSharer.stop()
        stateLock.lock()
        localMusicQueue.removeAll()
        stateLock.unlock()
        musicLoaded = false
        musicPlaying = false
        musicTrackTitle = ""
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

        let nowMs = ProcessInfo.processInfo.systemUptime * 1000.0
        stateLock.lock()
        let isMuted = txMuted
        let isPttMode = txPttMode
        let isPttHeld = txPttPressed
        let peerSpeaking = (nowMs - lastRemoteAudioMs) < 450.0
        stateLock.unlock()

        let shouldTxVoice: Bool
        if isMuted {
            shouldTxVoice = false
        } else if isPttMode {
            shouldTxVoice = isPttHeld
        } else {
            shouldTxVoice = gateOpen
        }

        let musicFrame = musicSharer.nextFrame(voiceActive: shouldTxVoice || peerSpeaking)
        if let m = musicFrame {
            stateLock.lock()
            if localMusicQueue.count >= 6 { localMusicQueue.removeFirst() }
            localMusicQueue.append(m)
            stateLock.unlock()
        }

        if let codec = opusCodec {
            var txBuffer: AVAudioPCMBuffer?
            if shouldTxVoice && musicFrame != nil {
                MusicSharer.mix(musicFrame!, into: buffer)
                txBuffer = buffer
            } else if shouldTxVoice {
                txBuffer = buffer
            } else if let m = musicFrame {
                let mbuf = codec.makeSilenceBuffer()
                MusicSharer.mix(m, into: mbuf)
                txBuffer = mbuf
            }

            if let sendBuf = txBuffer, let opus = codec.encode(sendBuf) {
                var flags: UInt8 = shouldTxVoice ? RideComProtocol.flagVoiceActive : 0
                if isPttMode && isPttHeld {
                    flags |= RideComProtocol.flagPttActive
                }
                udpManager?.sendAudio(opusPayload: opus, flags: flags)
            }
        }

        uiTick += 1
        if uiTick >= 4 {
            uiTick = 0
            Task { @MainActor [weak self] in
                self?.inputLevelDb = levelDb
                self?.voxOpen = shouldTxVoice
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

        let outBuf: AVAudioPCMBuffer
        var hasRemote = false

        switch jitterBuffer.pop() {
        case let .frame(payload, flags):
            if let decoded = codec.decode(payload) {
                hasRemote = true
                if (flags & RideComProtocol.flagVoiceActive) != 0 {
                    stateLock.lock()
                    lastRemoteAudioMs = ProcessInfo.processInfo.systemUptime * 1000.0
                    stateLock.unlock()
                }
                if !wasPlayingRemote {
                    AudioCueMixer.applyFade(decoded, fadeIn: true)
                }
                outBuf = decoded
            } else {
                outBuf = codec.makeSilenceBuffer()
            }
        case .missing, .buffering:
            outBuf = codec.makeSilenceBuffer()
        }

        wasPlayingRemote = hasRemote

        stateLock.lock()
        let localMusic = localMusicQueue.isEmpty ? nil : localMusicQueue.removeFirst()
        stateLock.unlock()

        if let m = localMusic {
            MusicSharer.mix(m, into: outBuf)
        }
        cueMixer.mixInto(outBuf)

        return outBuf
    }
}
