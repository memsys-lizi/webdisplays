package net.montoyo.wd.client.stream.codec;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.FloatPointer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/**
 * Mono 48 kHz Opus encoder in low-delay mode. Feed it exactly {@link #getFrameSize()} samples per call.
 * Not thread safe.
 */
public final class OpusEncoder implements AutoCloseable {
    public static final int SAMPLE_RATE = 48000;

    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket pkt;
    private final int frameSize;
    private long samplePts;

    public OpusEncoder(int bitrate) throws IOException {
        AVCodec codec = avcodec_find_encoder_by_name("libopus");
        if (codec == null || codec.isNull()) throw new IOException("libopus is not built into this FFmpeg");

        ctx = avcodec_alloc_context3(codec);
        ctx.sample_rate(SAMPLE_RATE);
        ctx.sample_fmt(AV_SAMPLE_FMT_FLT);
        av_channel_layout_default(ctx.ch_layout(), 1);
        ctx.bit_rate(bitrate);
        ctx.time_base(av_make_q(1, SAMPLE_RATE));

        AVDictionary opts = new AVDictionary(null);
        av_dict_set(opts, "application", "lowdelay", 0);
        av_dict_set(opts, "frame_duration", "10", 0);
        av_dict_set(opts, "vbr", "constrained", 0);
        int ret = avcodec_open2(ctx, codec, opts);
        av_dict_free(opts);
        if (ret < 0) {
            close();
            throw new IOException("Could not open Opus encoder: " + FFmpegUtil.error(ret));
        }
        frameSize = ctx.frame_size();

        frame = av_frame_alloc();
        frame.format(AV_SAMPLE_FMT_FLT);
        frame.nb_samples(frameSize);
        frame.sample_rate(SAMPLE_RATE);
        av_channel_layout_copy(frame.ch_layout(), ctx.ch_layout());
        ret = av_frame_get_buffer(frame, 0);
        if (ret < 0) {
            close();
            throw new IOException("Could not allocate audio frame: " + FFmpegUtil.error(ret));
        }
        pkt = av_packet_alloc();
    }

    /** Samples per Opus frame (480 = 10 ms). */
    public int getFrameSize() {
        return frameSize;
    }

    /** Encodes {@link #getFrameSize()} samples starting at {@code offset}. */
    public List<EncodedPacket> encode(float[] samples, int offset) throws IOException {
        int ret = av_frame_make_writable(frame);
        if (ret < 0) throw new IOException("Audio frame not writable: " + FFmpegUtil.error(ret));

        FloatPointer dst = new FloatPointer(frame.data(0));
        dst.put(samples, offset, frameSize);
        dst.close();
        frame.pts(samplePts);
        samplePts += frameSize;

        ret = avcodec_send_frame(ctx, frame);
        if (ret < 0) throw new IOException("Opus send_frame failed: " + FFmpegUtil.error(ret));

        List<EncodedPacket> out = new ArrayList<>(1);
        while (true) {
            ret = avcodec_receive_packet(ctx, pkt);
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) break;
            if (ret < 0) throw new IOException("Opus receive_packet failed: " + FFmpegUtil.error(ret));

            byte[] data = new byte[pkt.size()];
            pkt.data().get(data);
            out.add(new EncodedPacket(data, pkt.pts() * 1000 / SAMPLE_RATE, true));
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
    }
}
