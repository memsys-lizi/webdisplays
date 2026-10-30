package net.montoyo.wd.client.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Per-second counters for the stream HUD. Counters are written from several threads. */
final class StreamStats {
    enum Role {LOOPBACK, HOST, VIEWER}

    final Role role;
    final AtomicLong captured = new AtomicLong();
    final AtomicLong encoded = new AtomicLong();    // frames encoded (host/loopback) or received (viewer)
    final AtomicLong displayed = new AtomicLong();
    final AtomicLong bytes = new AtomicLong();      // video bytes sent / received
    final AtomicLong keyFrames = new AtomicLong();
    final AtomicLong encodeNanos = new AtomicLong();
    final AtomicLong decodeNanos = new AtomicLong();
    final AtomicLong decodedFrames = new AtomicLong();
    final AtomicLong latencyNanos = new AtomicLong();
    final AtomicLong audioBytes = new AtomicLong();
    final AtomicLong dropped = new AtomicLong();    // frames the host skipped because its connection was busy

    volatile String title = "WD stream";
    volatile String encoderName = "-";
    volatile String resolution = "-";
    volatile String error;
    /** Supplies the audio buffer status from the current player. */
    volatile Supplier<String> audioStatus = () -> "-";

    private long windowStart = System.nanoTime();
    private List<String> lines = List.of("collecting...");

    StreamStats(Role role) {
        this.role = role;
    }

    /** Called from the render thread once per frame; rolls the window every second. */
    List<String> lines() {
        long now = System.nanoTime();
        double secs = (now - windowStart) / 1e9;
        if (secs >= 1.0) {
            windowStart = now;
            long cap = captured.getAndSet(0), enc = encoded.getAndSet(0), disp = displayed.getAndSet(0);
            long b = bytes.getAndSet(0), keys = keyFrames.getAndSet(0), drop = dropped.getAndSet(0);
            long encNs = encodeNanos.getAndSet(0), decNs = decodeNanos.getAndSet(0), dec = decodedFrames.getAndSet(0);
            long latNs = latencyNanos.getAndSet(0), ab = audioBytes.getAndSet(0);
            double videoKbps = b * 8 / 1000.0 / secs, audioKbps = ab * 8 / 1000.0 / secs;

            List<String> l = new ArrayList<>();
            l.add(encoderName + "  " + resolution);
            switch (role) {
                case LOOPBACK -> {
                    l.add(String.format("fps  capture %.0f  encode %.0f  display %.0f", cap / secs, enc / secs, disp / secs));
                    l.add(String.format("video %.0f kbps (key frames %d)  audio %.0f kbps", videoKbps, keys, audioKbps));
                    l.add(String.format("encode %.1f ms  decode %.1f ms  capture->display %.0f ms",
                            enc == 0 ? 0 : encNs / 1e6 / enc, dec == 0 ? 0 : decNs / 1e6 / dec, disp == 0 ? 0 : latNs / 1e6 / disp));
                }
                case HOST -> {
                    l.add(String.format("fps  capture %.0f  sent %.0f  skipped %d (connection busy)", cap / secs, enc / secs, drop));
                    l.add(String.format("upload %.0f kbps = %.2f MB/s (video %.0f + audio %.0f)",
                            videoKbps + audioKbps, (b + ab) / 1e6 / secs, videoKbps, audioKbps));
                    l.add(String.format("encode %.1f ms  key frames %d", enc == 0 ? 0 : encNs / 1e6 / enc, keys));
                }
                case VIEWER -> {
                    l.add(String.format("fps  received %.0f  shown %.0f  key frames %d", enc / secs, disp / secs, keys));
                    l.add(String.format("download %.0f kbps = %.2f MB/s (video %.0f + audio %.0f)",
                            videoKbps + audioKbps, (b + ab) / 1e6 / secs, videoKbps, audioKbps));
                    l.add(String.format("decode %.1f ms", dec == 0 ? 0 : decNs / 1e6 / dec));
                }
            }
            l.add("audio " + audioStatus.get());
            if (error != null) l.add("error: " + error);
            lines = l;
        }
        return lines;
    }
}
