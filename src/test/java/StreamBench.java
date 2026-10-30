import net.montoyo.wd.client.stream.codec.EncodedPacket;
import net.montoyo.wd.client.stream.codec.FFmpegLoader;
import net.montoyo.wd.client.stream.codec.H264Decoder;
import net.montoyo.wd.client.stream.codec.H264Encoder;
import net.montoyo.wd.client.stream.codec.OpusDecoder;
import net.montoyo.wd.client.stream.codec.OpusEncoder;
import net.montoyo.wd.client.stream.audio.LinearResampler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

/**
 * Encodes synthetic 1280x720 "web page with a video" frames with every available encoder, decodes them again
 * and reports speed, bitrate, encoder delay and whether colours survive the round trip.
 * Run with {@code ./gradlew streamBench}.
 */
public class StreamBench {
    static final int W = 1280, H = 720, FPS = 30, KBPS = 4000, FRAMES = 150;

    public static void main(String[] args) throws Exception {
        if (!FFmpegLoader.ensureLoaded(Path.of("build", "natives"))) {
            System.out.println("FFmpeg failed to load: " + FFmpegLoader.getFailure());
            System.exit(1);
        }

        List<H264Encoder.Candidate> available = H264Encoder.available();
        System.out.println();
        System.out.println("Available encoders: " + available.stream().map(H264Encoder.Candidate::label).toList());
        System.out.printf("%n%-20s %8s %8s %9s %10s %8s %s%n", "encoder", "enc ms", "dec ms", "kbps", "delay(fr)", "frames", "colour");

        for (H264Encoder.Candidate c : available)
            run(c);

        audio();
    }

    /** 1 kHz sine at 44.1 kHz in CEF-sized blocks -> resample to 48 kHz -> Opus -> decode; checks pitch and level. */
    static void audio() throws Exception {
        final int inRate = 44100, seconds = 3, block = 1024;
        final float amp = 0.5f;
        LinearResampler resampler = new LinearResampler(inRate, OpusEncoder.SAMPLE_RATE);
        float[] resampled = new float[OpusEncoder.SAMPLE_RATE * seconds + 4096];
        int nRes = 0;
        float[] in = new float[block];
        for (int pos = 0; pos + block <= inRate * seconds; pos += block) {
            for (int i = 0; i < block; i++) in[i] = amp * (float) Math.sin(2 * Math.PI * 1000 * (pos + i) / inRate);
            int n = resampler.process(in, block);
            System.arraycopy(resampler.output(), 0, resampled, nRes, n);
            nRes += n;
        }

        float[] decoded = new float[nRes + 4096];
        int[] nDec = {0};
        long encNs = 0, decNs = 0, bytes = 0;
        int frames = 0;
        try (OpusEncoder enc = new OpusEncoder(64000); OpusDecoder dec = new OpusDecoder()) {
            int fs = enc.getFrameSize();
            for (int off = 0; off + fs <= nRes; off += fs) {
                long t0 = System.nanoTime();
                var packets = enc.encode(resampled, off);
                encNs += System.nanoTime() - t0;
                frames++;
                for (EncodedPacket p : packets) {
                    bytes += p.data().length;
                    long t1 = System.nanoTime();
                    dec.decode(p, (samples, count) -> {
                        System.arraycopy(samples, 0, decoded, nDec[0], count);
                        nDec[0] += count;
                    });
                    decNs += System.nanoTime() - t1;
                }
            }
            System.out.printf("%nOpus frame size %d samples (%.1f ms)%n", fs, fs * 1000.0 / OpusEncoder.SAMPLE_RATE);
        }

        System.out.printf("resampler: %d samples in -> %d out (expected ~%d), pitch %.1f Hz%n",
                inRate * seconds / block * block, nRes, (long) (inRate * seconds / block * block) * 48000 / inRate, pitch(resampled, 4800, nRes));
        // skip the first 100 ms (codec start-up) when measuring
        System.out.printf("opus: enc %.3f ms  dec %.3f ms per frame, %.0f kbps, decoded %d samples, pitch %.1f Hz, rms %.3f (input %.3f)%n",
                encNs / 1e6 / frames, decNs / 1e6 / frames, bytes * 8 / 1000.0 / seconds, nDec[0],
                pitch(decoded, 4800, nDec[0]), rms(decoded, 4800, nDec[0]), rms(resampled, 4800, nRes));
    }

