package com.ridecom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridecom.audio.AudioRoute
import com.ridecom.network.DiscoveredPeer
import com.ridecom.network.PeerDiscovery
import com.ridecom.protocol.JitterPreset
import com.ridecom.service.IntercomService
import com.ridecom.service.IntercomState
import java.util.Locale

@Composable
fun RideComApp(
    peerDiscovery: PeerDiscovery,
    isBatteryOptimizationIgnored: () -> Boolean,
    onRequestIgnoreBatteryOpt: () -> Unit
) {
    val context = LocalContext.current
    val state by IntercomService.state.collectAsState()
    val peers by peerDiscovery.peers.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf(PeerDiscovery.defaultDeviceName()) }
    var manualIp by remember { mutableStateOf("") }
    var localIp by remember { mutableStateOf(PeerDiscovery.getLocalWifiIp()) }

    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                showSettings -> SettingsScreen(
                    deviceName = deviceName,
                    state = state,
                    onDeviceNameChange = { deviceName = it },
                    onPresetChange = { IntercomService.updateJitterPreset(it) },
                    onBitrateChange = { IntercomService.updateBitrateKbps(it) },
                    onBack = { showSettings = false }
                )
                state.active -> ActiveCallScreen(
                    state = state,
                    onOpenSettings = { showSettings = true },
                    onDisconnect = { IntercomService.stop(context) }
                )
                else -> LobbyScreen(
                    localIp = localIp,
                    peers = peers,
                    manualIp = manualIp,
                    batteryIgnored = isBatteryOptimizationIgnored(),
                    onManualIpChange = { manualIp = it },
                    onRefreshIp = {
                        localIp = PeerDiscovery.getLocalWifiIp()
                        peerDiscovery.start(deviceName)
                    },
                    onConnect = { ip ->
                        IntercomService.start(context, ip, deviceName)
                    },
                    onHostListen = {
                        IntercomService.start(context, null, deviceName)
                    },
                    onOpenSettings = { showSettings = true },
                    onRequestBatteryOpt = onRequestIgnoreBatteryOpt
                )
            }
        }
    }
}

