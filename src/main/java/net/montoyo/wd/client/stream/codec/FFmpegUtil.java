package net.montoyo.wd.client.stream.codec;

import java.nio.charset.StandardCharsets;

import static org.bytedeco.ffmpeg.global.avutil.av_strerror;

final class FFmpegUtil {
    private FFmpegUtil() {
    }

    static String error(int code) {
        byte[] buf = new byte[256];
        av_strerror(code, buf, buf.length);
        int len = 0;
        while (len < buf.length && buf[len] != 0) len++;
        return new String(buf, 0, len, StandardCharsets.UTF_8) + " (" + code + ")";
    }
}
