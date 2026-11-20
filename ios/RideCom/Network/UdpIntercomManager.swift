import Darwin
import Foundation

final class UdpIntercomManager {
    private let localName: String
    private let port: UInt16
    private let senderId: UInt16 = UInt16.random(in: 1...65534)

    private var sockFd: Int32 = -1
    private var isRunning = false
    private var txSeq: UInt16 = 0
    private let lock = NSLock()

    private var peerIp: String?
    private var peerPort: UInt16
    private var peerName: String = ""
    private var lastPeerRxMs: Double = 0
    private var rttMs: Int = 0

    private let rxQueue = DispatchQueue(label: "com.ridecom.udp.rx", qos: .userInteractive)
    private let keepAliveQueue = DispatchQueue(label: "com.ridecom.udp.keepalive", qos: .utility)
    private var keepAliveTimer: DispatchSourceTimer?

    init(localName: String, port: UInt16 = RideComProtocol.defaultPort) {
        self.localName = localName
        self.port = port
        self.peerPort = port
    }

    func start(
        targetHost: String?,
        targetPort: UInt16 = RideComProtocol.defaultPort,
        onAudioPacket: @escaping (RideComPacket) -> Void,
        onPeerUpdate: @escaping (_ connected: Bool, _ name: String, _ ip: String, _ rttMs: Int) -> Void
    ) throws {
        lock.lock()
        guard !isRunning else {
            lock.unlock()
            return
        }

        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else {
            lock.unlock()
            throw NSError(domain: "RideCom.UDP", code: Int(errno), userInfo: [NSLocalizedDescriptionKey: "Failed to create UDP socket"])
        }

        var reuse: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &reuse, socklen_t(MemoryLayout<Int32>.size))

