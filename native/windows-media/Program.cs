// Copyright (c) 2026 dishanhai. Licensed under the MIT License.
using System.Globalization;
using System.Text;
using Windows.Graphics.Imaging;
using Windows.Media.Control;
using Windows.Storage.Streams;

const int maxSessions = 8;
const int maxArtwork = 256 * 1024;
var artwork = new Dictionary<string, Cover>();
CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
if (Console.ReadLine() != "HELLO 1") return;
if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 17763)) {
    Console.WriteLine("UNSUPPORTED");
    return;
}
Console.WriteLine("HELLO 1");
var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
string? command;
while ((command = Console.ReadLine()) is "POLL" or "POLL META") {
    var current = manager.GetCurrentSession();
    var preferred = current?.SourceAppUserModelId;
    var sessions = manager.GetSessions().ToList();
    // Keep the current session in the bounded result even if the system list is long.
    if (current != null) sessions.Insert(0, current);
    var seen = new HashSet<string>();
    var samples = new List<string>();
    foreach (var session in sessions) {
        if (samples.Count == maxSessions) break;
        try {
            string id = session.SourceAppUserModelId;
            if (string.IsNullOrWhiteSpace(id) || !seen.Add(id)) continue;
            var playback = session.GetPlaybackInfo();
            var timeline = session.GetTimelineProperties();
            var status = playback.PlaybackStatus.ToString().ToUpperInvariant();
            var duration = (long)(timeline.EndTime - timeline.StartTime).TotalMilliseconds;
            var position = (long)(timeline.Position - timeline.StartTime).TotalMilliseconds;
            var rate = playback.PlaybackRate;
            if (timeline.LastUpdatedTime.Year <= 1601 || position < 0 ||
                    (timeline.Position == TimeSpan.Zero && duration <= 0)) position = -1;
            if (position >= 0 && status == "PLAYING") {
                var age = (DateTimeOffset.UtcNow - timeline.LastUpdatedTime).TotalMilliseconds;
                // SMTC can report the same reference position until seek/pause/change.
                position += (long)(Math.Clamp(age, 0, TimeSpan.FromDays(1).TotalMilliseconds) *
                                   (rate is >= 0 and <= 16 ? rate.Value : 1));
                if (duration > 0) position = Math.Min(position, duration);
            }
            GlobalSystemMediaTransportControlsSessionMediaProperties? media = null;
            try {
                using var timeout = new CancellationTokenSource(TimeSpan.FromMilliseconds(650));
                media = await session.TryGetMediaPropertiesAsync().AsTask(timeout.Token);
            }
            catch (Exception) { /* Playback fields remain usable. */ }
            var title = media?.Title;
            var artist = media?.Artist;
            var album = media?.AlbumTitle;
            // Album, duration and playback status can arrive late. Keep the fallback key independent of them.
            var key = (string.IsNullOrWhiteSpace(title) ? "" : B64(title) + ":" + B64(artist));
            string cover = "-";
            if (command == "POLL" && media != null && key.Length != 0) {
                artwork.TryGetValue(id, out var cached);
                long now = Environment.TickCount64;
                if (cached != null && cached.Key == key && now < cached.Expires) cover = "=";
                else {
                    byte[]? png = null;
                    try {
                        using var timeout = new CancellationTokenSource(TimeSpan.FromMilliseconds(500));
                        png = await ReadCover(media.Thumbnail, timeout.Token);
                    }
                    catch (Exception) { /* Unsupported or unreadable cover. */ }
                    if (png != null) {
                        cover = cached?.Key == key && cached.Bytes != null && png.AsSpan().SequenceEqual(cached.Bytes)
                            ? "=" : Convert.ToBase64String(png);
                    }
                    artwork[id] = new Cover(key, now + 15000, png);
                }
            } else if (key != artwork.GetValueOrDefault(id)?.Key) artwork.Remove(id);
            samples.Add(string.Join('\t', "S", B64(id), key, B64(title), B64(artist), B64(album),
                status, position.ToString(), duration.ToString(), rate?.ToString() ?? "", cover));
        } catch (Exception) { /* A session can disappear during enumeration. */ }
    }
    foreach (string id in artwork.Keys.Where(id => !seen.Contains(id)).ToArray()) artwork.Remove(id);
    Console.WriteLine("BEGIN\t" + B64(preferred));
    foreach (string sample in samples) Console.WriteLine(sample);
    Console.WriteLine("END");
}
return;

static string B64(string? text) {
    if (string.IsNullOrWhiteSpace(text)) return "";
    if (text.Length > 512) text = text[..512];
    return Convert.ToBase64String(Encoding.UTF8.GetBytes(text));
}

async Task<byte[]?> ReadCover(IRandomAccessStreamReference? thumbnail, CancellationToken token) {
    if (thumbnail == null) return null;
    using var input = await thumbnail.OpenReadAsync().AsTask(token);
    if (input.Size == 0 || input.Size > 16 * 1024 * 1024) return null;
    var decoder = await BitmapDecoder.CreateAsync(input).AsTask(token);
    if (decoder.PixelWidth is 0 or > 4096 || decoder.PixelHeight is 0 or > 4096) return null;
    double scale = Math.Min(1, 240.0 / Math.Max(decoder.PixelWidth, decoder.PixelHeight));
    var transform = new BitmapTransform {
        ScaledWidth = Math.Max(1, (uint)(decoder.PixelWidth * scale)),
        ScaledHeight = Math.Max(1, (uint)(decoder.PixelHeight * scale))
    };
    var pixels = await decoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Straight,
        transform, ExifOrientationMode.IgnoreExifOrientation, ColorManagementMode.DoNotColorManage).AsTask(token);
    using var output = new InMemoryRandomAccessStream();
    var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, output).AsTask(token);
    encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Straight, transform.ScaledWidth,
        transform.ScaledHeight, 96, 96, pixels.DetachPixelData());
    await encoder.FlushAsync().AsTask(token);
    if (output.Size == 0 || output.Size > maxArtwork) return null;
    using var reader = new DataReader(output.GetInputStreamAt(0));
    var length = (uint)output.Size;
    if (await reader.LoadAsync(length).AsTask(token) != length) return null;
    var bytes = new byte[length];
    reader.ReadBytes(bytes);
    return bytes;
}

record Cover(string Key, long Expires, byte[]? Bytes);
