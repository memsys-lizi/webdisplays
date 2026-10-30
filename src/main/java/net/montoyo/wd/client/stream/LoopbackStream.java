package net.montoyo.wd.client.stream;

import net.montoyo.wd.client.stream.audio.AudioTap;
import net.montoyo.wd.client.stream.audio.BrowserAudioRoute;
import net.montoyo.wd.client.stream.codec.EncodedPacket;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.browser.WDClientBrowser;

/**
 * Local test mode: the screen's own stream is encoded and immediately decoded again, and the decoded picture and
 * sound replace the browser's. Exercises the whole pipeline except the network.
 */
public final class LoopbackStream implements StreamEncoder.Sink {
    final ScreenData screen;
    final WDClientBrowser browser;
    final StreamStats stats = new StreamStats(StreamStats.Role.LOOPBACK);
    final StreamEncoder encoder;
    private final StreamDecoder decoder;

    LoopbackStream(ScreenData screen, WDClientBrowser browser, StreamEncoder.Settings settings) {
        this.screen = screen;
        this.browser = browser;
        stats.title = "WD loopback stream";
        stats.audioStatus = () -> {
            BrowserAudioRoute route = AudioTap.INSTANCE.routeFor(browser);
            return route == null ? "waiting for sound" : route.getPlayer().describe();
        };
        decoder = new StreamDecoder(stats, () -> {
            BrowserAudioRoute route = AudioTap.INSTANCE.routeFor(browser);
            return route == null ? null : route.getPlayer();
        }, false);
        encoder = new StreamEncoder(browser, settings, stats, this, false);
    }

    @Override
    public void video(EncodedPacket packet, long captureNanos) {
        decoder.video(packet, captureNanos);
    }

    @Override
    public void audio(EncodedPacket packet) {
        decoder.audio(packet);
    }

    int textureId() {
        return decoder.textureId();
    }

    void close() {
        encoder.close();
        decoder.close();
    }
}
