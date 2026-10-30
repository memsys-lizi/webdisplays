import net.montoyo.wd.client.stream.codec.EncodedPacket;
import net.montoyo.wd.client.stream.codec.FFmpegLoader;
import net.montoyo.wd.client.stream.codec.H264Decoder;
import net.montoyo.wd.client.stream.codec.H264Encoder;

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
