package net.montoyo.wd.client.stream;

import com.mojang.blaze3d.systems.RenderSystem;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL12.*;

/**
 * GL texture holding decoded stream frames, uploaded the same way MCEF uploads browser frames (BGRA).
 * Render thread only.
 */
public final class StreamTexture {
    private int id;
    private int width = -1, height = -1;

    public int getId() {
        return id;
    }

    public void upload(ByteBuffer bgra, int w, int h) {
        RenderSystem.assertOnRenderThread();
        if (id == 0) {
            id = glGenTextures();
            RenderSystem.bindTexture(id);
            RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        } else {
            RenderSystem.bindTexture(id);
        }

        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, w);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_ROWS, 0);
        bgra.position(0);
        if (w != width || h != height) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, bgra);
            width = w;
            height = h;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, bgra);
        }
        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, 0);
        RenderSystem.bindTexture(0);
    }

    public void delete() {
        RenderSystem.assertOnRenderThread();
        if (id != 0) {
            glDeleteTextures(id);
            id = 0;
        }
    }
}
