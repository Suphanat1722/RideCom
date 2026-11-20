using NAudio.Wave;

namespace RideComPC;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

public sealed class MainForm : Form
{
    private readonly RideComEngine _engine = new();

    private readonly Label _localIpLabel = new();
    private readonly ComboBox _micBox = new();
    private readonly ComboBox _spkBox = new();
    private readonly TextBox _peerIpBox = new();
    private readonly Button _connectBtn = new();
    private readonly Button _muteBtn = new();
    private readonly Button _beepBtn = new();
    private readonly Label _statusLabel = new();
    private readonly Label _statsLabel = new();
    private readonly ProgressBar _levelBar = new();
    private readonly TrackBar _voxSlider = new();
    private readonly Label _voxLabel = new();

    public MainForm()
    {
        Text = "RideCom PC — Offline Intercom Tester";
        ClientSize = new Size(440, 535);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(16, 18, 24);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);

        int y = 16;

        var title = new Label
        {
            Text = "RideCom PC (UDP :50000 + Opus 16kHz)",
            Font = new Font("Segoe UI", 13f, FontStyle.Bold),
            ForeColor = Color.FromArgb(0, 230, 118),
            Location = new Point(20, y),
            AutoSize = true
        };
        Controls.Add(title);
        y += 34;

        var ips = RideComEngine.GetLocalIpv4Addresses();
        _localIpLabel.Text = "PC IP: " + (ips.Count > 0 ? string.Join(" | ", ips.Take(2)) : "No network");
        _localIpLabel.ForeColor = Color.Silver;
        _localIpLabel.Location = new Point(20, y);
        _localIpLabel.Size = new Size(400, 24);
        Controls.Add(_localIpLabel);
        y += 32;

        Controls.Add(MakeLabel("Microphone (Input):", 20, y));
        y += 22;
        _micBox.Location = new Point(20, y);
        _micBox.Size = new Size(400, 28);
        _micBox.DropDownStyle = ComboBoxStyle.DropDownList;
        PopulateDevices();
        Controls.Add(_micBox);
        y += 36;

        Controls.Add(MakeLabel("Speaker / Headset (Output):", 20, y));
        y += 22;
        _spkBox.Location = new Point(20, y);
        _spkBox.Size = new Size(400, 28);
        _spkBox.DropDownStyle = ComboBoxStyle.DropDownList;
        Controls.Add(_spkBox);
        y += 38;

        Controls.Add(MakeLabel("Android IP (leave blank to just listen on :50000):", 20, y));
        y += 22;
        _peerIpBox.Location = new Point(20, y);
        _peerIpBox.Size = new Size(400, 28);
        _peerIpBox.PlaceholderText = "e.g. 192.168.43.1 or 192.168.1.50";
        Controls.Add(_peerIpBox);
        y += 40;

        _connectBtn.Text = "Start Intercom";
        _connectBtn.Location = new Point(20, y);
        _connectBtn.Size = new Size(240, 44);
        _connectBtn.BackColor = Color.FromArgb(0, 200, 100);
        _connectBtn.ForeColor = Color.Black;
        _connectBtn.FlatStyle = FlatStyle.Flat;
        _connectBtn.Font = new Font("Segoe UI", 11f, FontStyle.Bold);
        _connectBtn.Click += OnConnectClick;
        Controls.Add(_connectBtn);

        _muteBtn.Text = "Mute Mic";
        _muteBtn.Location = new Point(272, y);
        _muteBtn.Size = new Size(148, 44);
        _muteBtn.BackColor = Color.FromArgb(45, 52, 66);
        _muteBtn.ForeColor = Color.White;
        _muteBtn.FlatStyle = FlatStyle.Flat;
        _muteBtn.Enabled = false;
        _muteBtn.Click += OnMuteClick;
        Controls.Add(_muteBtn);
        y += 52;

        _beepBtn.Text = "Send Test Beep (440 Hz) to Android";
        _beepBtn.Location = new Point(20, y);
        _beepBtn.Size = new Size(400, 34);
        _beepBtn.BackColor = Color.FromArgb(30, 64, 110);
        _beepBtn.ForeColor = Color.White;
        _beepBtn.FlatStyle = FlatStyle.Flat;
        _beepBtn.Enabled = false;
        _beepBtn.Click += (_, _) => _engine.SendTestTone();
        Controls.Add(_beepBtn);
        y += 44;

        _statusLabel.Text = "Status: Idle";
        _statusLabel.Location = new Point(20, y);
        _statusLabel.Size = new Size(400, 24);
        _statusLabel.Font = new Font("Segoe UI", 10.5f, FontStyle.Bold);
        _statusLabel.ForeColor = Color.Gold;
        Controls.Add(_statusLabel);
        y += 26;

        _statsLabel.Text = "RTT: 0 ms  |  Loss: 0.0%";
        _statsLabel.Location = new Point(20, y);
        _statsLabel.Size = new Size(400, 22);
        _statsLabel.ForeColor = Color.LightGray;
        Controls.Add(_statsLabel);
        y += 30;

        _voxLabel.Text = "Wind Gate (VOX): -58 dB";
        _voxLabel.Location = new Point(20, y);
        _voxLabel.Size = new Size(400, 22);
        Controls.Add(_voxLabel);
        y += 24;

        _levelBar.Location = new Point(20, y);
        _levelBar.Size = new Size(400, 14);
        _levelBar.Minimum = 0;
        _levelBar.Maximum = 100;
        Controls.Add(_levelBar);
        y += 22;

