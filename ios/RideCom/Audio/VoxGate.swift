import Foundation

final class VoxGate {
    var thresholdDb: Float = -58.0
    var hangoverMs: Double = 400.0
    private(set) var lastLevelDb: Float = -90.0
    private var lastOpenTimeMs: Double = 0.0

    func process(samples: UnsafePointer<Float>, count: Int) -> Bool {
        guard count > 0 else { return false }
        var sumSq: Double = 0.0
        for i in 0..<count {
            let v = Double(samples[i])
            sumSq += v * v
        }
        let rms = sqrt(sumSq / Double(count))
        let db: Float = rms > 1e-6 ? Float(20.0 * log10(rms)) : -90.0
        lastLevelDb = db

        let nowMs = ProcessInfo.processInfo.systemUptime * 1000.0
        if db >= thresholdDb {
            lastOpenTimeMs = nowMs
            return true
        }
        return (nowMs - lastOpenTimeMs) <= hangoverMs
    }
}
