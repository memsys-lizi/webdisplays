package net.montoyo.wd.client.stream.codec;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Low-latency H.264 encoder fed with BGRA frames (as painted by CEF). Not thread safe: use from one thread.
 */
public final class H264Encoder implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Encoder");

    /**
     * An FFmpeg encoder configuration to try.
     *
     * @param label   unique name shown to the user
     * @param codec   FFmpeg encoder name
     * @param pixFmt  input pixel format the encoder accepts
     * @param options encoder private options as key/value pairs
     */
    public record Candidate(String label, String codec, int pixFmt, String... options) {
    }

    /**
     * Preferred order: hardware encoders first, software last. Media Foundation hardware comes first because it
     * covers NVIDIA, AMD and Intel GPUs and showed no encoder delay; its software MFT is left out because it
     * buffered ~16 frames.
     */
    public static final List<Candidate> CANDIDATES = List.of(
            new Candidate("mediafoundation-hw", "h264_mf", AV_PIX_FMT_NV12,
                    "hw_encoding", "1", "rate_control", "cbr", "scenario", "display_remoting"),
            new Candidate("nvenc", "h264_nvenc", AV_PIX_FMT_YUV420P,
                    "preset", "p1", "tune", "ull", "zerolatency", "1", "rc", "cbr", "delay", "0", "forced-idr", "1"),
            new Candidate("qsv", "h264_qsv", AV_PIX_FMT_NV12,
                    "preset", "veryfast", "async_depth", "1", "look_ahead", "0"),
            new Candidate("openh264", "libopenh264", AV_PIX_FMT_YUV420P,
                    "rc_mode", "bitrate", "allow_skip_frames", "0")
    );

    private static final Map<String, Boolean> PROBED = new ConcurrentHashMap<>();

    private final Candidate candidate;
    private final int width, height, fps;
    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket pkt;

    private SwsContext sws;
    private int swsSrcW = -1, swsSrcH = -1;
    private final PointerPointer<BytePointer> srcPlanes = new PointerPointer<>(1);
    private final IntPointer srcStride = new IntPointer(1);

    private long lastPts = -1;
    private boolean forceKeyFrame;

    private H264Encoder(Candidate candidate, int width, int height, int fps, int bitrateKbps) throws IOException {
        this.candidate = candidate;
        this.width = width;
        this.height = height;
        this.fps = fps;

        AVCodec codec = avcodec_find_encoder_by_name(candidate.codec());
        if (codec == null || codec.isNull())
            throw new IOException("Encoder " + candidate.codec() + " is not built into this FFmpeg");

        ctx = avcodec_alloc_context3(codec);
        ctx.width(width);
        ctx.height(height);
        ctx.pix_fmt(candidate.pixFmt());
        ctx.time_base(av_make_q(1, 1000));
        ctx.framerate(av_make_q(fps, 1));
        long bps = bitrateKbps * 1000L;
        ctx.bit_rate(bps);
        ctx.rc_max_rate(bps);
        ctx.rc_buffer_size((int) Math.min(Integer.MAX_VALUE, bps / 2));
        ctx.gop_size(fps * 2);
        ctx.max_b_frames(0);
        ctx.flags(ctx.flags() | AV_CODEC_FLAG_LOW_DELAY);

        AVDictionary opts = new AVDictionary(null);
        for (int i = 0; i + 1 < candidate.options().length; i += 2)
            av_dict_set(opts, candidate.options()[i], candidate.options()[i + 1], 0);
        int ret = avcodec_open2(ctx, codec, opts);
        av_dict_free(opts);
        if (ret < 0) {
            close();
            throw new IOException("Could not open " + candidate.label() + ": " + FFmpegUtil.error(ret));
        }

        frame = av_frame_alloc();
        frame.format(candidate.pixFmt());
        frame.width(width);
        frame.height(height);
        ret = av_frame_get_buffer(frame, 0);
        if (ret < 0) {
            close();
            throw new IOException("Could not allocate frame: " + FFmpegUtil.error(ret));
        }
        pkt = av_packet_alloc();
    }

    /** Opens one specific encoder configuration. Width and height are rounded down to even values. */
    public static H264Encoder open(Candidate candidate, int width, int height, int fps, int bitrateKbps) throws IOException {
        return new H264Encoder(candidate, width & ~1, height & ~1, fps, bitrateKbps);
    }

    /** Opens the first encoder from {@link #CANDIDATES} that works on this machine. */
    public static H264Encoder openBest(int width, int height, int fps, int bitrateKbps) throws IOException {
        for (Candidate c : available()) {
            try {
                return open(c, width, height, fps, bitrateKbps);
            } catch (IOException e) {
                LOG.warn("Encoder {} failed to open at {}x{}: {}", c.label(), width, height, e.getMessage());
            }
        }
        throw new IOException("No working H.264 encoder found");
    }

    /** Candidates that successfully encoded a probe frame (result cached per session). */
    public static List<Candidate> available() {
        List<Candidate> list = new ArrayList<>();
        for (Candidate c : CANDIDATES)
            if (PROBED.computeIfAbsent(c.label(), k -> probe(c)))
                list.add(c);
        return list;
    }

    private static boolean probe(Candidate c) {
        ByteBuffer gray = ByteBuffer.allocateDirect(320 * 180 * 4);
        for (int i = 0; i < 320 * 180; i++) gray.putInt(0xFF808080);
        gray.flip();
        try (H264Encoder enc = open(c, 320, 180, 30, 500)) {
            enc.encode(gray, 320, 180, 0);
            LOG.info("Encoder {} is available", c.label());
            return true;
        } catch (Throwable t) {
            LOG.info("Encoder {} is unavailable: {}", c.label(), t.getMessage());
            return false;
        }
    }

    public Candidate getCandidate() {
        return candidate;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFps() {
        return fps;
    }

    /** The next encoded frame will be a key frame (e.g. when a new viewer joins). */
    public void requestKeyFrame() {
        forceKeyFrame = true;
    }

    /**
     * Scales and encodes one frame.
     *
     * @param bgra  direct buffer with tightly packed BGRA pixels, {@code srcW * srcH * 4} bytes from position 0
     * @param ptsMs presentation time in ms; made strictly increasing if needed
     * @return the packets the encoder produced (may be empty while the encoder buffers)
     */
    public List<EncodedPacket> encode(ByteBuffer bgra, int srcW, int srcH, long ptsMs) throws IOException {
        if (sws == null || srcW != swsSrcW || srcH != swsSrcH) {
            if (sws != null) sws_freeContext(sws);
            sws = sws_getContext(srcW, srcH, AV_PIX_FMT_BGRA, width, height, candidate.pixFmt(),
                    SWS_BILINEAR, null, null, (DoublePointer) null);
            if (sws == null || sws.isNull()) throw new IOException("Could not create scaler");
            swsSrcW = srcW;
            swsSrcH = srcH;
        }

        int ret = av_frame_make_writable(frame);
        if (ret < 0) throw new IOException("Frame not writable: " + FFmpegUtil.error(ret));

        BytePointer src = new BytePointer(bgra);
        srcPlanes.put(0, src);
        srcStride.put(0, srcW * 4);
        sws_scale(sws, srcPlanes, srcStride, 0, srcH, frame.data(), frame.linesize());
        src.close();

        if (ptsMs <= lastPts) ptsMs = lastPts + 1;
        lastPts = ptsMs;
        frame.pts(ptsMs);
        frame.pict_type(forceKeyFrame ? AV_PICTURE_TYPE_I : AV_PICTURE_TYPE_NONE);
        forceKeyFrame = false;

        ret = avcodec_send_frame(ctx, frame);
        if (ret < 0) throw new IOException("send_frame failed: " + FFmpegUtil.error(ret));
        return drain();
    }

    private List<EncodedPacket> drain() throws IOException {
        List<EncodedPacket> out = new ArrayList<>(1);
        while (true) {
            int ret = avcodec_receive_packet(ctx, pkt);
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) break;
            if (ret < 0) throw new IOException("receive_packet failed: " + FFmpegUtil.error(ret));

            byte[] data = new byte[pkt.size()];
            pkt.data().get(data);
            out.add(new EncodedPacket(data, pkt.pts(), (pkt.flags() & AV_PKT_FLAG_KEY) != 0));
            av_packet_unref(pkt);
        }
        return out;
    }

    @Override
    public void close() {
        if (ctx != null) {
            avcodec_free_context(ctx);
            ctx = null;
        }
        if (frame != null) {
            av_frame_free(frame);
            frame = null;
        }
        if (pkt != null) {
            av_packet_free(pkt);
            pkt = null;
        }
        if (sws != null) {
            sws_freeContext(sws);
            sws = null;
        }
        srcPlanes.close();
        srcStride.close();
    }
}
