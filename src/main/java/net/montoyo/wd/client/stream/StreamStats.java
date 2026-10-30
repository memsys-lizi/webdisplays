package net.montoyo.wd.client.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Per-second counters for the stream HUD. Counters are written from several threads. */
final class StreamStats {
    final AtomicLong captured = new AtomicLong();
    final AtomicLong encoded = new AtomicLong();
    final AtomicLong displayed = new AtomicLong();
    final AtomicLong bytes = new AtomicLong();
    final AtomicLong keyFrames = new AtomicLong();
    final AtomicLong encodeNanos = new AtomicLong();
    final AtomicLong decodeNanos = new AtomicLong();
    final AtomicLong decodedFrames = new AtomicLong();
    final AtomicLong latencyNanos = new AtomicLong();

    volatile String encoderName = "-";
    volatile String resolution = "-";
    volatile String error;

    private long windowStart = System.nanoTime();
    private List<String> lines = List.of("collecting...");

    /** Called from the render thread once per frame; rolls the window every second. */
    List<String> lines() {
        long now = System.nanoTime();
        double secs = (now - windowStart) / 1e9;
        if (secs >= 1.0) {
            windowStart = now;
            long cap = captured.getAndSet(0), enc = encoded.getAndSet(0), disp = displayed.getAndSet(0);
            long b = bytes.getAndSet(0), keys = keyFrames.getAndSet(0);
            long encNs = encodeNanos.getAndSet(0), decNs = decodeNanos.getAndSet(0), dec = decodedFrames.getAndSet(0);
            long latNs = latencyNanos.getAndSet(0);

            List<String> l = new ArrayList<>();
            l.add("encoder " + encoderName + "  " + resolution);
            l.add(String.format("fps  capture %.0f  encode %.0f  display %.0f", cap / secs, enc / secs, disp / secs));
            l.add(String.format("bitrate %.0f kbps  (key frames %d)", b * 8 / 1000.0 / secs, keys));
            l.add(String.format("encode %.1f ms  decode %.1f ms", enc == 0 ? 0 : encNs / 1e6 / enc, dec == 0 ? 0 : decNs / 1e6 / dec));
            l.add(String.format("capture->display %.0f ms", disp == 0 ? 0 : latNs / 1e6 / disp));
            if (error != null) l.add("error: " + error);
            lines = l;
        }
        return lines;
    }
}