    static double pitch(float[] s, int from, int to) {
        int crossings = 0;
        for (int i = from + 1; i < to; i++) if (s[i - 1] < 0 && s[i] >= 0) crossings++;
        return crossings / ((to - from) / (double) OpusEncoder.SAMPLE_RATE);
    }

    static double rms(float[] s, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += s[i] * s[i];
        return Math.sqrt(sum / (to - from));
    }

    static void run(H264Encoder.Candidate c) throws Exception {
        ByteBuffer src = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer[] last = new ByteBuffer[1];
        int[] decoded = {0};

        long encNs = 0, decNs = 0, bytes = 0;
        int firstPacketFrame = -1;

        try (H264Encoder enc = H264Encoder.open(c, W, H, FPS, KBPS); H264Decoder dec = new H264Decoder()) {
            H264Decoder.FrameSink sink = new H264Decoder.FrameSink() {
                ByteBuffer buf;

                @Override
                public ByteBuffer acquire(int width, int height) {
                    if (buf == null || buf.capacity() < width * height * 4) buf = ByteBuffer.allocateDirect(width * height * 4);
                    return buf;
                }

                @Override
                public void publish(ByteBuffer bgra, int width, int height, long ptsMs) {
                    last[0] = bgra;
                    decoded[0]++;
                }
            };

            for (int i = 0; i < FRAMES; i++) {
                paint(src, i);
                long t0 = System.nanoTime();
                List<EncodedPacket> packets = enc.encode(src, W, H, i * 1000L / FPS);
                encNs += System.nanoTime() - t0;

                for (EncodedPacket p : packets) {
                    if (firstPacketFrame < 0) firstPacketFrame = i;
                    bytes += p.data().length;
                    long t1 = System.nanoTime();
                    dec.decode(p, sink);
                    decNs += System.nanoTime() - t1;
                }
            }
        } catch (Exception e) {
            System.out.printf("%-20s FAILED: %s%n", c.label(), e.getMessage());
            return;
        }

        double seconds = FRAMES / (double) FPS;
        String colour = last[0] == null ? "no output" : checkColour(last[0]);
        System.out.printf("%-20s %8.2f %8.2f %9.0f %10d %8d %s%n", c.label(),
                encNs / 1e6 / FRAMES, decoded[0] == 0 ? 0 : decNs / 1e6 / decoded[0],
                bytes * 8 / 1000.0 / seconds, firstPacketFrame, decoded[0], colour);
    }

    /** Red block top-left, blue block top-right, moving white square, noisy "video" area at the bottom. */
    static void paint(ByteBuffer buf, int n) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int argb;
                if (y < 100 && x < 200) argb = 0xFFFF0000;            // red
                else if (y < 100 && x >= W - 200) argb = 0xFF0000FF;  // blue
                else if (y >= 360) {                                   // "video": moving gradient + noise
                    int v = (x + y * 3 + n * 8) & 0xFF;
                    int noise = ((x * 73856093) ^ (y * 19349663) ^ (n * 83492791)) & 0x1F;
                    argb = 0xFF000000 | (v << 16) | (((v + noise) & 0xFF) << 8) | (255 - v);
                } else if (((x / 8) + (y / 16)) % 7 == 0) argb = 0xFF202020; // "text"
                else argb = 0xFFF0F0F0;
                int bx = (n * 10) % (W - 80);
                if (x >= bx && x < bx + 80 && y >= 150 && y < 230) argb = 0xFFFFFFFF;
                buf.putInt((y * W + x) * 4, argb); // little endian int ARGB == bytes B,G,R,A
            }
        }
    }

    static String checkColour(ByteBuffer bgra) {
        int red = bgra.order(ByteOrder.LITTLE_ENDIAN).getInt((50 * W + 100) * 4);
        int blue = bgra.getInt((50 * W + W - 100) * 4);
        boolean ok = ((red >> 16) & 0xFF) > 200 && (red & 0xFF) < 60 && (blue & 0xFF) > 200 && ((blue >> 16) & 0xFF) < 60;
        return (ok ? "OK" : "WRONG") + String.format(" (red=%08X blue=%08X)", red, blue);
    }
}
