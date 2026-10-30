package net.montoyo.wd.client.stream.codec;

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

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Low-latency H.264 decoder producing BGRA frames. Not thread safe: use from one thread.
 */
public final class H264Decoder implements AutoCloseable {
    /** Receives decoded frames. */
    public interface FrameSink {
        /** @return a direct buffer of at least {@code width * height * 4} bytes to convert the frame into */
        ByteBuffer acquire(int width, int height);

        /** The buffer from {@link #acquire} now holds the frame. */
        void publish(ByteBuffer bgra, int width, int height, long ptsMs);
    }

    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket pkt;
    private SwsContext sws;
    private final PointerPointer<BytePointer> dstPlanes = new PointerPointer<>(1);
    private final IntPointer dstStride = new IntPointer(1);

    public H264Decoder() throws IOException {
        AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_H264);
        if (codec == null || codec.isNull()) throw new IOException("No H.264 decoder in this FFmpeg");

        ctx = avcodec_alloc_context3(codec);
        ctx.flags(ctx.flags() | AV_CODEC_FLAG_LOW_DELAY);
        // frame threading adds a frame of latency per thread, slice threading does not
        ctx.thread_type(AVCodecContext.FF_THREAD_SLICE);
        ctx.thread_count(0);

        int ret = avcodec_open2(ctx, codec, (AVDictionary) null);
        if (ret < 0) {
            close();
            throw new IOException("Could not open H.264 decoder: " + FFmpegUtil.error(ret));
        }
        frame = av_frame_alloc();
        pkt = av_packet_alloc();
    }

    /**
     * Decodes one access unit; every completed frame is converted and handed to {@code sink}.
     *
     * @return the number of frames published
     */
    public int decode(EncodedPacket packet, FrameSink sink) throws IOException {
        int ret = av_new_packet(pkt, packet.data().length);
        if (ret < 0) throw new IOException("Could not allocate packet: " + FFmpegUtil.error(ret));
        pkt.data().put(packet.data());
        pkt.pts(packet.ptsMs());
        if (packet.keyFrame()) pkt.flags(pkt.flags() | AV_PKT_FLAG_KEY);

        ret = avcodec_send_packet(ctx, pkt);
        av_packet_unref(pkt);
        // corrupt data / missing reference frames: drop it, the next key frame recovers
        if (ret < 0 && ret != AVERROR_EAGAIN()) return 0;

        int published = 0;
        while (true) {
            ret = avcodec_receive_frame(ctx, frame);
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) break;
            if (ret < 0) throw new IOException("receive_frame failed: " + FFmpegUtil.error(ret));

            int w = frame.width();
            int h = frame.height();
            sws = sws_getCachedContext(sws, w, h, frame.format(), w, h, AV_PIX_FMT_BGRA,
                    SWS_POINT, null, null, (DoublePointer) null);
            if (sws == null || sws.isNull()) throw new IOException("Could not create scaler");

            ByteBuffer out = sink.acquire(w, h);
            BytePointer dst = new BytePointer(out);
            dstPlanes.put(0, dst);
            dstStride.put(0, w * 4);
            sws_scale(sws, frame.data(), frame.linesize(), 0, h, dstPlanes, dstStride);
            dst.close();

            sink.publish(out, w, h, frame.best_effort_timestamp());
            av_frame_unref(frame);
            published++;
        }
        return published;
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
        dstPlanes.close();
        dstStride.close();
    }
}
