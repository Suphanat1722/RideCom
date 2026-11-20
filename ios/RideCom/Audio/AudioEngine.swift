import AVFoundation
import Foundation

enum AudioRouteKind: String {
    case bluetoothHfp = "BT HFP"
    case wiredHeadset = "Wired"
    case speaker = "Speaker"
    case receiver = "Receiver"
}

final class AudioEngine {
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let canonicalFormat: AVAudioFormat

    private var converter: AVAudioConverter?
    private var accumulator: [Float] = []
    private var playbackTimer: DispatchSourceTimer?
    private let playbackQueue = DispatchQueue(label: "com.ridecom.audio.playback", qos: .userInteractive)

    private var isRunning = false
    private(set) var preferBluetooth = true
    private(set) var currentRoute: AudioRouteKind = .speaker

    private var onCaptureFrame: ((AVAudioPCMBuffer) -> Void)?
    private var onNeedPlaybackFrame: (() -> AVAudioPCMBuffer)?
    private var onRouteChanged: ((AudioRouteKind) -> Void)?

    init() {
        self.canonicalFormat = AVAudioFormat(
            standardFormatWithSampleRate: RideComProtocol.sampleRate,
            channels: RideComProtocol.channels
        )!
    }

    func start(
        preferBluetooth: Bool,
        onCaptureFrame: @escaping (AVAudioPCMBuffer) -> Void,
        onNeedPlaybackFrame: @escaping () -> AVAudioPCMBuffer,
        onRouteChanged: @escaping (AudioRouteKind) -> Void
    ) throws {
        guard !isRunning else { return }
        self.preferBluetooth = preferBluetooth
        self.onCaptureFrame = onCaptureFrame
        self.onNeedPlaybackFrame = onNeedPlaybackFrame
        self.onRouteChanged = onRouteChanged
        self.accumulator.removeAll(keepingCapacity: true)

        try configureSession(preferBluetooth: preferBluetooth)
        registerNotifications()
        try startEngineGraph()
        startPlaybackTimer()

        isRunning = true
        updateRouteState()
    }

    func switchRoute(preferBluetooth: Bool) {
        self.preferBluetooth = preferBluetooth
        do {
            try configureSession(preferBluetooth: preferBluetooth)
            updateRouteState()
        } catch {
            // Keep running on current route
        }
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false

        playbackTimer?.cancel()
        playbackTimer = nil

        NotificationCenter.default.removeObserver(self)

        engine.inputNode.removeTap(onBus: 0)
        playerNode.stop()
        engine.stop()
        engine.reset()
        converter = nil
        accumulator.removeAll(keepingCapacity: false)

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func configureSession(preferBluetooth: Bool) throws {
        let session = AVAudioSession.sharedInstance()
        var options: AVAudioSession.CategoryOptions = [.allowBluetooth]
        if !preferBluetooth {
            options.insert(.defaultToSpeaker)
        }
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: options)
        try? session.setPreferredSampleRate(RideComProtocol.sampleRate)
        try? session.setPreferredIOBufferDuration(Double(RideComProtocol.frameMs) / 1000.0)
        try session.setActive(true)

        if !preferBluetooth {
            try? session.overrideOutputAudioPort(.speaker)
        } else {
            try? session.overrideOutputAudioPort(.none)
        }
    }

    private func startEngineGraph() throws {
        let inputNode = engine.inputNode
        try? inputNode.setVoiceProcessingEnabled(true)

        if !engine.attachedNodes.contains(playerNode) {
            engine.attach(playerNode)
        }
        engine.connect(playerNode, to: engine.mainMixerNode, format: canonicalFormat)

        let hwFormat = inputNode.outputFormat(forBus: 0)
        converter = AVAudioConverter(from: hwFormat, to: canonicalFormat)

        let tapBufferSize = AVAudioFrameCount(max(256, Int(hwFormat.sampleRate * 0.02)))
        inputNode.removeTap(onBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: tapBufferSize, format: hwFormat) { [weak self] buffer, _ in
            self?.handleCapturedBuffer(buffer)
        }

        engine.prepare()
        try engine.start()
        playerNode.play()
    }

    private func handleCapturedBuffer(_ inputBuffer: AVAudioPCMBuffer) {
        guard let converter = converter else { return }

        let ratio = canonicalFormat.sampleRate / inputBuffer.format.sampleRate
        let expectedFrames = AVAudioFrameCount(ceil(Double(inputBuffer.frameLength) * ratio)) + 16
        guard let outBuffer = AVAudioPCMBuffer(pcmFormat: canonicalFormat, frameCapacity: expectedFrames) else {
            return
        }

        var error: NSError?
        var consumed = false
        converter.convert(to: outBuffer, error: &error) { _, outStatus in
            if consumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            outStatus.pointee = .haveData
            return inputBuffer
        }

        guard error == nil, let channelData = outBuffer.floatChannelData?[0] else { return }
        let count = Int(outBuffer.frameLength)
        guard count > 0 else { return }

        accumulator.append(contentsOf: UnsafeBufferPointer(start: channelData, count: count))

        let frameLen = RideComProtocol.frameSamples
        while accumulator.count >= frameLen {
            guard let frameBuf = AVAudioPCMBuffer(
                pcmFormat: canonicalFormat,
                frameCapacity: AVAudioFrameCount(frameLen)
            ) else { break }
            frameBuf.frameLength = AVAudioFrameCount(frameLen)
            if let dst = frameBuf.floatChannelData?[0] {
                accumulator.withUnsafeBufferPointer { src in
                    dst.update(from: src.baseAddress!, count: frameLen)
                }
            }
            accumulator.removeFirst(frameLen)
            onCaptureFrame?(frameBuf)
        }
    }

    private func startPlaybackTimer() {
        let timer = DispatchSource.makeTimerSource(queue: playbackQueue)
        timer.schedule(deadline: .now() + .milliseconds(20), repeating: .milliseconds(RideComProtocol.frameMs), leeway: .milliseconds(2))
        timer.setEventHandler { [weak self] in
            guard let self = self, self.isRunning, let provider = self.onNeedPlaybackFrame else { return }
            let buf = provider()
            self.playerNode.scheduleBuffer(buf, completionHandler: nil)
        }
        playbackTimer = timer
        timer.resume()
    }

    private func registerNotifications() {
        let nc = NotificationCenter.default
        nc.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
        nc.addObserver(
            self,
            selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance()
        )
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard let info = notification.userInfo,
              let typeVal = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeVal) else { return }

        if type == .ended {
            try? configureSession(preferBluetooth: preferBluetooth)
            if !engine.isRunning {
                try? engine.start()
                playerNode.play()
            }
            updateRouteState()
        }
    }

    @objc private func handleRouteChange(_ notification: Notification) {
        updateRouteState()
        if isRunning && !engine.isRunning {
            try? engine.start()
            playerNode.play()
        }
    }

    private func updateRouteState() {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let route: AudioRouteKind
        if outputs.contains(where: { $0.portType == .bluetoothHFP || $0.portType == .bluetoothLE || $0.portType == .bluetoothA2DP }) {
            route = .bluetoothHfp
        } else if outputs.contains(where: { $0.portType == .headphones || $0.portType == .usbAudio }) {
            route = .wiredHeadset
        } else if outputs.contains(where: { $0.portType == .builtInSpeaker }) {
            route = .speaker
        } else {
            route = .receiver
        }
        currentRoute = route
        onRouteChanged?(route)
    }
}
