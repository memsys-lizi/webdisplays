package net.montoyo.wd.client.stream.audio;

import com.cinemamod.mcef.MCEF;
import net.montoyo.wd.client.stream.StreamManager;
import net.montoyo.wd.utilities.browser.WDClientBrowser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAudioHandler;
import org.cef.misc.CefAudioParameters;
import org.cef.misc.DataPointer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the audio of selected browsers through CEF's audio handler.
 * <p>
 * CEF asks {@link #getAudioParameters} once, when a browser starts producing sound; returning true reroutes that
 * browser's audio to {@link #onAudioStreamPacket} until it has been silent for a couple of seconds. Captured audio
 * is then played by a {@link BrowserAudioRoute} (through the stream's codec while a stream is running).
 */
public final class AudioTap implements CefAudioHandler {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Audio");
    public static final AudioTap INSTANCE = new AudioTap();

    /**
     * Mutes playing media for 4 s so CEF sees the page go quiet (it waits 2 s before calling it silent) and asks
     * {@link #getAudioParameters} again when the sound comes back. Also looks inside shadow DOMs, same-origin
     * iframes and media-like custom elements (players that wrap their own decoder).
     */
    private static final String RETRIGGER_JS = "(function(){var found=[];"
            + "function isMedia(e){return (e instanceof HTMLMediaElement)||(typeof e.muted==='boolean'&&typeof e.paused==='boolean');}"
            + "function visit(root){var all=root.querySelectorAll('*');for(var i=0;i<all.length;i++){var e=all[i];"
            + "if(isMedia(e)&&!e.paused&&!e.muted)found.push(e);"
            + "if(e.shadowRoot)visit(e.shadowRoot);"
            + "if(e.tagName==='IFRAME'){try{if(e.contentDocument)visit(e.contentDocument);}catch(x){}}}}"
            + "visit(document);"
            + "console.log('[WebDisplays] audio capture: muting '+found.length+' playing media element(s) for 4 s: '"
            + "+found.map(function(e){return e.tagName.toLowerCase();}).join(','));"
            + "found.forEach(function(e){e.muted=true;});"
            + "setTimeout(function(){found.forEach(function(e){e.muted=false;});},4000);})();";

    private final Set<CefBrowser> capture = ConcurrentHashMap.newKeySet();
    private final Map<CefBrowser, BrowserAudioRoute> routes = new ConcurrentHashMap<>();
    private final Map<CefBrowser, Integer> sampleRates = new ConcurrentHashMap<>();
    /** What CEF uses when the handler does not change the parameters (kAudioCDSampleRate). */
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private int packetErrors;
    private boolean registered;

    private AudioTap() {
    }

    /** Starts capturing this browser's audio; if it is already playing, its sound pauses for ~3 s while switching. */
    public synchronized void startCapture(WDClientBrowser browser) {
        if (!registered) {
            MCEF.getClient().addAudioHandler(this);
            registered = true;
        }
        capture.add(browser);
        LOG.info("Audio capture armed for {}@{}", browser.getClass().getSimpleName(), Integer.toHexString(System.identityHashCode(browser)));
        if (!routes.containsKey(browser))
            browser.executeJavaScript(RETRIGGER_JS, "", 0);
    }

    /** New audio from this browser plays normally again; a capture in progress finishes without the codec. */
    public void stopCapture(CefBrowser browser) {
        capture.remove(browser);
    }

    public BrowserAudioRoute routeFor(CefBrowser browser) {
        return routes.get(browser);
    }

    @Override
    public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {
        try {
            boolean take = capture.contains(browser);
            LOG.info("CEF audio started on {}@{}: {} ({} Hz, {} frames/buffer)", browser.getClass().getSimpleName(),
                    Integer.toHexString(System.identityHashCode(browser)), take ? "capturing" : "not streamed, normal playback",
                    params.sampleRate, params.framesPerBuffer);
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
            if (!capture.contains(browser) || !(browser instanceof WDClientBrowser wd)) {
                LOG.warn("Audio stream started for an unexpected browser {}", browser);
                return;
            }
            Integer rate = sampleRates.remove(browser);
            int sampleRate = params != null ? params.sampleRate : rate != null ? rate : DEFAULT_SAMPLE_RATE;
            LOG.info("Capturing browser audio: {} Hz, {} channels", sampleRate, channels);
            BrowserAudioRoute old = routes.put(browser, new BrowserAudioRoute(wd, sampleRate, channels));
            if (old != null) old.close();
        } catch (Throwable t) {
            LOG.error("onAudioStreamStarted failed", t);
        }
    }

    @Override
    public void onAudioStreamPacket(CefBrowser browser, DataPointer data, int frames, long pts) {
        try {
            fixContextClassLoader();
            BrowserAudioRoute route = routes.get(browser);
            if (route != null) route.onPacket(data, frames, StreamManager.streamForBrowser(browser));
        } catch (Throwable t) {
            if (packetErrors++ < 5) LOG.error("onAudioStreamPacket failed", t);
        }
    }

    @Override
    public void onAudioStreamStopped(CefBrowser browser) {
        try {
            if (browser == null) { // java-cef passes null once the browser is being destroyed
                routes.entrySet().removeIf(e -> {
                    if (!isLive(e.getKey())) {
                        LOG.info("Browser audio capture stopped (browser closed)");
                        e.getValue().close();
                        return true;
                    }
                    return false;
                });
                return;
            }
            BrowserAudioRoute route = routes.remove(browser);
            if (route != null) {
                LOG.info("Browser audio capture stopped");
                route.close();
            }
        } catch (Throwable t) {
            LOG.error("onAudioStreamStopped failed", t);
        }
    }

    @Override
    public void onAudioStreamError(CefBrowser browser, String text) {
        LOG.warn("Browser audio capture error: {}", text);
        onAudioStreamStopped(browser);
    }

    /** Whether the browser still belongs to a screen (it is detached/replaced when the screen unloads or closes). */
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
