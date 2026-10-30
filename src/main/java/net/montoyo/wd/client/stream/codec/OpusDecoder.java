package net.montoyo.wd.client.stream.codec;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.ShortPointer;

import java.io.IOException;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;

/** Mono 48 kHz Opus decoder producing float samples. Not thread safe. */
public final class OpusDecoder implements AutoCloseable {
    /** Receives decoded mono samples; the array is reused after the call returns. */
    public interface SampleSink {
        void accept(float[] samples, int count);
    }

    private AVCodecContext ctx;
    private AVFrame frame;
    private AVPacket pkt;
    private float[] buf = new float[960];

    public OpusDecoder() throws IOException {
        AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_OPUS);
        if (codec == null || codec.isNull()) throw new IOException("No Opus decoder in this FFmpeg");

        ctx = avcodec_alloc_context3(codec);
        ctx.sample_rate(OpusEncoder.SAMPLE_RATE);
        av_channel_layout_default(ctx.ch_layout(), 1);
        int ret = avcodec_open2(ctx, codec, (AVDictionary) null);
        if (ret < 0) {
            close();
            throw new IOException("Could not open Opus decoder: " + FFmpegUtil.error(ret));
        }
        frame = av_frame_alloc();
        pkt = av_packet_alloc();
    }

    public void decode(EncodedPacket packet, SampleSink sink) throws IOException {
        int ret = av_new_packet(pkt, packet.data().length);
        if (ret < 0) throw new IOException("Could not allocate packet: " + FFmpegUtil.error(ret));
        pkt.data().put(packet.data());
        ret = avcodec_send_packet(ctx, pkt);
        av_packet_unref(pkt);
        if (ret < 0 && ret != AVERROR_EAGAIN()) return; // corrupt packet: skip it

        while (true) {
            ret = avcodec_receive_frame(ctx, frame);
            if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) break;
            if (ret < 0) throw new IOException("Opus receive_frame failed: " + FFmpegUtil.error(ret));

            int n = frame.nb_samples();
            if (buf.length < n) buf = new float[n];
            int fmt = frame.format();
            // mono: planar and packed layouts are identical, only channel 0 is used
            if (fmt == AV_SAMPLE_FMT_FLT || fmt == AV_SAMPLE_FMT_FLTP) {
                FloatPointer src = new FloatPointer(frame.data(0));
                src.get(buf, 0, n);
                src.close();
            } else if (fmt == AV_SAMPLE_FMT_S16 || fmt == AV_SAMPLE_FMT_S16P) {
                ShortPointer src = new ShortPointer(frame.data(0));
                for (int i = 0; i < n; i++) buf[i] = src.get(i) / 32768f;
                src.close();
            } else {
                throw new IOException("Unexpected Opus sample format " + fmt);
            }
            sink.accept(buf, n);
            av_frame_unref(frame);
        }
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
