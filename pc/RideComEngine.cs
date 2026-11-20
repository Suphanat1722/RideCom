using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Concentus.Enums;
using Concentus.Structs;
using NAudio.Wave;

namespace RideComPC;

public static class RideComProtocol
{
    public const byte Magic0 = 0x52; // 'R'
    public const byte Magic1 = 0x43; // 'C'
    public const byte Version = 1;
    public const int HeaderSize = 12;
    public const int DefaultPort = 50000;

    public const int SampleRate = 16000;
    public const int Channels = 1;
    public const int FrameMs = 20;
    public const int FrameSamples = 320; // 16000 * 20 / 1000
    public const int FrameBytes = FrameSamples * 2;

    public const byte FlagVoiceActive = 1 << 0;
}

public enum PacketType : byte
{
    Audio = 0x1,
    Hello = 0x2,
    HelloAck = 0x3,
    Ping = 0x4,
    Pong = 0x5,
    Bye = 0x6
}

public sealed record RideComStatus(
    bool Active,
    bool PeerConnected,
    string PeerName,
    string PeerIp,
    int RttMs,
    float PacketLossPct,
    float InputLevelDb,
    bool VoxOpen
);

/// <summary>
/// Sequence-ordered jitter buffer with 60ms pre-buffer and clean talkspurt re-sync.
/// </summary>
internal sealed class PcJitterBuffer
{
    private const int Capacity = 32;
    private readonly (int Seq, byte[] Payload)?[] _slots = new (int, byte[])?[Capacity];
    private readonly object _lock = new();

    private const int TargetFrames = 3; // 60ms smooth buffer for PC WaveOut
    private bool _initialized;
    private bool _buffering = true;
    private int _nextReadSeq;
    private int _highestSeenSeq;
    private int _consecutiveMissing;

    public long TotalExpected { get; private set; }
    public long TotalLost { get; private set; }

    public void Reset()
    {
        lock (_lock)
        {
            Array.Clear(_slots);
            _initialized = false;
            _buffering = true;
            _nextReadSeq = 0;
            _highestSeenSeq = 0;
            _consecutiveMissing = 0;
            TotalExpected = 0;
            TotalLost = 0;
        }
    }

    public float PacketLossPercent()
    {
        lock (_lock)
        {
            return TotalExpected > 0 ? (TotalLost * 100f / TotalExpected) : 0f;
        }
    }

    public void Push(ushort seq, byte[] payload)
    {
        lock (_lock)
        {
            int normSeq = seq & 0xFFFF;
            if (!_initialized)
            {
                _initialized = true;
                _buffering = true;
                _nextReadSeq = normSeq;
                _highestSeenSeq = normSeq;
                _consecutiveMissing = 0;
            }
            else
            {
                int diff = SeqDiff(normSeq, _nextReadSeq);
                if (diff < -4 || diff > 25)
                {
                    if (diff > 25 || diff < -25)
                    {
                        Array.Clear(_slots);
                        _nextReadSeq = normSeq;
                        _highestSeenSeq = normSeq;
                        _buffering = true;
                        _consecutiveMissing = 0;
                    }
                    else
                    {
                        return;
                    }
                }
                if (SeqDiff(normSeq, _highestSeenSeq) > 0)
                {
                    _highestSeenSeq = normSeq;
                }
            }
            _slots[normSeq % Capacity] = (normSeq, payload);
        }
    }

    /// <summary>
    /// Returns: 1 = got frame, 0 = missing (use PLC), -1 = buffering/idle (silence).
    /// </summary>
    public int Pop(out byte[]? payload)
    {
        lock (_lock)
        {
            payload = null;
            if (!_initialized) return -1;

            int depth = SeqDiff(_highestSeenSeq, _nextReadSeq) + 1;
            if (_buffering)
            {
                if (depth >= TargetFrames)
                {
                    _buffering = false;
                    _consecutiveMissing = 0;
                }
                else
                {
                    return -1;
                }
            }

            if (depth > TargetFrames + 5)
            {
                int skip = depth - TargetFrames;
                for (int i = 0; i < skip; i++)
                {
                    _slots[(_nextReadSeq + i) % Capacity] = null;
                }
                _nextReadSeq = (_nextReadSeq + skip) & 0xFFFF;
            }

            int idx = _nextReadSeq % Capacity;
            var slot = _slots[idx];
            if (slot.HasValue && slot.Value.Seq == _nextReadSeq)
            {
                _slots[idx] = null;
                _nextReadSeq = (_nextReadSeq + 1) & 0xFFFF;
                _consecutiveMissing = 0;
                TotalExpected++;
                payload = slot.Value.Payload;
                return 1;
            }

            _consecutiveMissing++;
            if (_consecutiveMissing >= 4)
            {
                Array.Clear(_slots);
                _initialized = false;
                _buffering = true;
                _consecutiveMissing = 0;
                return -1;
            }

            TotalExpected++;
            TotalLost++;
            _nextReadSeq = (_nextReadSeq + 1) & 0xFFFF;
            return 0;
        }
    }

