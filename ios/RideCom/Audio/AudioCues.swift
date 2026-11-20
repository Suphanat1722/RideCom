import AVFoundation
import Foundation

enum CueTone {
    case connected
    case disconnected
    case muteOn
    case muteOff
}

/// Generates soft-enveloped 16kHz Float32 PCM audio cues for in-helmet status feedback.
final class AudioCueMixer {
    private let lock = NSLock()
    private var queue: [[Float]] = []
    var enabled: Bool = true

    func play(_ tone: CueTone) {
        guard enabled else { return }
        let samples: [Float]
        switch tone {
        case .connected:
            samples = synthTone(freqHz: 523.25, durationMs: 75, gain: 0.22)
                + synthTone(freqHz: 783.99, durationMs: 105, gain: 0.25)
        case .disconnected:
            samples = synthTone(freqHz: 622.25, durationMs: 90, gain: 0.22)
                + synthTone(freqHz: 415.30, durationMs: 130, gain: 0.22)
        case .muteOn:
            samples = synthTone(freqHz: 392.0, durationMs: 65, gain: 0.20)
        case .muteOff:
            samples = synthTone(freqHz: 698.46, durationMs: 65, gain: 0.20)
        }

        let frameLen = RideComProtocol.frameSamples
        var frames: [[Float]] = []
        var offset = 0
        while offset < samples.count {
            var frame = [Float](repeating: 0, count: frameLen)
            let copyLen = min(frameLen, samples.count - offset)
            for i in 0..<copyLen {
                frame[i] = samples[offset + i]
            }
            frames.append(frame)
            offset += frameLen
        }

        lock.lock()
        queue.append(contentsOf: frames)
        lock.unlock()
    }

    func mixInto(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        let frame = queue.isEmpty ? nil : queue.removeFirst()
        lock.unlock()

        guard let cue = frame, let dst = buffer.floatChannelData?[0] else { return }
        let count = min(Int(buffer.frameLength), cue.count)
        for i in 0..<count {
            dst[i] = max(-1.0, min(1.0, dst[i] + cue[i]))
        }
    }

    func clear() {
        lock.lock()
        queue.removeAll()
        lock.unlock()
    }

    private func synthTone(freqHz: Double, durationMs: Int, gain: Float) -> [Float] {
        let count = Int(RideComProtocol.sampleRate) * durationMs / 1000
        var out = [Float](repeating: 0, count: count)
        let fade = min(count / 4, 80)
        for i in 0..<count {
            let env: Float
            if i < fade {
                env = Float(i) / Float(max(1, fade))
            } else if i > count - fade {
                env = Float(count - i) / Float(max(1, fade))
            } else {
                env = 1.0
            }
            let wave = Float(sin(2.0 * Double.pi * freqHz * Double(i) / RideComProtocol.sampleRate))
            out[i] = wave * gain * env
        }
        return out
    }

    static func applyFade(_ buffer: AVAudioPCMBuffer, fadeIn: Bool) {
        guard let dst = buffer.floatChannelData?[0] else { return }
        let len = Int(buffer.frameLength)
        let ramp = min(32, len)
        for i in 0..<ramp {
            let gain = fadeIn ? (Float(i) / Float(ramp)) : (1.0 - Float(i) / Float(ramp))
            let idx = fadeIn ? i : (len - ramp + i)
            dst[idx] *= gain
        }
    }
}
