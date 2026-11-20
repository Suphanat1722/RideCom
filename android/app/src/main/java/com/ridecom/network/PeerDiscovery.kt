package com.ridecom.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.ridecom.protocol.RideComProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class DiscoveredPeer(
    val name: String,
    val host: String,
    val port: Int
)

class PeerDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var registeredName: String? = null

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    fun start(deviceName: String, port: Int = RideComProtocol.DEFAULT_PORT) {
        stop()
        registerService(deviceName, port)
        startDiscovery()
    }

    private fun registerService(deviceName: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = RideComProtocol.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredName = serviceInfo.serviceName
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = listener
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {}
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == registeredName) return
                resolveService(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.name == serviceInfo.serviceName }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discListener = listener
        try {
            nsdManager.discoverServices(
                RideComProtocol.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val hostAddr = info.host?.hostAddress ?: return
                    val localIp = getLocalWifiIp()
                    if (hostAddr == localIp) return
                    val peer = DiscoveredPeer(info.serviceName, hostAddr, info.port)
                    val current = _peers.value.filterNot { it.host == hostAddr }
                    _peers.value = current + peer
                }
            })
        } catch (_: Throwable) {}
    }

    fun stop() {
        regListener?.let {
            try { nsdManager.unregisterService(it) } catch (_: Throwable) {}
        }
        discListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Throwable) {}
        }
        regListener = null
        discListener = null
        _peers.value = emptyList()
    }

    companion object {
        fun getLocalWifiIp(): String? {
            return try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
                var fallback: String? = null
                for (iface in ifaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name.lowercase()
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                                return ip
                            }
                            if (fallback == null) fallback = ip
                        }
                    }
                }
                fallback
            } catch (_: Throwable) {
                null
            }
        }

        fun guessHotspotGateway(localIp: String?): String? {
            if (localIp == null) return null
            val parts = localIp.split(".")
            if (parts.size != 4) return null
            val gw = "${parts[0]}.${parts[1]}.${parts[2]}.1"
            return if (gw == localIp) null else gw
        }

        fun defaultDeviceName(): String = Build.MODEL.ifBlank { "Android Rider" }
    }
}