@Composable
private fun LobbyScreen(
    localIp: String?,
    peers: List<DiscoveredPeer>,
    manualIp: String,
    batteryIgnored: Boolean,
    onManualIpChange: (String) -> Unit,
    onRefreshIp: () -> Unit,
    onConnect: (String) -> Unit,
    onHostListen: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestBatteryOpt: () -> Unit
) {
    val gatewayIp = PeerDiscovery.guessHotspotGateway(localIp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("RideCom", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Offline Helmet Intercom", fontSize = 13.sp, color = Color(0xFF9AA3B2))
            }
            Row {
                IconButton(onClick = onRefreshIp) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14181F)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (localIp != null) Color(0xFF00E676) else Color(0xFFFF5252)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (localIp != null) "Local IP: $localIp" else "No Wi-Fi / Hotspot detected",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "Port 50000 • UDP + Opus 16kHz",
                        fontSize = 12.sp,
                        color = Color(0xFF8C96A6)
                    )
                }
            }
        }

        if (!batteryIgnored) {
            OutlinedButton(
                onClick = onRequestBatteryOpt,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFCA28))
            ) {
                Text("Unlock Battery Limit (Recommended for Lock Screen)")
            }
        }

        Button(
            onClick = onHostListen,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
        ) {
            Text("Start Listening (Hotspot Host)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        if (gatewayIp != null) {
            OutlinedButton(
                onClick = { onConnect(gatewayIp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Connect to Hotspot Host ($gatewayIp)")
            }
        }

        Text("Nearby Riders (Auto-Discovery)", fontWeight = FontWeight.SemiBold, color = Color(0xFFB0BAC9))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (peers.isEmpty()) {
                item {
                    Text(
                        "Scanning local Wi-Fi via Bonjour/mDNS...",
                        color = Color(0xFF6B7482),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(peers) { peer ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A202B)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConnect(peer.host) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(peer.name, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("${peer.host}:${peer.port}", fontSize = 12.sp, color = Color(0xFF9AA3B2))
                            }
                            Text("CONNECT", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = onManualIpChange,
                label = { Text("Manual Peer IP") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { if (manualIp.isNotBlank()) onConnect(manualIp.trim()) },
                modifier = Modifier.height(54.dp)
            ) {
                Text("Join")
            }
        }
    }
}

@Composable
private fun ActiveCallScreen(
    state: IntercomState,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header & Live Telemetry
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (state.peerConnected) state.peerName.ifBlank { "Rider Connected" } else "Waiting for Rider...",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.peerConnected) Color(0xFF00E676) else Color(0xFFFFCA28)
                    )
                    Text(
                        text = state.peerIp.ifBlank { "Listening on UDP :50000" },
                        fontSize = 13.sp,
                        color = Color(0xFF8C96A6)
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                color = Color(0xFF14181F),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatBadge("RTT", "${state.rttMs} ms")
                    StatBadge("LOSS", String.format(Locale.US, "%.1f%%", state.packetLossPct))
                    StatBadge("BUF", "${state.bufferMs} ms")
                    StatBadge("AUDIO", if (state.audioRoute == AudioRoute.BLUETOOTH_HFP) "BT HFP" else state.audioRoute.label)
                }
            }
        }

        // Giant Glove-Friendly Main Button
        val btnColor = when {
            state.muted -> Color(0xFF424752)
            state.pttMode && state.pttPressed -> Color(0xFF00E676)
            state.pttMode -> Color(0xFF1E293B)
            state.voxOpen -> Color(0xFF00E676)
            else -> Color(0xFF153827)
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(210.dp)
                .clip(CircleShape)
                .background(btnColor)
                .pointerInput(state.pttMode) {
                    if (state.pttMode) {
                        detectTapGestures(
                            onPress = {
                                IntercomService.instance?.setPttPressed(true)
                                tryAwaitRelease()
                                IntercomService.instance?.setPttPressed(false)
                            }
                        )
                    } else {
                        detectTapGestures(
                            onTap = { IntercomService.instance?.toggleMute() }
                        )
                    }
                }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (state.muted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        state.muted -> "MUTED"
                        state.pttMode && state.pttPressed -> "TALKING"
                        state.pttMode -> "HOLD TO TALK"
                        state.voxOpen -> "LIVE (VOX)"
                        else -> "OPEN MIC"
                    },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // VOX Wind Gate & Controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = !state.pttMode,
                    onClick = { IntercomService.instance?.setPttMode(false) },
                    label = { Text("Open Mic (VOX)") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = state.pttMode,
                    onClick = { IntercomService.instance?.setPttMode(true) },
                    label = { Text("Push-To-Talk") },
                    modifier = Modifier.weight(1f)
                )
            }

            if (!state.pttMode) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Wind Gate (VOX)", color = Color(0xFFB0BAC9), fontSize = 13.sp)
                        Text(
                            "${state.voxThresholdDb.toInt()} dB (In: ${state.inputLevelDb.toInt()} dB)",
                            color = Color(0xFF00E676),
                            fontSize = 13.sp
                        )
                    }
                    val normalizedLevel = ((state.inputLevelDb + 70f) / 70f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { normalizedLevel },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .padding(vertical = 2.dp),
                        color = if (state.voxOpen) Color(0xFF00E676) else Color(0xFF475569)
                    )
                    Slider(
                        value = state.voxThresholdDb,
                        onValueChange = { IntercomService.instance?.setVoxThreshold(it) },
                        valueRange = -70f..-10f
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { IntercomService.instance?.toggleAudioRoute() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(
                        if (state.preferBluetooth) Icons.Default.BluetoothAudio else Icons.Default.VolumeUp,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.preferBluetooth) "Bluetooth" else "Speaker")
                }

                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("End", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    deviceName: String,
    state: IntercomState,
    onDeviceNameChange: (String) -> Unit,
    onPresetChange: (JitterPreset) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)

        OutlinedTextField(
            value = deviceName,
            onValueChange = onDeviceNameChange,
            label = { Text("Rider Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Jitter Buffer Preset", fontWeight = FontWeight.SemiBold, color = Color(0xFFB0BAC9))
        JitterPreset.entries.forEach { preset ->
            FilterChip(
                selected = state.jitterPreset == preset,
                onClick = { onPresetChange(preset) },
                label = { Text(preset.label) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text("Opus Bitrate (VoIP)", fontWeight = FontWeight.SemiBold, color = Color(0xFFB0BAC9))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(16, 24, 32).forEach { kbps ->
                FilterChip(
                    selected = state.bitrateKbps == kbps,
                    onClick = { onBitrateChange(kbps) },
                    label = { Text("$kbps kbps") }
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("Done")
        }
    }
}

@Composable
private fun StatBadge(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 10.sp, color = Color(0xFF7B8794))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
