package net.montoyo.wd.utilities.browser;

import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.MCEFClient;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.utilities.browser.handlers.js.queries.ElementCenterQuery;
import net.montoyo.wd.utilities.browser.handlers.js.JSQueryHandler;
import net.montoyo.wd.utilities.data.BlockSide;
import org.cef.browser.CefBrowser;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

import java.util.HashMap;

public class WDClientBrowser extends MCEFBrowser implements WDBrowser {
    /** Receives every full browser frame (BGRA, render thread), used for screen streaming. */
    @FunctionalInterface
    public interface FrameListener {
        void onFrame(ByteBuffer bgra, int width, int height);
    }

    private volatile FrameListener frameListener;

    ElementCenterQuery focusedEl = new ElementCenterQuery("ActiveElement", "document.activeElement");
    ElementCenterQuery pointerLockEl =
            new ElementCenterQuery("PointerElement", "document.pointerLockElement")
                    .addAdditional("unadjust", "document.webdisplays__unadjustPointerMotion")
            ;
    HashMap<String, JSQueryHandler> handlerHashMap = new HashMap<>();

    ScreenBlockEntity be;
    BlockSide side;

    public WDClientBrowser(MCEFClient client, String url, boolean transparent) {
        super(client, url, transparent);
    }

    public void setFrameListener(FrameListener listener) {
        this.frameListener = listener;
    }

    /** Makes CEF repaint the whole view, so a frame listener gets a picture even on a static page. */
    public void requestFullRepaint() {
        invalidate();
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        super.onPaint(browser, popup, dirtyRects, buffer, width, height);

        FrameListener listener = frameListener;
        if (listener != null && !popup && dirtyRects.length > 0)
            listener.onFrame(buffer, width, height);
    }

    @Override
    public HashMap<String, JSQueryHandler> queryHandlers() {
        return handlerHashMap;
    }

    @Override
    public ElementCenterQuery focusedElement() {
        return focusedEl;
    }

    @Override
    public ElementCenterQuery pointerLockElement() {
        return pointerLockEl;
    }

    @Override
    public void setBe(ScreenBlockEntity blockEntity, BlockSide side) {
        this.be = blockEntity;
        this.side = side;
    }

    @Override
    public ScreenBlockEntity getBe() {
        return be;
    }

    @Override
    public BlockSide getSide() {
        return side;
    }
}
