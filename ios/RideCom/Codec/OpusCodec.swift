import AVFoundation
import Foundation
import Opus

final class OpusCodec {
    let format: AVAudioFormat
    private let encoder: Opus.Encoder
    private let decoder: Opus.Decoder
    private let encodeLock = NSLock()
    private let decodeLock = NSLock()

    init() throws {
        guard let fmt = AVAudioFormat(
            standardFormatWithSampleRate: RideComProtocol.sampleRate,
            channels: RideComProtocol.channels
        ) else {
            throw NSError(domain: "RideCom.OpusCodec", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid audio format"])
        }
        self.format = fmt
        self.encoder = try Opus.Encoder(format: fmt, application: .voip)
        self.decoder = try Opus.Decoder(format: fmt)
    }

    func encode(_ buffer: AVAudioPCMBuffer) -> Data? {
        encodeLock.lock()
        defer { encodeLock.unlock() }
        var out = Data(count: 1276)
        do {
            let written = try encoder.encode(buffer, to: &out)
            guard written > 0 else { return nil }
            return out.prefix(written)
        } catch {
            return nil
        }
    }

    func decode(_ packet: Data) -> AVAudioPCMBuffer? {
        decodeLock.lock()
        defer { decodeLock.unlock() }
        do {
            return try decoder.decode(packet)
        } catch {
            return nil
        }
    }

    func makeSilenceBuffer() -> AVAudioPCMBuffer {
        let buf = AVAudioPCMBuffer(
            pcmFormat: format,
            frameCapacity: AVAudioFrameCount(RideComProtocol.frameSamples)
        )!
        buf.frameLength = AVAudioFrameCount(RideComProtocol.frameSamples)
        if let channel = buf.floatChannelData?[0] {
            channel.initialize(repeating: 0, count: RideComProtocol.frameSamples)
        }
        return buf
    }
}
