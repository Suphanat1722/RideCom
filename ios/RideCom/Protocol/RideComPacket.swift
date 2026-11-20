import Foundation

enum RideComProtocol {
    static let magic0: UInt8 = 0x52 // 'R'
    static let magic1: UInt8 = 0x43 // 'C'
    static let version: UInt8 = 1
    static let headerSize: Int = 12
    static let defaultPort: UInt16 = 50000
    static let serviceType: String = "_ridecom._udp"

    static let sampleRate: Double = 16000
    static let channels: UInt32 = 1
    static let frameMs: Int = 20
    static let frameSamples: Int = 320 // 16000 * 20 / 1000

    static let flagVoiceActive: UInt8 = 1 << 0
    static let flagPttActive: UInt8 = 1 << 1
    static let flagFecPresent: UInt8 = 1 << 2
}

enum PacketType: UInt8 {
    case audio = 0x1
    case hello = 0x2
    case helloAck = 0x3
    case ping = 0x4
    case pong = 0x5
    case bye = 0x6
}

struct RideComPacket {
    let type: PacketType
    let flags: UInt8
    let sequence: UInt16
    let senderId: UInt16
    let timestampMs: UInt32
    let payload: Data

    init(
        type: PacketType,
        flags: UInt8 = 0,
        sequence: UInt16 = 0,
        senderId: UInt16 = 0,
        timestampMs: UInt32 = 0,
        payload: Data = Data()
    ) {
        self.type = type
        self.flags = flags
        self.sequence = sequence
        self.senderId = senderId
        self.timestampMs = timestampMs
        self.payload = payload
    }

    func encode() -> Data {
        var data = Data(capacity: RideComProtocol.headerSize + payload.count)
        data.append(RideComProtocol.magic0)
        data.append(RideComProtocol.magic1)
        let verType = ((RideComProtocol.version & 0x0F) << 4) | (type.rawValue & 0x0F)
        data.append(verType)
        data.append(flags)

        var seqBE = sequence.bigEndian
        var senderBE = senderId.bigEndian
        var tsBE = timestampMs.bigEndian
        withUnsafeBytes(of: &seqBE) { data.append(contentsOf: $0) }
        withUnsafeBytes(of: &senderBE) { data.append(contentsOf: $0) }
        withUnsafeBytes(of: &tsBE) { data.append(contentsOf: $0) }

        if !payload.isEmpty {
            data.append(payload)
        }
        return data
    }

    static func decode(_ data: Data) -> RideComPacket? {
        guard data.count >= RideComProtocol.headerSize else { return nil }
        return data.withUnsafeBytes { raw -> RideComPacket? in
            let bytes = raw.bindMemory(to: UInt8.self)
            guard bytes[0] == RideComProtocol.magic0,
                  bytes[1] == RideComProtocol.magic1 else { return nil }

            let verType = bytes[2]
            let version = (verType >> 4) & 0x0F
            guard version == RideComProtocol.version else { return nil }
            guard let type = PacketType(rawValue: verType & 0x0F) else { return nil }

            let flags = bytes[3]
            let seq = (UInt16(bytes[4]) << 8) | UInt16(bytes[5])
            let sender = (UInt16(bytes[6]) << 8) | UInt16(bytes[7])
            let ts = (UInt32(bytes[8]) << 24)
                | (UInt32(bytes[9]) << 16)
                | (UInt32(bytes[10]) << 8)
                | UInt32(bytes[11])

            let payload = data.count > RideComProtocol.headerSize
                ? data.subdata(in: RideComProtocol.headerSize..<data.count)
                : Data()

            return RideComPacket(
                type: type,
                flags: flags,
                sequence: seq,
                senderId: sender,
                timestampMs: ts,
                payload: payload
            )
        }
    }
}
