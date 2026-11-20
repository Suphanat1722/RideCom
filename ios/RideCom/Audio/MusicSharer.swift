import AVFoundation
import Foundation

/// Decodes local audio files (MP3, M4A, WAV, AAC) into 16kHz Mono Float32 20ms frames
/// using native AVAudioFile + AVAudioConverter, with smooth Auto Voice Ducking.
final class MusicSharer {
    private let lock = NSLock()
    private let canonicalFormat: AVAudioFormat

    private var audioFile: AVAudioFile?
    private var converter: AVAudioConverter?
    private var accumulator: [Float] = []
    private var currentDuckGain: Float = 1.0
    private var lastSpeechMs: Double = 0.0
    private var securityScopedUrl: URL?

    private(set) var isLoaded: Bool = false
    private(set) var isPlaying: Bool = false
    private(set) var trackTitle: String = ""
    var volume: Float = 0.35

    init() {
        self.canonicalFormat = AVAudioFormat(
            standardFormatWithSampleRate: RideComProtocol.sampleRate,
            channels: RideComProtocol.channels
        )!
    }

    func loadAndPlay(url: URL) throws {
        stop()

        let accessed = url.startAccessingSecurityScopedResource()
        if accessed {
            securityScopedUrl = url
        }

        let file = try AVAudioFile(forReading: url)
        guard let conv = AVAudioConverter(from: file.processingFormat, to: canonicalFormat) else {
            if accessed { url.stopAccessingSecurityScopedResource() }
            securityScopedUrl = nil
            throw NSError(domain: "RideCom.MusicSharer", code: -1, userInfo: [NSLocalizedDescriptionKey: "Unsupported audio format"])
        }

        lock.lock()
        audioFile = file
        converter = conv
        accumulator.removeAll(keepingCapacity: true)
        trackTitle = url.deletingPathExtension().lastPathComponent
        isLoaded = true
        isPlaying = true
        currentDuckGain = 1.0
        lock.unlock()
    }

    func togglePlayPause() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard isLoaded else { return false }
        isPlaying.toggle()
        return isPlaying
    }

    func stop() {
        lock.lock()
        audioFile = nil
        converter = nil
        accumulator.removeAll(keepingCapacity: false)
        isLoaded = false
        isPlaying = false
        trackTitle = ""
        currentDuckGain = 1.0
        let url = securityScopedUrl
        securityScopedUrl = nil
        lock.unlock()

        url?.stopAccessingSecurityScopedResource()
    }

    /// Pulls the next 20ms music frame scaled by volume + smooth auto-ducking.
    func nextFrame(voiceActive: Bool) -> [Float]? {
        lock.lock()
        defer { lock.unlock() }

        guard isPlaying, let file = audioFile, let conv = converter else { return nil }

        let frameLen = RideComProtocol.frameSamples
        var safety = 0
        while accumulator.count < frameLen && safety < 4 {
            safety += 1
            let readCap: AVAudioFrameCount = 2048
            guard let inBuf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: readCap) else { break }
            do {
                try file.read(into: inBuf, frameCount: readCap)
            } catch {
                break
            }

            if inBuf.frameLength == 0 {
                // Loop song seamlessly
                file.framePosition = 0
                continue
            }

            let ratio = canonicalFormat.sampleRate / file.processingFormat.sampleRate
            let outCap = AVAudioFrameCount(ceil(Double(inBuf.frameLength) * ratio)) + 32
            guard let outBuf = AVAudioPCMBuffer(pcmFormat: canonicalFormat, frameCapacity: outCap) else { break }

            var error: NSError?
            var consumed = false
            conv.convert(to: outBuf, error: &error) { _, outStatus in
                if consumed {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                consumed = true
                outStatus.pointee = .haveData
                return inBuf
            }

            if error == nil, let ch = outBuf.floatChannelData?[0], outBuf.frameLength > 0 {
                accumulator.append(contentsOf: UnsafeBufferPointer(start: ch, count: Int(outBuf.frameLength)))
            }
        }

        guard accumulator.count >= frameLen else { return nil }

        let nowMs = ProcessInfo.processInfo.systemUptime * 1000.0
        if voiceActive {
            lastSpeechMs = nowMs
        }
        let shouldDuck = (nowMs - lastSpeechMs) <= 750.0
        let targetDuck: Float = shouldDuck ? 0.15 : 1.0
        let alpha: Float = targetDuck < currentDuckGain ? 0.45 : 0.05
        currentDuckGain += (targetDuck - currentDuckGain) * alpha

        let gain = min(max(volume, 0.0), 0.85) * currentDuckGain
        var out = [Float](repeating: 0, count: frameLen)
        for i in 0..<frameLen {
            out[i] = max(-1.0, min(1.0, accumulator[i] * gain))
        }
        accumulator.removeFirst(frameLen)
        return out
    }

    static func mix(_ music: [Float], into buffer: AVAudioPCMBuffer) {
        guard let dst = buffer.floatChannelData?[0] else { return }
        let count = min(Int(buffer.frameLength), music.count)
        for i in 0..<count {
            dst[i] = max(-1.0, min(1.0, dst[i] + music[i]))
        }
    }
}
