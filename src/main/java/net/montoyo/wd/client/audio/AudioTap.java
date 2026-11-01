package net.montoyo.wd.client.audio;

import com.cinemamod.mcef.MCEF;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAudioHandler;
import org.cef.misc.CefAudioParameters;
import org.cef.misc.DataPointer;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the audio of every screen browser through CEF's audio handler, so it plays from the screen (or its
 * speakers) in the world instead of straight out of the computer's speakers.
 * <p>
 * CEF asks {@link #getAudioParameters} when a browser starts producing sound; returning true reroutes that
 * browser's audio to {@link #onAudioStreamPacket} until it has been silent for a couple of seconds. Browsers that
 * do not belong to a screen (the MinePad) keep playing normally.
 */
public final class AudioTap implements CefAudioHandler {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Audio");
    public static final AudioTap INSTANCE = new AudioTap();

    /** What CEF uses when the handler does not change the parameters (kAudioCDSampleRate). */
    private static final int DEFAULT_SAMPLE_RATE = 44100;

    private final Map<CefBrowser, ScreenAudioRoute> routes = new ConcurrentHashMap<>();
    private final Map<CefBrowser, Integer> sampleRates = new ConcurrentHashMap<>();
    private int packetErrors;
    private boolean registered;

    private AudioTap() {
    }

    public synchronized void register() {
        if (registered || !MCEF.isInitialized()) return;
        MCEF.getClient().addAudioHandler(this);
        MCEF.getClient().addLoadHandler(AudioKeepAlive.INSTANCE);
        registered = true;
        LOG.info("WebDisplays positional audio enabled");
    }

    Collection<ScreenAudioRoute> routes() {
        return routes.values();
    }

    private static boolean isScreenBrowser(CefBrowser browser) {
        return browser instanceof WDClientBrowser wd && wd.getBe() != null;
    }

    @Override
    public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {
        try {
            boolean take = isScreenBrowser(browser);
            // java-cef passes null parameters to onAudioStreamStarted, so remember them here
            if (take) sampleRates.put(browser, params.sampleRate);
            return take;
        } catch (Throwable t) { // exceptions thrown back into JNI are silently dropped
            LOG.error("getAudioParameters failed", t);
            return false;
        }
    }

    @Override
    public void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int channels) {
        try {
            fixContextClassLoader();
            if (!isScreenBrowser(browser)) return;
            Integer rate = sampleRates.remove(browser);
            int sampleRate = params != null ? params.sampleRate : rate != null ? rate : DEFAULT_SAMPLE_RATE;
            LOG.info("Screen audio started: {} Hz, {} channels", sampleRate, channels);
            ScreenAudioRoute old = routes.put(browser, new ScreenAudioRoute((WDClientBrowser) browser, sampleRate, channels));
            if (old != null) old.close();
        } catch (Throwable t) {
            LOG.error("onAudioStreamStarted failed", t);
        }
    }

    @Override
    public void onAudioStreamPacket(CefBrowser browser, DataPointer data, int frames, long pts) {
        try {
            ScreenAudioRoute route = routes.get(browser);
            if (route != null) route.onPacket(data, frames);
        } catch (Throwable t) {
            if (packetErrors++ < 5) LOG.error("onAudioStreamPacket failed", t);
        }
    }

    @Override
    public void onAudioStreamStopped(CefBrowser browser) {
        try {
            if (browser == null) { // java-cef passes null once the browser is being destroyed
                pruneDead();
                return;
            }
            ScreenAudioRoute route = routes.remove(browser);
            if (route != null) route.close();
        } catch (Throwable t) {
            LOG.error("onAudioStreamStopped failed", t);
        }
    }

    @Override
    public void onAudioStreamError(CefBrowser browser, String text) {
        LOG.warn("Screen audio capture error: {}", text);
        onAudioStreamStopped(browser);
    }

    /** Drops the routes of browsers that no longer belong to a loaded screen. */
    void pruneDead() {
        routes.entrySet().removeIf(e -> {
            if (isLive(e.getKey())) return false;
            e.getValue().close();
            return true;
        });
    }

    void closeAll() {
        routes.values().forEach(ScreenAudioRoute::close);
        routes.clear();
    }

    private static boolean isLive(CefBrowser browser) {
        if (!(browser instanceof WDClientBrowser wd) || wd.getBe() == null || wd.getBe().isRemoved()) return false;
        var screen = wd.getBe().getScreen(wd.getSide());
        return screen != null && screen.browser == browser;
    }

    /** CEF's native threads have no context class loader, which Forge's class transformers trip over. */
    private static void fixContextClassLoader() {
        Thread t = Thread.currentThread();
        if (t.getContextClassLoader() == null) t.setContextClassLoader(AudioTap.class.getClassLoader());
    }
}