    private static int SeqDiff(int a, int b)
    {
        int d = (a & 0xFFFF) - (b & 0xFFFF);
        if (d > 32768) d -= 65536;
        if (d < -32768) d += 65536;
        return d;
    }
}

public sealed class RideComEngine : IDisposable
{
    private readonly ushort _senderId = (ushort)Random.Shared.Next(1, 65535);
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly object _encodeLock = new();
    private readonly object _decodeLock = new();
    private readonly object _peerLock = new();

    private readonly OpusEncoder _encoder;
    private readonly OpusDecoder _decoder;
    private readonly PcJitterBuffer _jitterBuffer = new();

    private UdpClient? _udp;
    private WaveInEvent? _waveIn;
    private WaveOutEvent? _waveOut;
    private BufferedWaveProvider? _playBuffer;
    private CancellationTokenSource? _cts;

    private IPEndPoint? _peerEndpoint;
    private string _peerName = "";
    private long _lastPeerRxMs;
    private int _rttMs;
    private ushort _txSeq;

    private readonly List<byte> _captureAccum = new(RideComProtocol.FrameBytes * 4);
    private readonly byte[] _opusScratch = new byte[1276];
    private readonly short[] _decodeScratch = new short[RideComProtocol.FrameSamples];
    private readonly byte[] _pcmOutScratch = new byte[RideComProtocol.FrameBytes];
    private readonly byte[] _silenceFrame = new byte[RideComProtocol.FrameBytes];

    private long _lastVoxOpenMs;
    private float _lastInputDb = -90f;
    private bool _lastVoxOpen;

    public volatile bool Active;
    public volatile bool Muted;
    public volatile float VoxThresholdDb = -58f;
    public string LocalName { get; set; } = Environment.MachineName;

    public event Action<RideComStatus>? StatusUpdated;

    public RideComEngine()
    {
        _encoder = OpusEncoder.Create(RideComProtocol.SampleRate, RideComProtocol.Channels, OpusApplication.OPUS_APPLICATION_VOIP);
        _encoder.Bitrate = 24000;
        _encoder.Complexity = 2;
        _encoder.SignalType = OpusSignal.OPUS_SIGNAL_VOICE;
        _encoder.UseInbandFEC = false;

        _decoder = OpusDecoder.Create(RideComProtocol.SampleRate, RideComProtocol.Channels);
    }

    public void Start(string? targetIp, int inputDeviceIndex, int outputDeviceIndex)
    {
        if (Active) return;

        _cts = new CancellationTokenSource();
        _jitterBuffer.Reset();
        _lastPeerRxMs = 0;
        _rttMs = 0;
        _peerName = "";
        _captureAccum.Clear();

        lock (_peerLock)
        {
            _peerEndpoint = !string.IsNullOrWhiteSpace(targetIp) && IPAddress.TryParse(targetIp.Trim(), out var ip)
                ? new IPEndPoint(ip, RideComProtocol.DefaultPort)
                : null;
        }

        _udp = new UdpClient();
        _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _udp.Client.Bind(new IPEndPoint(IPAddress.Any, RideComProtocol.DefaultPort));

        var waveFormat = new WaveFormat(RideComProtocol.SampleRate, 16, RideComProtocol.Channels);
        if (outputDeviceIndex >= 0 && outputDeviceIndex < WaveOut.DeviceCount)
        {
            _playBuffer = new BufferedWaveProvider(waveFormat)
            {
                DiscardOnBufferOverflow = true,
                BufferDuration = TimeSpan.FromMilliseconds(500)
            };

            _waveOut = new WaveOutEvent
            {
                DeviceNumber = outputDeviceIndex,
                DesiredLatency = 80,
                NumberOfBuffers = 3
            };
            _waveOut.Init(_playBuffer);
            _waveOut.Play();
        }

        if (inputDeviceIndex >= 0 && inputDeviceIndex < WaveInEvent.DeviceCount)
        {
            _waveIn = new WaveInEvent
            {
                DeviceNumber = inputDeviceIndex,
                WaveFormat = waveFormat,
                BufferMilliseconds = RideComProtocol.FrameMs
            };
            _waveIn.DataAvailable += OnMicData;
            _waveIn.StartRecording();
        }

        Active = true;

        var token = _cts.Token;
        Task.Run(() => ReceiveLoop(token), token);
        Task.Run(() => PlaybackLoop(token), token);
        Task.Run(() => KeepAliveLoop(token), token);
        EmitStatus();
    }

