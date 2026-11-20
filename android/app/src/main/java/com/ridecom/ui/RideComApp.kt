package com.ridecom.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridecom.R
import com.ridecom.audio.AudioRoute
import com.ridecom.data.RideComPrefs
import com.ridecom.network.DiscoveredPeer
import com.ridecom.network.PeerDiscovery
import com.ridecom.protocol.JitterPreset
import com.ridecom.service.IntercomService
import com.ridecom.service.IntercomState
import java.util.Locale

private val BgCanvas = Color(0xFF070A0F)
private val CardBg = Color(0xFF11161F)
private val CardBorder = Color(0xFF1E293B)
private val AccentEmerald = Color(0xFF00E676)
private val AccentCyan = Color(0xFF00D2FF)
private val AccentAmber = Color(0xFFFFB300)
private val AccentRed = Color(0xFFFF4757)
private val TextSecondary = Color(0xFF8B98A9)

@Composable
fun RideComApp(
    prefs: RideComPrefs,
    peerDiscovery: PeerDiscovery,
    isBatteryOptimizationIgnored: () -> Boolean,
    onRequestIgnoreBatteryOpt: () -> Unit
) {
    val context = LocalContext.current
    val state by IntercomService.state.collectAsState()
    val peers by peerDiscovery.peers.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var manualIp by remember { mutableStateOf(prefs.lastPeerIp) }
    var localIp by remember { mutableStateOf(PeerDiscovery.getLocalWifiIp()) }

    val musicPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            IntercomService.instance?.loadMusic(uri)
        }
    }

    val appAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            IntercomService.instance?.startAppAudioShare(result.resultCode, data)
        }
    }

    Scaffold(containerColor = BgCanvas) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                showSettings -> SettingsScreen(
                    deviceName = deviceName,
                    state = state,
                    onDeviceNameChange = {
                        deviceName = it
                        prefs.deviceName = it
                    },
                    onPresetChange = { IntercomService.updateJitterPreset(prefs, it) },
                    onBitrateChange = { IntercomService.updateBitrateKbps(prefs, it) },
                    onAudioCuesChange = { IntercomService.updateAudioCues(prefs, it) },
                    onBack = { showSettings = false }
                )
                state.active -> ActiveCallScreen(
                    state = state,
                    onPickMusic = { musicPicker.launch(arrayOf("audio/*")) },
                    onToggleAppAudio = {
                        if (state.appAudioSharing) {
                            IntercomService.instance?.stopAppAudioShare()
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            appAudioLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    onOpenSettings = { showSettings = true },
                    onDisconnect = { IntercomService.stop(context) }
                )
                else -> LobbyScreen(
                    localIp = localIp,
                    lastPeerIp = prefs.lastPeerIp,
                    lastPeerName = prefs.lastPeerName,
                    peers = peers,
                    manualIp = manualIp,
                    batteryIgnored = isBatteryOptimizationIgnored(),
                    onManualIpChange = { manualIp = it },
                    onRefreshIp = {
                        localIp = PeerDiscovery.getLocalWifiIp()
                        peerDiscovery.start(deviceName)
                    },
                    onConnect = { ip ->
                        prefs.lastPeerIp = ip
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
    lastPeerIp: String,
    lastPeerName: String,
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
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Branded Header with RideCom Logo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.ridecom_logo),
                    contentDescription = "RideCom Logo",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AccentEmerald.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("RideCom", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("v1.1.1 • Native Opus Intercom", fontSize = 12.sp, color = AccentCyan)
                }
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

        // Network Status Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background((if (localIp != null) AccentEmerald else AccentRed).copy(alpha = 0.15f))
                ) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (localIp != null) AccentEmerald else AccentRed
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (localIp != null) localIp else "No Wi-Fi / Hotspot",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        text = "UDP :50000 • 16kHz VoIP + FEC",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Surface(
                    color = (if (localIp != null) AccentEmerald else AccentRed).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = if (localIp != null) "READY" else "OFFLINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (localIp != null) AccentEmerald else AccentRed,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (!batteryIgnored) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AccentAmber.copy(alpha = 0.12f)),
                border = BorderStroke(1.dp, AccentAmber.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRequestBatteryOpt() }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = AccentAmber)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Unlock Battery Limit", fontWeight = FontWeight.Bold, color = AccentAmber, fontSize = 13.sp)
                        Text("Tap to keep voice smooth while screen is locked", fontSize = 11.sp, color = Color(0xFFD6DCE5))
                    }
                }
            }
        }

        // Quick 1-Tap Reconnect to Last Rider
        if (lastPeerIp.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConnect(lastPeerIp) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null, tint = AccentCyan)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Reconnect: ${lastPeerName.ifBlank { lastPeerIp }}",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(lastPeerIp, fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                    Surface(color = AccentCyan, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "JOIN",
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Primary Action: Start Hosting (Listen)
        Button(
            onClick = onHostListen,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald, contentColor = Color(0xFF002110))
        ) {
            Icon(Icons.Default.Podcasts, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Start Hosting (Wait for Rider)", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }

        // Hotspot Host Quick Connect
        if (gatewayIp != null) {
            OutlinedButton(
                onClick = { onConnect(gatewayIp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.55f))
            ) {
                Icon(Icons.Default.Router, contentDescription = null, tint = AccentEmerald)
                Spacer(Modifier.width(8.dp))
                Text("Connect to Hotspot Host ($gatewayIp)", color = AccentEmerald, fontWeight = FontWeight.Bold)
            }
        }

        // Nearby Riders Auto-Discovery
        Text(
            "NEARBY RIDERS",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = TextSecondary
        )

        if (peers.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Scanning local Wi-Fi via Bonjour/mDNS...",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            peers.forEach { peer ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(14.dp),
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
                            Text("${peer.host}:${peer.port}", fontSize = 12.sp, color = TextSecondary)
                        }
                        Text("CONNECT", color = AccentEmerald, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Manual IP Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = onManualIpChange,
                    label = { Text("Direct Peer IP") },
                    placeholder = { Text("192.168.x.x") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentEmerald,
                        unfocusedBorderColor = CardBorder
                    ),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { if (manualIp.isNotBlank()) onConnect(manualIp.trim()) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(54.dp)
                ) {
                    Text("Join", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ActiveCallScreen(
    state: IntercomState,
    onPickMusic: () -> Unit,
    onToggleAppAudio: () -> Unit,
    onOpenSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    val normLevel = ((state.inputLevelDb + 70f) / 70f).coerceIn(0f, 1f)
    val pulseScale by animateFloatAsState(
        targetValue = if (state.voxOpen && !state.muted) 1.0f + (normLevel * 0.14f) else 1.0f,
        label = "micPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Status + Telemetry Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (state.peerConnected) AccentEmerald else AccentAmber)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (state.peerConnected) state.peerName.ifBlank { "Rider Connected" } else "Waiting for Rider...",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = state.peerIp.ifBlank { "Hosting on UDP :50000" },
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatBadge("RTT", "${state.rttMs} ms", if (state.rttMs in 1..60) AccentEmerald else Color.White)
                StatBadge("LOSS", String.format(Locale.US, "%.1f%%", state.packetLossPct), if (state.packetLossPct < 3f) AccentEmerald else AccentAmber)
                StatBadge("BUF", "${state.bufferMs} ms", AccentCyan)
                StatBadge("ROUTE", if (state.audioRoute == AudioRoute.BLUETOOTH_HFP) "BT HFP" else state.audioRoute.label, Color.White)
            }
        }

        // Giant Animated Glove-Friendly Mic / PTT Circle
        val coreBrush = when {
            state.muted -> Brush.radialGradient(listOf(Color(0xFF4B5563), Color(0xFF1F2937)))
            state.pttMode && state.pttPressed -> Brush.radialGradient(listOf(AccentEmerald, Color(0xFF00A854)))
            state.pttMode -> Brush.radialGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
            state.voxOpen -> Brush.radialGradient(listOf(AccentEmerald, Color(0xFF00994D)))
            else -> Brush.radialGradient(listOf(Color(0xFF123524), Color(0xFF0A1F15)))
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(vertical = 6.dp)
                .size(230.dp)
        ) {
            // Animated outer pulse aura
            if (state.voxOpen && !state.muted) {
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(AccentEmerald.copy(alpha = 0.22f))
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(196.dp)
                    .clip(CircleShape)
                    .background(coreBrush)
                    .border(
                        width = 2.dp,
                        color = if (state.muted) AccentRed else if (state.voxOpen) AccentEmerald else CardBorder,
                        shape = CircleShape
                    )
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
                        modifier = Modifier.size(62.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            state.muted -> "MUTED"
                            state.pttMode && state.pttPressed -> "TALKING"
                            state.pttMode -> "HOLD TO TALK"
                            state.voxOpen -> "LIVE (VOX)"
                            else -> "OPEN MIC"
                        },
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (state.pttMode) "Press & Hold" else "Tap to Mute",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Open Mic vs PTT Segmented Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg)
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeSegment("Open Mic (VOX)", selected = !state.pttMode, modifier = Modifier.weight(1f)) {
                IntercomService.instance?.setPttMode(false)
            }
            ModeSegment("Push-To-Talk", selected = state.pttMode, modifier = Modifier.weight(1f)) {
                IntercomService.instance?.setPttMode(true)
            }
        }

        // VOX Wind Gate Card
        if (!state.pttMode) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Wind Gate (VOX)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "${state.voxThresholdDb.toInt()} dB  •  In: ${state.inputLevelDb.toInt()} dB",
                            color = AccentEmerald,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { normLevel },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (state.voxOpen) AccentEmerald else Color(0xFF475569),
                        trackColor = Color(0xFF1E293B)
                    )
                    Slider(
                        value = state.voxThresholdDb,
                        onValueChange = { IntercomService.instance?.setVoxThreshold(it) },
                        valueRange = -70f..-10f,
                        colors = SliderDefaults.colors(thumbColor = AccentEmerald, activeTrackColor = AccentEmerald)
                    )
                }
            }
        }

        // RideDJ Shared Music & App Audio Card (with Auto Voice Ducking)
        val sharingAnyAudio = state.musicPlaying || state.appAudioSharing
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, if (sharingAnyAudio) AccentCyan.copy(alpha = 0.55f) else CardBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentCyan)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = when {
                                    state.appAudioSharing -> "Sharing App Audio (Live)"
                                    state.musicLoaded -> state.musicTrackTitle
                                    else -> "Share Audio (RideDJ)"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    state.appAudioSharing -> "YouTube / Web / Apps • Auto-ducks on voice"
                                    state.musicLoaded -> "Auto-ducks when riding partners talk"
                                    else -> "Share YouTube/Web audio or local MP3"
                                },
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (state.musicLoaded) {
                        Row {
                            IconButton(onClick = { IntercomService.instance?.toggleMusicPlayPause() }) {
                                Icon(
                                    if (state.musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = AccentCyan
                                )
                            }
                            IconButton(onClick = { IntercomService.instance?.stopMusic() }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop Music", tint = TextSecondary)
                            }
                        }
                    }
                }

                if (!state.musicLoaded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onToggleAppAudio,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.appAudioSharing) AccentRed else AccentCyan,
                                contentColor = if (state.appAudioSharing) Color.White else Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (state.appAudioSharing) "Stop App Audio" else "Share App Audio",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        OutlinedButton(
                            onClick = onPickMusic,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AccentCyan),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pick MP3/M4A", color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (state.musicLoaded || state.appAudioSharing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Vol ${(state.musicVolume * 100).toInt()}%", color = TextSecondary, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = state.musicVolume,
                            onValueChange = { IntercomService.instance?.setMusicVolume(it) },
                            valueRange = 0.05f..0.85f,
                            colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Bottom Glove-Friendly Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { IntercomService.instance?.toggleAudioRoute() },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            ) {
                Icon(
                    if (state.preferBluetooth) Icons.Default.BluetoothAudio else Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(Modifier.width(6.dp))
                Text(if (state.preferBluetooth) "Bluetooth" else "Speaker", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onDisconnect,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("End Ride", color = Color.White, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentEmerald else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color(0xFF002010) else TextSecondary
        )
    }
}

@Composable
private fun SettingsScreen(
    deviceName: String,
    state: IntercomState,
    onDeviceNameChange: (String) -> Unit,
    onPresetChange: (JitterPreset) -> Unit,
    onBitrateChange: (Int) -> Unit,
    onAudioCuesChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }

        OutlinedTextField(
            value = deviceName,
            onValueChange = onDeviceNameChange,
            label = { Text("Rider Display Name") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentEmerald,
                unfocusedBorderColor = CardBorder
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            border = BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("In-Helmet Audio Cues", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Play soft chime on connect, disconnect & mute", color = TextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = state.audioCuesEnabled,
                    onCheckedChange = onAudioCuesChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = AccentEmerald)
                )
            }
        }

        Text("JITTER BUFFER PRESET", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = TextSecondary)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JitterPreset.entries.forEach { preset ->
                val selected = state.jitterPreset == preset
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (selected) AccentEmerald.copy(alpha = 0.14f) else CardBg),
                    border = BorderStroke(1.dp, if (selected) AccentEmerald else CardBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPresetChange(preset) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(preset.label, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        if (selected) {
                            Text("ACTIVE", color = AccentEmerald, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }

        Text("OPUS BITRATE (VOIP + FEC)", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(16, 24, 32).forEach { kbps ->
                val selected = state.bitrateKbps == kbps
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) AccentEmerald else CardBg)
                        .border(1.dp, if (selected) AccentEmerald else CardBorder, RoundedCornerShape(12.dp))
                        .clickable { onBitrateChange(kbps) }
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        "$kbps kbps",
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.Black else Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "RideCom v1.0.0 • Native C libopus 1.5.2 • 100% Offline UDP",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun StatBadge(title: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = valueColor)
    }
}
