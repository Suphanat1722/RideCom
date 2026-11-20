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

public sealed class RideComEngine : IDisposable
{
    private readonly ushort _senderId = (ushort)Random.Shared.Next(1, 65535);
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly object _codecLock = new();
    private readonly object _peerLock = new();

    private readonly OpusEncoder _encoder;
    private readonly OpusDecoder _decoder;

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

    private int _lastRxSeq = -1;
    private long _rxExpected;
    private long _rxLost;

    private readonly List<byte> _captureAccum = new(RideComProtocol.FrameBytes * 4);
    private readonly byte[] _opusScratch = new byte[1276];
    private readonly short[] _decodeScratch = new short[RideComProtocol.FrameSamples];
    private readonly byte[] _pcmOutScratch = new byte[RideComProtocol.FrameBytes];

    private long _lastVoxOpenMs;
    private float _lastInputDb = -90f;
    private bool _lastVoxOpen;

    public volatile bool Active;
    public volatile bool Muted;
    public volatile float VoxThresholdDb = -45f;
    public string LocalName { get; set; } = Environment.MachineName;

    public event Action<RideComStatus>? StatusUpdated;

    public RideComEngine()
    {
        _encoder = OpusEncoder.Create(RideComProtocol.SampleRate, RideComProtocol.Channels, OpusApplication.OPUS_APPLICATION_VOIP);
        _encoder.Bitrate = 24000;
        _encoder.Complexity = 5;
        _encoder.SignalType = OpusSignal.OPUS_SIGNAL_VOICE;
        _encoder.UseInbandFEC = true;
        _encoder.PacketLossPercent = 15;

        _decoder = OpusDecoder.Create(RideComProtocol.SampleRate, RideComProtocol.Channels);
    }

    public void Start(string? targetIp, int inputDeviceIndex, int outputDeviceIndex)
    {
        if (Active) return;

        _cts = new CancellationTokenSource();
        _lastRxSeq = -1;
        _rxExpected = 0;
        _rxLost = 0;
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
        _playBuffer = new BufferedWaveProvider(waveFormat)
        {
            DiscardOnBufferOverflow = true,
            BufferDuration = TimeSpan.FromMilliseconds(200)
        };

        _waveOut = new WaveOutEvent
        {
            DeviceNumber = outputDeviceIndex,
            DesiredLatency = 60
        };
        _waveOut.Init(_playBuffer);
        _waveOut.Play();

        _waveIn = new WaveInEvent
        {
            DeviceNumber = inputDeviceIndex,
            WaveFormat = waveFormat,
            BufferMilliseconds = RideComProtocol.FrameMs
        };
        _waveIn.DataAvailable += OnMicData;
        _waveIn.StartRecording();

        Active = true;

        var token = _cts.Token;
        Task.Run(() => ReceiveLoop(token), token);
        Task.Run(() => KeepAliveLoop(token), token);
        EmitStatus();
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
                gateOpen = (nowMs - _lastVoxOpenMs) <= 350;
            }

            bool shouldTx = !Muted && gateOpen;
            _lastVoxOpen = shouldTx;

            if (shouldTx)
            {
                int encodedLen;
                lock (_codecLock)
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
                        HandleAudioPacket(seq, data, RideComProtocol.HeaderSize, payloadLen);
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

    private void HandleAudioPacket(ushort seq, byte[] buffer, int offset, int length)
    {
        if (_playBuffer == null || length <= 0) return;

        if (_lastRxSeq >= 0)
        {
            int diff = ((seq - _lastRxSeq) + 65536) % 65536;
            if (diff > 1 && diff < 20)
            {
                _rxLost += (diff - 1);
                _rxExpected += diff;
            }
            else
            {
                _rxExpected += 1;
            }
        }
        else
        {
            _rxExpected = 1;
        }
        _lastRxSeq = seq;

        int decodedSamples;
        lock (_codecLock)
        {
            decodedSamples = _decoder.Decode(buffer, offset, length, _decodeScratch, 0, RideComProtocol.FrameSamples, false);
        }

        if (decodedSamples > 0)
        {
            int bytes = decodedSamples * 2;
            Buffer.BlockCopy(_decodeScratch, 0, _pcmOutScratch, 0, bytes);
            _playBuffer.AddSamples(_pcmOutScratch, 0, bytes);
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
        float loss = _rxExpected > 0 ? (_rxLost * 100f / _rxExpected) : 0f;
        StatusUpdated?.Invoke(new RideComStatus(
            Active: Active,
            PeerConnected: alive,
            PeerName: _peerName,
            PeerIp: peerIp,
            RttMs: alive ? _rttMs : 0,
            PacketLossPct: loss,
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