    public void SendTestTone(int durationMs = 1200)
    {
        if (!Active) return;
        Task.Run(async () =>
        {
            int frames = Math.Max(1, durationMs / RideComProtocol.FrameMs);
            var pcm = new short[RideComProtocol.FrameSamples];
            int sampleIdx = 0;
            long nextTick = _clock.ElapsedMilliseconds;
            for (int f = 0; f < frames && Active; f++)
            {
                for (int i = 0; i < RideComProtocol.FrameSamples; i++)
                {
                    pcm[i] = (short)(Math.Sin(2.0 * Math.PI * 440.0 * sampleIdx / RideComProtocol.SampleRate) * 10000.0);
                    sampleIdx++;
                }
                int encodedLen;
                lock (_encodeLock)
                {
                    encodedLen = _encoder.Encode(pcm, 0, RideComProtocol.FrameSamples, _opusScratch, 0, _opusScratch.Length);
                }
                if (encodedLen > 0)
                {
                    var payload = new byte[encodedLen];
                    Buffer.BlockCopy(_opusScratch, 0, payload, 0, encodedLen);
                    SendPacket(PacketType.Audio, RideComProtocol.FlagVoiceActive, _txSeq++, payload);
                }
                nextTick += RideComProtocol.FrameMs;
                int wait = (int)(nextTick - _clock.ElapsedMilliseconds);
                if (wait > 0) await Task.Delay(wait);
            }
        });
    }

    public void Stop()
    {
        if (!Active) return;
        Active = false;

        SendControl(PacketType.Bye, Array.Empty<byte>());

        try { _cts?.Cancel(); } catch { }
        try { _waveIn?.StopRecording(); } catch { }
        try { _waveIn?.Dispose(); } catch { }
        try { _waveOut?.Stop(); } catch { }
        try { _waveOut?.Dispose(); } catch { }
        try { _udp?.Close(); } catch { }
        try { _udp?.Dispose(); } catch { }

        _waveIn = null;
        _waveOut = null;
        _playBuffer = null;
        _udp = null;
        _cts?.Dispose();
        _cts = null;
        _lastInputDb = -90f;
        _lastVoxOpen = false;

        EmitStatus();
    }

    private void OnMicData(object? sender, WaveInEventArgs e)
    {
        if (!Active || e.BytesRecorded <= 0) return;

        for (int i = 0; i < e.BytesRecorded; i++)
        {
            _captureAccum.Add(e.Buffer[i]);
        }

        while (_captureAccum.Count >= RideComProtocol.FrameBytes)
        {
            var pcm = new short[RideComProtocol.FrameSamples];
            double sumSq = 0;
            for (int i = 0; i < RideComProtocol.FrameSamples; i++)
            {
                short s = (short)(_captureAccum[i * 2] | (_captureAccum[i * 2 + 1] << 8));
                pcm[i] = s;
                double norm = s / 32768.0;
                sumSq += norm * norm;
            }
            _captureAccum.RemoveRange(0, RideComProtocol.FrameBytes);

            double rms = Math.Sqrt(sumSq / RideComProtocol.FrameSamples);
            float db = rms > 1e-6 ? (float)(20.0 * Math.Log10(rms)) : -90f;
            _lastInputDb = db;

            long nowMs = _clock.ElapsedMilliseconds;
            bool gateOpen;
            if (db >= VoxThresholdDb)
            {
                _lastVoxOpenMs = nowMs;
                gateOpen = true;
            }
            else
            {
                gateOpen = (nowMs - _lastVoxOpenMs) <= 400;
            }

            bool shouldTx = !Muted && gateOpen;
            _lastVoxOpen = shouldTx;

            if (shouldTx)
            {
                int encodedLen;
                lock (_encodeLock)
                {
                    encodedLen = _encoder.Encode(pcm, 0, RideComProtocol.FrameSamples, _opusScratch, 0, _opusScratch.Length);
                }
                if (encodedLen > 0)
                {
                    var payload = new byte[encodedLen];
                    Buffer.BlockCopy(_opusScratch, 0, payload, 0, encodedLen);
                    SendPacket(PacketType.Audio, RideComProtocol.FlagVoiceActive, _txSeq++, payload);
                }
            }
        }
    }