        _voxSlider.Location = new Point(16, y);
        _voxSlider.Size = new Size(408, 42);
        _voxSlider.Minimum = -70;
        _voxSlider.Maximum = -10;
        _voxSlider.Value = -58;
        _voxSlider.TickFrequency = 10;
        _voxSlider.ValueChanged += (_, _) =>
        {
            _engine.VoxThresholdDb = _voxSlider.Value;
            _voxLabel.Text = $"Wind Gate (VOX): {_voxSlider.Value} dB";
        };
        Controls.Add(_voxSlider);
        y += 44;

        Controls.Add(MakeLabel("PC Jitter Buffer (higher = smoother):", 20, y));
        y += 22;
        _bufBox.Location = new Point(20, y);
        _bufBox.Size = new Size(190, 28);
        _bufBox.DropDownStyle = ComboBoxStyle.DropDownList;
        _bufBox.Items.AddRange(new object[] { "40 ms (Fast)", "60 ms (Balanced)", "100 ms (Ultra Smooth)" });
        _bufBox.SelectedIndex = 1;
        _bufBox.SelectedIndexChanged += (_, _) => _engine.SetBufferFrames(SelectedBufferFrames());
        Controls.Add(_bufBox);

        _volLabel.Text = "Speaker Vol: 70%";
        _volLabel.Location = new Point(225, y - 22);
        _volLabel.Size = new Size(195, 20);
        _volLabel.ForeColor = Color.LightGray;
        Controls.Add(_volLabel);

        _volSlider.Location = new Point(220, y - 4);
        _volSlider.Size = new Size(200, 38);
        _volSlider.Minimum = 0;
        _volSlider.Maximum = 100;
        _volSlider.Value = 70;
        _volSlider.TickFrequency = 25;
        _volSlider.ValueChanged += (_, _) =>
        {
            _volLabel.Text = $"Speaker Vol: {_volSlider.Value}%";
            _engine.SetOutputVolume(_volSlider.Value / 100f);
        };
        Controls.Add(_volSlider);

        _engine.StatusUpdated += OnStatusUpdated;
        FormClosing += (_, _) => _engine.Dispose();
    }

    private int SelectedBufferFrames() => _bufBox.SelectedIndex switch
    {
        0 => 2,
        2 => 5,
        _ => 3
    };

    private static Label MakeLabel(string text, int x, int y) => new()
    {
        Text = text,
        Location = new Point(x, y),
        AutoSize = true,
        ForeColor = Color.LightGray
    };

    private void PopulateDevices()
    {
        _micBox.Items.Clear();
        if (WaveInEvent.DeviceCount == 0)
        {
            _micBox.Items.Add("(No Microphone — Listen Only)");
        }
        else
        {
            for (int i = 0; i < WaveInEvent.DeviceCount; i++)
            {
                _micBox.Items.Add(WaveInEvent.GetCapabilities(i).ProductName);
            }
        }
        _micBox.SelectedIndex = 0;

        _spkBox.Items.Clear();
        if (WaveOut.DeviceCount == 0)
        {
            _spkBox.Items.Add("(No Speaker Detected)");
        }
        else
        {
            for (int i = 0; i < WaveOut.DeviceCount; i++)
            {
                _spkBox.Items.Add(WaveOut.GetCapabilities(i).ProductName);
            }
        }
        _spkBox.SelectedIndex = 0;
    }

    private void OnConnectClick(object? sender, EventArgs e)
    {
        if (_engine.Active)
        {
            _engine.Stop();
            _connectBtn.Text = "Start Intercom";
            _connectBtn.BackColor = Color.FromArgb(0, 200, 100);
            _muteBtn.Enabled = false;
            _beepBtn.Enabled = false;
            return;
        }

        try
        {
            int micIdx = WaveInEvent.DeviceCount > 0 ? Math.Max(0, _micBox.SelectedIndex) : -1;
            int spkIdx = WaveOut.DeviceCount > 0 ? Math.Max(0, _spkBox.SelectedIndex) : -1;
            _engine.Start(_peerIpBox.Text, micIdx, spkIdx, SelectedBufferFrames(), _volSlider.Value / 100f);
            _connectBtn.Text = "Stop Intercom";
            _connectBtn.BackColor = Color.IndianRed;
            _muteBtn.Enabled = micIdx >= 0;
            _beepBtn.Enabled = true;
        }
        catch (Exception ex)
        {
            MessageBox.Show($"Failed to start:\n{ex.Message}", "RideCom PC", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void OnMuteClick(object? sender, EventArgs e)
    {
        _engine.Muted = !_engine.Muted;
        _muteBtn.Text = _engine.Muted ? "Unmute Mic" : "Mute Mic";
        _muteBtn.BackColor = _engine.Muted ? Color.DarkOrange : Color.FromArgb(45, 52, 66);
    }

    private void OnStatusUpdated(RideComStatus s)
    {
        if (IsDisposed) return;
        BeginInvoke(() =>
        {
            if (!s.Active)
            {
                _statusLabel.Text = "Status: Idle";
                _statusLabel.ForeColor = Color.Silver;
                _levelBar.Value = 0;
                return;
            }

            if (s.PeerConnected)
            {
                _statusLabel.Text = $"Connected: {(string.IsNullOrEmpty(s.PeerName) ? s.PeerIp : s.PeerName)} ({s.PeerIp})";
                _statusLabel.ForeColor = Color.FromArgb(0, 230, 118);
            }
            else
            {
                _statusLabel.Text = string.IsNullOrEmpty(s.PeerIp)
                    ? "Listening on UDP :50000..."
                    : $"Calling {s.PeerIp}:50000...";
                _statusLabel.ForeColor = Color.Gold;
            }

            _statsLabel.Text = $"RTT: {s.RttMs} ms  |  Loss: {s.PacketLossPct:F1}%  |  Buf: {s.BufferDepthMs} ms";
            int norm = (int)Math.Clamp((s.InputLevelDb + 70f) / 70f * 100f, 0f, 100f);
            _levelBar.Value = norm;
        });
    }
}
