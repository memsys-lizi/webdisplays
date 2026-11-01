package net.montoyo.wd.client.audio;

import net.montoyo.wd.utilities.browser.WDClientBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefRequest;

/**
 * Keeps every screen page "audible" with an inaudible tone (30 Hz at -60 dBFS).
 * <p>
 * CEF only starts capturing a page's audio once Chromium has noticed that the page makes sound, and stops after two
 * silent seconds; until the capture is up, the sound goes straight to the computer's speakers at full volume. With
 * the tone the capture starts as soon as the page loads and never stops, so real audio always goes through the
 * screen's positional sources. The tone stays below {@link ScreenAudioRoute}'s silence threshold.
 */
public final class AudioKeepAlive extends CefLoadHandlerAdapter {
    public static final AudioKeepAlive INSTANCE = new AudioKeepAlive();

    private static final String JS = "(function(){if(window.__wdKeepAlive)return;"
            + "try{var C=window.AudioContext||window.webkitAudioContext;if(!C)return;"
            + "var ac=new C(),o=ac.createOscillator(),g=ac.createGain();"
            + "o.frequency.value=30;g.gain.value=0.0015;o.connect(g);g.connect(ac.destination);o.start();"
            + "window.__wdKeepAlive=ac;"
            + "var r=function(){if(ac.state!=='running')ac.resume();};r();setInterval(r,2000);"
            + "}catch(e){}})();";

    private AudioKeepAlive() {
    }

    private static void inject(CefBrowser browser, CefFrame frame) {
        if (frame != null && frame.isMain() && browser instanceof WDClientBrowser wd && wd.getBe() != null)
            frame.executeJavaScript(JS, frame.getURL(), 0);
    }

    @Override
    public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
        inject(browser, frame); // as early as possible, before the page starts its own audio
    }

    @Override
    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        inject(browser, frame); // in case the early injection ran before the new document existed
    }
}