    private void ReceiveLoop(CancellationToken token)
    {
        var remote = new IPEndPoint(IPAddress.Any, 0);
        while (!token.IsCancellationRequested && _udp != null)
        {
            try
            {
                byte[] data = _udp.Receive(ref remote);
                if (data.Length < RideComProtocol.HeaderSize) continue;
                if (data[0] != RideComProtocol.Magic0 || data[1] != RideComProtocol.Magic1) continue;

                byte verType = data[2];
                if ((verType >> 4) != RideComProtocol.Version) continue;
                var type = (PacketType)(verType & 0x0F);

                ushort seq = BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(4, 2));
                ushort senderId = BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(6, 2));
                if (senderId == _senderId) continue;

                lock (_peerLock)
                {
                    _lastPeerRxMs = _clock.ElapsedMilliseconds;
                    if (_peerEndpoint == null || !_peerEndpoint.Address.Equals(remote.Address))
                    {
                        _peerEndpoint = new IPEndPoint(remote.Address, remote.Port);
                    }
                }

                int payloadLen = data.Length - RideComProtocol.HeaderSize;
                switch (type)
                {
                    case PacketType.Audio:
                        if (payloadLen > 0)
                        {
                            var opus = new byte[payloadLen];
                            Buffer.BlockCopy(data, RideComProtocol.HeaderSize, opus, 0, payloadLen);
                            _jitterBuffer.Push(seq, opus);
                        }
                        break;
                    case PacketType.Hello:
                        _peerName = payloadLen > 0 ? Encoding.UTF8.GetString(data, RideComProtocol.HeaderSize, payloadLen) : remote.Address.ToString();
                        SendControl(PacketType.HelloAck, Encoding.UTF8.GetBytes(LocalName));
                        EmitStatus();
                        break;
                    case PacketType.HelloAck:
                        _peerName = payloadLen > 0 ? Encoding.UTF8.GetString(data, RideComProtocol.HeaderSize, payloadLen) : remote.Address.ToString();
                        EmitStatus();
                        break;
                    case PacketType.Ping:
                        if (payloadLen > 0)
                        {
                            var echo = new byte[payloadLen];
                            Buffer.BlockCopy(data, RideComProtocol.HeaderSize, echo, 0, payloadLen);
                            SendControl(PacketType.Pong, echo);
                        }
                        break;
                    case PacketType.Pong:
                        if (payloadLen >= 8)
                        {
                            long sent = BinaryPrimitives.ReadInt64BigEndian(data.AsSpan(RideComProtocol.HeaderSize, 8));
                            _rttMs = (int)Math.Clamp(_clock.ElapsedMilliseconds - sent, 0, 9999);
                            EmitStatus();
                        }
                        break;
                    case PacketType.Bye:
                        _lastPeerRxMs = 0;
                        EmitStatus();
                        break;
                }
            }
            catch (SocketException)
            {
                if (!Active) break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
        }
    }

    private void PlaybackLoop(CancellationToken token)
    {
        long nextTickMs = _clock.ElapsedMilliseconds;
        int consecutivePlc = 0;

        while (!token.IsCancellationRequested && Active)
        {
            nextTickMs += RideComProtocol.FrameMs;

            var playBuf = _playBuffer;
            if (playBuf != null)
            {
                // Keep WaveOut buffer healthy without letting latency creep above 120ms
                if (playBuf.BufferedBytes > RideComProtocol.FrameBytes * 6)
                {
                    playBuf.ClearBuffer();
                }

                int status = _jitterBuffer.Pop(out var opus);
                if (status == 1 && opus != null)
                {
                    consecutivePlc = 0;
                    int decoded;
                    lock (_decodeLock)
                    {
                        decoded = _decoder.Decode(opus, 0, opus.Length, _decodeScratch, 0, RideComProtocol.FrameSamples, false);
                    }
                    if (decoded > 0)
                    {
                        int bytes = decoded * 2;
                        Buffer.BlockCopy(_decodeScratch, 0, _pcmOutScratch, 0, bytes);
                        playBuf.AddSamples(_pcmOutScratch, 0, bytes);
                    }
                }
                else if (status == 0 && consecutivePlc < 3)
                {
                    consecutivePlc++;
                    int decoded;
                    lock (_decodeLock)
                    {
                        decoded = _decoder.Decode((byte[]?)null, 0, 0, _decodeScratch, 0, RideComProtocol.FrameSamples, false);
                    }
                    if (decoded > 0)
                    {
                        int bytes = decoded * 2;
                        Buffer.BlockCopy(_decodeScratch, 0, _pcmOutScratch, 0, bytes);
                        playBuf.AddSamples(_pcmOutScratch, 0, bytes);
                    }
                }
                else if (playBuf.BufferedBytes < RideComProtocol.FrameBytes * 2)
                {
                    playBuf.AddSamples(_silenceFrame, 0, _silenceFrame.Length);
                }
            }

            long sleepMs = nextTickMs - _clock.ElapsedMilliseconds;
            if (sleepMs > 1)
            {
                Thread.Sleep((int)sleepMs);
            }
            else if (sleepMs < -100)
            {
                nextTickMs = _clock.ElapsedMilliseconds;
            }
        }
    }

    private async Task KeepAliveLoop(CancellationToken token)
    {
        while (!token.IsCancellationRequested && Active)
        {
            bool hasPeer;
            bool alive;
            lock (_peerLock)
            {
                hasPeer = _peerEndpoint != null;
                alive = _lastPeerRxMs > 0 && (_clock.ElapsedMilliseconds - _lastPeerRxMs) < 3500;
            }

            if (hasPeer)
            {
                if (!alive)
                {
                    SendControl(PacketType.Hello, Encoding.UTF8.GetBytes(LocalName));
                }
                else
                {
                    var pingPayload = new byte[8];
                    BinaryPrimitives.WriteInt64BigEndian(pingPayload, _clock.ElapsedMilliseconds);
                    SendControl(PacketType.Ping, pingPayload);
                }
            }

            EmitStatus();
            try { await Task.Delay(250, token); } catch { break; }
        }
    }

    private void SendControl(PacketType type, byte[] payload)
    {
        SendPacket(type, 0, 0, payload);
    }

    private void SendPacket(PacketType type, byte flags, ushort seq, byte[] payload)
    {
        IPEndPoint? target;
        lock (_peerLock) { target = _peerEndpoint; }
        var udp = _udp;
        if (target == null || udp == null) return;

        var packet = new byte[RideComProtocol.HeaderSize + payload.Length];
        packet[0] = RideComProtocol.Magic0;
        packet[1] = RideComProtocol.Magic1;
        packet[2] = (byte)((RideComProtocol.Version << 4) | ((byte)type & 0x0F));
        packet[3] = flags;
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(4, 2), seq);
        BinaryPrimitives.WriteUInt16BigEndian(packet.AsSpan(6, 2), _senderId);
        BinaryPrimitives.WriteUInt32BigEndian(packet.AsSpan(8, 4), (uint)_clock.ElapsedMilliseconds);
        if (payload.Length > 0)
        {
            Buffer.BlockCopy(payload, 0, packet, RideComProtocol.HeaderSize, payload.Length);
        }

        try { udp.Send(packet, packet.Length, target); } catch { }
    }

    private void EmitStatus()
    {
        bool alive;
        string peerIp;
        lock (_peerLock)
        {
            alive = Active && _lastPeerRxMs > 0 && (_clock.ElapsedMilliseconds - _lastPeerRxMs) < 3500;
            peerIp = _peerEndpoint?.Address.ToString() ?? "";
        }
        StatusUpdated?.Invoke(new RideComStatus(
            Active: Active,
            PeerConnected: alive,
            PeerName: _peerName,
            PeerIp: peerIp,
            RttMs: alive ? _rttMs : 0,
            PacketLossPct: _jitterBuffer.PacketLossPercent(),
            InputLevelDb: _lastInputDb,
            VoxOpen: _lastVoxOpen
        ));
    }

    public static List<string> GetLocalIpv4Addresses()
    {
        var result = new List<string>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily == AddressFamily.InterNetwork)
                {
                    result.Add($"{ua.Address} ({ni.Name})");
                }
            }
        }
        return result;
    }

    public void Dispose() => Stop();
}
