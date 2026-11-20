import Foundation

enum JitterPreset: String, CaseIterable, Identifiable {
    case ultraLow = "Ultra-Low (20ms)"
    case balanced = "Balanced (40ms)"
    case stable = "Stable (80ms)"
    case auto = "Auto (20–100ms)"

    var id: String { rawValue }

    var targetFrames: Int {
        switch self {
        case .ultraLow: return 1
        case .balanced: return 2
        case .stable: return 4
        case .auto: return 2
        }
    }

    var isAdaptive: Bool { self == .auto }
}

enum PopResult {
    case frame(Data, UInt8)
    case missing
    case buffering
}

final class JitterBuffer {
    private struct Slot {
        let seq: Int
        let payload: Data
        let flags: UInt8
    }

    private let lock = NSLock()
    private let capacity = 32
    private var slots: [Slot?] = Array(repeating: nil, count: 32)

    private var preset: JitterPreset = .balanced
    private var currentTargetFrames: Int = 2
    private var initialized = false
    private var buffering = true
    private var nextReadSeq = 0
    private var highestSeenSeq = 0
    private var stableReads = 0
    private var consecutiveMissing = 0

    private(set) var totalExpected: Int64 = 0
    private(set) var totalLost: Int64 = 0

    func setPreset(_ newPreset: JitterPreset) {
        lock.lock()
        defer { lock.unlock() }
        preset = newPreset
        currentTargetFrames = newPreset.targetFrames
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        slots = Array(repeating: nil, count: capacity)
        initialized = false
        buffering = true
        nextReadSeq = 0
        highestSeenSeq = 0
        stableReads = 0
        consecutiveMissing = 0
        totalExpected = 0
        totalLost = 0
    }

    func currentBufferMs() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return currentTargetFrames * RideComProtocol.frameMs
    }

    func packetLossPercent() -> Float {
        lock.lock()
        defer { lock.unlock() }
        guard totalExpected > 0 else { return 0 }
        return (Float(totalLost) / Float(totalExpected)) * 100.0
    }

    func push(seq: UInt16, payload: Data, flags: UInt8) {
        lock.lock()
        defer { lock.unlock() }

        let normSeq = Int(seq) & 0xFFFF
        if !initialized {
            initialized = true
            buffering = true
            nextReadSeq = normSeq
            highestSeenSeq = normSeq
            consecutiveMissing = 0
        } else {
            let diffFromRead = seqDiff(normSeq, nextReadSeq)
            if diffFromRead < -4 || diffFromRead > 25 {
                if diffFromRead > 25 || diffFromRead < -25 {
                    slots = Array(repeating: nil, count: capacity)
                    nextReadSeq = normSeq
                    highestSeenSeq = normSeq
                    buffering = true
                    consecutiveMissing = 0
                } else {
                    return
                }
            }
            if seqDiff(normSeq, highestSeenSeq) > 0 {
                highestSeenSeq = normSeq
            }
        }
        slots[normSeq % capacity] = Slot(seq: normSeq, payload: payload, flags: flags)
    }

    func pop() -> PopResult {
        lock.lock()
        defer { lock.unlock() }

        guard initialized else { return .buffering }

        let bufferedDepth = seqDiff(highestSeenSeq, nextReadSeq) + 1
        if buffering {
            if bufferedDepth >= currentTargetFrames {
                buffering = false
                consecutiveMissing = 0
            } else {
                return .buffering
            }
        }

        let maxAllowedDepth = min(currentTargetFrames + 4, 12)
        if bufferedDepth > maxAllowedDepth {
            let skip = bufferedDepth - currentTargetFrames
            for i in 0..<skip {
                slots[(nextReadSeq + i) % capacity] = nil
            }
            nextReadSeq = (nextReadSeq + skip) & 0xFFFF
        }

        let idx = nextReadSeq % capacity
        if let slot = slots[idx], slot.seq == nextReadSeq {
            slots[idx] = nil
            nextReadSeq = (nextReadSeq + 1) & 0xFFFF
            consecutiveMissing = 0
            totalExpected += 1
            if preset.isAdaptive {
                stableReads += 1
                if stableReads >= 150 && currentTargetFrames > 1 {
                    currentTargetFrames -= 1
                    stableReads = 0
                }
            }
            return .frame(slot.payload, slot.flags)
        }

        consecutiveMissing += 1
        if consecutiveMissing >= 4 {
            slots = Array(repeating: nil, count: capacity)
            initialized = false
            buffering = true
            consecutiveMissing = 0
            return .buffering
        }

        totalExpected += 1
        totalLost += 1
        stableReads = 0
        if preset.isAdaptive && currentTargetFrames < 5 {
            currentTargetFrames += 1
        }
        nextReadSeq = (nextReadSeq + 1) & 0xFFFF
        return .missing
    }

    private func seqDiff(_ a: Int, _ b: Int) -> Int {
        var diff = (a & 0xFFFF) - (b & 0xFFFF)
        if diff > 32768 { diff -= 65536 }
        if diff < -32768 { diff += 65536 }
        return diff
    }
}
