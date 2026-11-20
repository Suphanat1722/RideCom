import AVFoundation
import Foundation

enum AudioRouteKind: String {
    case bluetoothHfp = "BT HFP"
    case wiredHeadset = "Wired"
    case speaker = "Speaker"
    case receiver = "Receiver"
}

/// Real-time full-duplex iOS AudioEngine using hardware-clock pull-driven AVAudioSourceNode
/// (eliminates AVAudioPlayerNode + DispatchSourceTimer silent stalls with VoiceProcessingIO).
final class AudioEngine {
    private let engine = AVAudioEngine()
    private let canonicalFormat: AVAudioFormat

    private var sourceNode: AVAudioSourceNode?
    private var converter: AVAudioConverter?
    private var captureAccum: [Float] = []

    // Render-thread ring buffer for exact 20ms frame pulling
    private let renderLock = NSLock()
    private var renderFrame: [Float] = Array(repeating: 0, count: RideComProtocol.frameSamples)
    private var renderOffset: Int = RideComProtocol.frameSamples

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
        self.captureAccum.removeAll(keepingCapacity: true)
        self.renderOffset = RideComProtocol.frameSamples

        try configureSession(preferBluetooth: preferBluetooth)
        registerNotifications()
        try startEngineGraph()

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

        NotificationCenter.default.removeObserver(self)

        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        if let src = sourceNode {
            engine.detach(src)
            sourceNode = nil
        }
        engine.reset()
        converter = nil
        captureAccum.removeAll(keepingCapacity: false)

        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func configureSession(preferBluetooth: Bool) throws {
        let session = AVAudioSession.sharedInstance()
        // Always include .defaultToSpeaker so when NO Bluetooth helmet is connected,
        // audio plays clearly on loudspeaker instead of the quiet earpiece receiver.
        // When Bluetooth HFP IS connected, iOS still routes automatically to Bluetooth.
        var options: AVAudioSession.CategoryOptions = [.defaultToSpeaker, .mixWithOthers]
        if preferBluetooth {
            options.insert(.allowBluetooth)
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

        if let existing = sourceNode {
            engine.detach(existing)
            sourceNode = nil
        }

        let src = AVAudioSourceNode(format: canonicalFormat) { [weak self] _, _, frameCount, audioBufferList -> OSStatus in
            guard let self = self else { return noErr }
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            let count = Int(frameCount)
            guard let firstBuf = abl.first,
                  let outPtr = firstBuf.mData?.assumingMemoryBound(to: Float.self) else {
                return noErr
            }
            self.fillRenderBuffer(outPtr, count: count)
            return noErr
        }

        sourceNode = src
        engine.attach(src)
        engine.connect(src, to: engine.mainMixerNode, format: canonicalFormat)
        engine.connect(engine.mainMixerNode, to: engine.outputNode, format: nil)
        engine.mainMixerNode.outputVolume = 1.0

        let hwFormat = inputNode.outputFormat(forBus: 0)
        converter = AVAudioConverter(from: hwFormat, to: canonicalFormat)

        let tapBufferSize = AVAudioFrameCount(max(256, Int(hwFormat.sampleRate * 0.02)))
        inputNode.removeTap(onBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: tapBufferSize, format: hwFormat) { [weak self] buffer, _ in
            self?.handleCapturedBuffer(buffer)
        }

        engine.prepare()
        try engine.start()
    }

    private func fillRenderBuffer(_ outPtr: UnsafeMutablePointer<Float>, count: Int) {
        renderLock.lock()
        defer { renderLock.unlock() }

        var written = 0
        while written < count {
            if renderOffset >= RideComProtocol.frameSamples {
                if let provider = onNeedPlaybackFrame {
                    let pcmBuf = provider()
                    let frameLen = min(Int(pcmBuf.frameLength), RideComProtocol.frameSamples)
                    if let src = pcmBuf.floatChannelData?[0] {
                        for i in 0..<frameLen {
                            renderFrame[i] = src[i]
                        }
                        if frameLen < RideComProtocol.frameSamples {
                            for i in frameLen..<RideComProtocol.frameSamples {
                                renderFrame[i] = 0
                            }
                        }
                    } else {
                        for i in 0..<RideComProtocol.frameSamples { renderFrame[i] = 0 }
                    }
                } else {
                    for i in 0..<RideComProtocol.frameSamples { renderFrame[i] = 0 }
                }
                renderOffset = 0
            }

            let avail = RideComProtocol.frameSamples - renderOffset
            let toCopy = min(avail, count - written)
            for i in 0..<toCopy {
                outPtr[written + i] = renderFrame[renderOffset + i]
            }
            renderOffset += toCopy
            written += toCopy
        }
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

        captureAccum.append(contentsOf: UnsafeBufferPointer(start: channelData, count: count))

        let frameLen = RideComProtocol.frameSamples
        while captureAccum.count >= frameLen {
            guard let frameBuf = AVAudioPCMBuffer(
                pcmFormat: canonicalFormat,
                frameCapacity: AVAudioFrameCount(frameLen)
            ) else { break }
            frameBuf.frameLength = AVAudioFrameCount(frameLen)
            if let dst = frameBuf.floatChannelData?[0] {
                captureAccum.withUnsafeBufferPointer { src in
                    dst.update(from: src.baseAddress!, count: frameLen)
                }
            }
            captureAccum.removeFirst(frameLen)
            onCaptureFrame?(frameBuf)
        }
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

        if type == .ended && isRunning {
            try? configureSession(preferBluetooth: preferBluetooth)
            if !engine.isRunning {
                try? startEngineGraph()
            }
            updateRouteState()
        }
    }

    @objc private func handleRouteChange(_ notification: Notification) {
        guard isRunning else { return }
        updateRouteState()

        guard let info = notification.userInfo,
              let reasonVal = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonVal),
              reason == .newDeviceAvailable || reason == .oldDeviceUnavailable else {
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.isRunning else { return }
            self.engine.inputNode.removeTap(onBus: 0)
            self.engine.stop()
            try? self.startEngineGraph()
            self.updateRouteState()
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
