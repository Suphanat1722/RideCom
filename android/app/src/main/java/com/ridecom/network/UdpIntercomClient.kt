package com.ridecom.network

import android.os.SystemClock
import com.ridecom.protocol.PacketType
import com.ridecom.protocol.RideComPacket
import com.ridecom.protocol.RideComProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class UdpIntercomClient(
    private val localName: String,
    private val bindPort: Int = RideComProtocol.DEFAULT_PORT
) {
    private val senderId: Int = Random.nextInt(1, 65535)
    private val txSequence = AtomicInteger(0)
    private val running = AtomicBoolean(false)

    private var socket: DatagramSocket? = null
    private var rxThread: Thread? = null
    private var keepAliveThread: Thread? = null

    @Volatile
    var peerAddress: InetAddress? = null
        private set

    @Volatile
    var peerPort: Int = bindPort
        private set

    @Volatile
    var peerName: String = ""
        private set

    @Volatile
    var lastPeerRxMs: Long = 0L
        private set

    @Volatile
    var rttMs: Int = 0
        private set

    fun start(
        targetHost: String?,
        targetPort: Int = bindPort,
        onAudioPacket: (RideComPacket) -> Unit,
        onPeerUpdate: (connected: Boolean, name: String, ip: String, rttMs: Int) -> Unit
    ) {
        if (!running.compareAndSet(false, true)) return

        val sock = DatagramSocket(null)
        sock.reuseAddress = true
        sock.soTimeout = 500
        sock.trafficClass = 0xB8 // DSCP EF (Expedited Forwarding for VoIP)
        sock.bind(InetSocketAddress(this.bindPort))
        socket = sock
        peerPort = targetPort

        rxThread = Thread({
            val buf = ByteArray(1500)
            val dgram = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    sock.receive(dgram)
                    val packet = RideComPacket.decode(dgram.data, dgram.length) ?: continue
                    if (packet.senderId == senderId) continue

                    val srcAddr = dgram.address
                    val srcPort = dgram.port
                    lastPeerRxMs = SystemClock.elapsedRealtime()

                    // Auto-learn / update peer endpoint for seamless auto-reconnect
                    if (peerAddress == null || peerAddress != srcAddr) {
                        peerAddress = srcAddr
                        peerPort = srcPort
                    }

                    when (packet.type) {
                        PacketType.AUDIO -> onAudioPacket(packet)
                        PacketType.HELLO -> {
                            peerName = packet.payload.toString(Charsets.UTF_8).ifBlank { srcAddr.hostAddress ?: "Peer" }
                            sendControl(PacketType.HELLO_ACK, localName.toByteArray(Charsets.UTF_8))
                            onPeerUpdate(true, peerName, srcAddr.hostAddress ?: "", rttMs)
                        }
                        PacketType.HELLO_ACK -> {
                            peerName = packet.payload.toString(Charsets.UTF_8).ifBlank { srcAddr.hostAddress ?: "Peer" }
                            onPeerUpdate(true, peerName, srcAddr.hostAddress ?: "", rttMs)
                        }
                        PacketType.PING -> {
                            sendControl(PacketType.PONG, packet.payload)
                        }
                        PacketType.PONG -> {
                            if (packet.payload.size >= 8) {
                                val sent = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN).long
                                val now = SystemClock.elapsedRealtime()
                                rttMs = (now - sent).coerceIn(0L, 9999L).toInt()
                                onPeerUpdate(true, peerName.ifBlank { srcAddr.hostAddress ?: "Peer" }, srcAddr.hostAddress ?: "", rttMs)
                            }
                        }
                        PacketType.BYE -> {
                            onPeerUpdate(false, peerName, srcAddr.hostAddress ?: "", 0)
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    val alive = lastPeerRxMs > 0L && (SystemClock.elapsedRealtime() - lastPeerRxMs) < 3500L
                    onPeerUpdate(alive, peerName, peerAddress?.hostAddress ?: "", if (alive) rttMs else 0)
                } catch (_: Throwable) {
                    if (!running.get()) break
                }
            }
        }, "RideCom-UdpRx").apply { start() }

        keepAliveThread = Thread({
            if (!targetHost.isNullOrBlank()) {
                try {
                    peerAddress = InetAddress.getByName(targetHost.trim())
                } catch (_: Throwable) {}
            }
            while (running.get()) {
                val target = peerAddress
                if (target != null) {
                    val alive = lastPeerRxMs > 0L && (SystemClock.elapsedRealtime() - lastPeerRxMs) < 3500L
                    if (!alive) {
                        sendControl(PacketType.HELLO, localName.toByteArray(Charsets.UTF_8))
                    } else {
                        val pingPayload = ByteBuffer.allocate(8)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putLong(SystemClock.elapsedRealtime())
                            .array()
                        sendControl(PacketType.PING, pingPayload)
                    }
                }
                try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
            }
        }, "RideCom-KeepAlive").apply { start() }
    }

    fun sendAudio(opusPayload: ByteArray, flags: Int) {
        val target = peerAddress ?: return
        val sock = socket ?: return
        val seq = txSequence.getAndIncrement() and 0xFFFF
        val packet = RideComPacket(
            type = PacketType.AUDIO,
            flags = flags,
            sequence = seq,
            senderId = senderId,
            timestampMs = SystemClock.elapsedRealtime(),
            payload = opusPayload
        ).encode()
        try {
            sock.send(DatagramPacket(packet, packet.size, target, peerPort))
        } catch (_: Throwable) {}
    }

    private fun sendControl(type: PacketType, payload: ByteArray) {
        val target = peerAddress ?: return
        val sock = socket ?: return
        val packet = RideComPacket(
            type = type,
            senderId = senderId,
            timestampMs = SystemClock.elapsedRealtime(),
            payload = payload
        ).encode()
        try {
            sock.send(DatagramPacket(packet, packet.size, target, peerPort))
        } catch (_: Throwable) {}
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val sockToClose = socket
        val target = peerAddress
        val targetPort = peerPort
        socket = null
        keepAliveThread?.interrupt()
        Thread({
            if (sockToClose != null && target != null) {
                try {
                    val bye = RideComPacket(
                        type = PacketType.BYE,
                        senderId = senderId,
                        timestampMs = SystemClock.elapsedRealtime()
                    ).encode()
                    sockToClose.send(DatagramPacket(bye, bye.size, target, targetPort))
                } catch (_: Throwable) {}
            }
            try { sockToClose?.close() } catch (_: Throwable) {}
        }, "RideCom-UdpClose").start()
        rxThread?.join(400)
        keepAliveThread?.join(400)
        rxThread = null
        keepAliveThread = null
    }
}
