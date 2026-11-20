import Darwin
import Foundation
import Network
import UIKit

struct DiscoveredPeer: Identifiable, Equatable {
    var id: String { "\(host):\(port)" }
    let name: String
    let host: String
    let port: UInt16
}

final class PeerDiscovery: ObservableObject {
    @Published private(set) var peers: [DiscoveredPeer] = []

    private var listener: NWListener?
    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "com.ridecom.discovery")
    private var localName: String = UIDevice.current.name

    func start(deviceName: String) {
        stop()
        localName = deviceName
        startAdvertising(name: deviceName)
        startBrowsing()
    }

    private func startAdvertising(name: String) {
        do {
            let params = NWParameters.udp
            params.allowLocalEndpointReuse = true
            let nwListener = try NWListener(using: params)
            nwListener.service = NWListener.Service(name: name, type: RideComProtocol.serviceType)
            nwListener.newConnectionHandler = { conn in
                conn.cancel()
            }
            nwListener.start(queue: queue)
            listener = nwListener
        } catch {
            // Non-fatal: manual IP / hotspot gateway still works
        }
    }

    private func startBrowsing() {
        let descriptor = NWBrowser.Descriptor.bonjour(type: RideComProtocol.serviceType, domain: "local.")
        let params = NWParameters()
        params.includePeerToPeer = false
        let nwBrowser = NWBrowser(for: descriptor, using: params)

        nwBrowser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self = self else { return }
            for result in results {
                if case let .service(name, _, _, _) = result.endpoint {
                    if name == self.localName { continue }
                    self.resolveEndpoint(result.endpoint, name: name)
                }
            }
        }
        nwBrowser.start(queue: queue)
        browser = nwBrowser
    }

    private func resolveEndpoint(_ endpoint: NWEndpoint, name: String) {
        let conn = NWConnection(to: endpoint, using: .udp)
        conn.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            if case .ready = state {
                if let remote = conn.currentPath?.remoteEndpoint,
                   case let .hostPort(host, _) = remote {
                    let rawHost = "\(host)".components(separatedBy: "%").first ?? "\(host)"
                    let localIp = Self.getLocalWifiIp()
                    if rawHost != localIp && !rawHost.contains(":") {
                        let peer = DiscoveredPeer(name: name, host: rawHost, port: RideComProtocol.defaultPort)
                        DispatchQueue.main.async {
                            if !self.peers.contains(where: { $0.host == rawHost }) {
                                self.peers.append(peer)
                            }
                        }
                    }
                }
                conn.cancel()
            } else if case .failed = state {
                conn.cancel()
            }
        }
        conn.start(queue: queue)
    }

    func stop() {
        listener?.cancel(); listener = nil
        browser?.cancel(); browser = nil
        DispatchQueue.main.async {
            self.peers = []
        }
    }

    static func getLocalWifiIp() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }

        var fallback: String?
        var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = ptr {
            let flags = Int32(current.pointee.ifa_flags)
            let isUp = (flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING)
            let isLoopback = (flags & IFF_LOOPBACK) != 0

            if isUp && !isLoopback, let addr = current.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: current.pointee.ifa_name)
                var hostBuffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &hostBuffer, socklen_t(hostBuffer.count), nil, 0, NI_NUMERICHOST) == 0 {
                    let ip = String(cString: hostBuffer)
                    if name == "en0" || name.hasPrefix("bridge") {
                        return ip
                    }
                    if fallback == nil { fallback = ip }
                }
            }
            ptr = current.pointee.ifa_next
        }
        return fallback
    }

    static func guessHotspotGateway(localIp: String?) -> String? {
        guard let ip = localIp else { return nil }
        let parts = ip.split(separator: ".")
        guard parts.count == 4 else { return nil }
        let gw = "\(parts[0]).\(parts[1]).\(parts[2]).1"
        return gw == ip ? nil : gw
    }
}
