package net.montoyo.wd.client.stream.codec;

/**
 * One compressed H.264 access unit (Annex B, SPS/PPS in-band on key frames).
 *
 * @param ptsMs presentation time in milliseconds since the stream started
 */
public record EncodedPacket(byte[] data, long ptsMs, boolean keyFrame) {
}