        var tos: Int32 = 0xB8 // DSCP EF
        setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, socklen_t(MemoryLayout<Int32>.size))

        var tv = timeval(tv_sec: 0, tv_usec: 500_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: INADDR_ANY.bigEndian)

        let bindRes = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindRes == 0 else {
            Darwin.close(fd)
            lock.unlock()
            throw NSError(domain: "RideCom.UDP", code: Int(errno), userInfo: [NSLocalizedDescriptionKey: "Failed to bind UDP port \(port)"])
        }

        self.sockFd = fd
        self.isRunning = true
        if let host = targetHost, !host.isEmpty {
            self.peerIp = host
            self.peerPort = targetPort
        }
        lock.unlock()

        rxQueue.async { [weak self] in
            self?.receiveLoop(onAudioPacket: onAudioPacket, onPeerUpdate: onPeerUpdate)
        }

        let timer = DispatchSource.makeTimerSource(queue: keepAliveQueue)
        timer.schedule(deadline: .now() + .milliseconds(200), repeating: .seconds(1))
        timer.setEventHandler { [weak self] in
            self?.sendKeepAliveTick()
        }
        keepAliveTimer = timer
        timer.resume()
    }

    func sendAudio(opusPayload: Data, flags: UInt8) {
        lock.lock()
        guard isRunning, sockFd >= 0, let ip = peerIp else {
            lock.unlock()
            return
        }
        let seq = txSeq
        txSeq = txSeq &+ 1
        let portToUse = peerPort
        let fd = sockFd
        lock.unlock()

        let packet = RideComPacket(
            type: .audio,
            flags: flags,
            sequence: seq,
            senderId: senderId,
            timestampMs: currentUptimeMs32(),
            payload: opusPayload
        ).encode()

        sendRaw(packet, toIp: ip, port: portToUse, fd: fd)
    }

    func stop() {
        lock.lock()
        guard isRunning else {
            lock.unlock()
            return
        }
        isRunning = false
        let fd = sockFd
        let ip = peerIp
        let portToUse = peerPort
        sockFd = -1
        lock.unlock()

        keepAliveTimer?.cancel()
        keepAliveTimer = nil

        if fd >= 0 {
            if let ip = ip {
                let bye = RideComPacket(
                    type: .bye,
                    senderId: senderId,
                    timestampMs: currentUptimeMs32()
                ).encode()
                sendRaw(bye, toIp: ip, port: portToUse, fd: fd)
            }
            Darwin.close(fd)
        }
    }

    private func receiveLoop(
        onAudioPacket: @escaping (RideComPacket) -> Void,
        onPeerUpdate: @escaping (_ connected: Bool, _ name: String, _ ip: String, _ rttMs: Int) -> Void
    ) {
        var buffer = [UInt8](repeating: 0, count: 1500)
        while true {
            lock.lock()
            let running = isRunning
            let fd = sockFd
            lock.unlock()
            guard running, fd >= 0 else { break }

            var srcAddr = sockaddr_in()
            var addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let received = withUnsafeMutablePointer(to: &srcAddr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(fd, &buffer, buffer.count, 0, $0, &addrLen)
                }
            }

            let nowMs = ProcessInfo.processInfo.systemUptime * 1000.0
            if received <= 0 {
                lock.lock()
                let alive = lastPeerRxMs > 0 && (nowMs - lastPeerRxMs) < 3500.0
                let name = peerName
                let ip = peerIp ?? ""
                let rtt = alive ? rttMs : 0
                lock.unlock()
                onPeerUpdate(alive, name, ip, rtt)
                continue
            }

            let data = Data(bytes: buffer, count: received)
            guard let packet = RideComPacket.decode(data), packet.senderId != senderId else { continue }

            var ipBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var sinAddr = srcAddr.sin_addr
            inet_ntop(AF_INET, &sinAddr, &ipBuf, socklen_t(INET_ADDRSTRLEN))
            let srcIp = String(cString: ipBuf)
            let srcPort = UInt16(bigEndian: srcAddr.sin_port)

            lock.lock()
            lastPeerRxMs = nowMs
            if peerIp == nil || peerIp != srcIp {
                peerIp = srcIp
                peerPort = srcPort
            }
            lock.unlock()

            switch packet.type {
            case .audio:
                onAudioPacket(packet)
            case .hello:
                let name = String(data: packet.payload, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
                let resolvedName = (name?.isEmpty == false) ? name! : srcIp
                lock.lock()
                peerName = resolvedName
                let rtt = rttMs
                lock.unlock()
                sendControl(type: .helloAck, payload: Data(localName.utf8))
                onPeerUpdate(true, resolvedName, srcIp, rtt)
            case .helloAck:
                let name = String(data: packet.payload, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
                let resolvedName = (name?.isEmpty == false) ? name! : srcIp
                lock.lock()
                peerName = resolvedName
                let rtt = rttMs
                lock.unlock()
                onPeerUpdate(true, resolvedName, srcIp, rtt)
            case .ping:
                sendControl(type: .pong, payload: packet.payload)
            case .pong:
                if packet.payload.count >= 8 {
                    let sentMs = packet.payload.withUnsafeBytes { raw -> Int64 in
                        let v = raw.load(as: Int64.self)
                        return Int64(bigEndian: v)
                    }
                    let measured = max(0, min(9999, Int(Int64(nowMs) - sentMs)))
                    lock.lock()
                    rttMs = measured
                    let name = peerName.isEmpty ? srcIp : peerName
                    lock.unlock()
                    onPeerUpdate(true, name, srcIp, measured)
                }
            case .bye:
                lock.lock()
                let name = peerName
                lock.unlock()
                onPeerUpdate(false, name, srcIp, 0)
            }
        }
    }

    private func sendKeepAliveTick() {
        lock.lock()
        guard isRunning, peerIp != nil else {
            lock.unlock()
            return
        }
        let nowMs = ProcessInfo.processInfo.systemUptime * 1000.0
        let alive = lastPeerRxMs > 0 && (nowMs - lastPeerRxMs) < 3500.0
        lock.unlock()

        if !alive {
            sendControl(type: .hello, payload: Data(localName.utf8))
        } else {
            var nowBE = Int64(nowMs).bigEndian
            let payload = withUnsafeBytes(of: &nowBE) { Data($0) }
            sendControl(type: .ping, payload: payload)
        }
    }

    private func sendControl(type: PacketType, payload: Data) {
        lock.lock()
        guard isRunning, sockFd >= 0, let ip = peerIp else {
            lock.unlock()
            return
        }
        let portToUse = peerPort
        let fd = sockFd
        lock.unlock()

        let packet = RideComPacket(
            type: type,
            senderId: senderId,
            timestampMs: currentUptimeMs32(),
            payload: payload
        ).encode()

        sendRaw(packet, toIp: ip, port: portToUse, fd: fd)
    }

    private func sendRaw(_ data: Data, toIp: String, port: UInt16, fd: Int32) {
        var dest = sockaddr_in()
        dest.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        dest.sin_family = sa_family_t(AF_INET)
        dest.sin_port = port.bigEndian
        guard inet_pton(AF_INET, toIp, &dest.sin_addr) == 1 else { return }

        data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            withUnsafePointer(to: &dest) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    _ = sendto(fd, base, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    private func currentUptimeMs32() -> UInt32 {
        UInt32(truncatingIfNeeded: UInt64(ProcessInfo.processInfo.systemUptime * 1000.0))
    }
}
