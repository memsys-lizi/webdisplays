package net.montoyo.wd.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.montoyo.wd.client.audio.AudioSettings;
import net.montoyo.wd.client.audio.SpeakerRegistry;
import net.montoyo.wd.entity.SpeakerBlockEntity;
import net.montoyo.wd.net.WDNetworkRegistry;
import net.montoyo.wd.net.server_bound.C2SMessageSpeakerSettings;
import net.montoyo.wd.utilities.math.Vector3i;

/** Speaker settings (shared with everyone) plus this player's own WebDisplays audio settings. */
public class GuiSpeaker extends Screen {
    private final BlockPos pos;
    private final Vector3i screenPos;
    private int volume, range;
    private SpeakerBlockEntity.Channel channel;
    /** What the server has been told last, and when. */
    private C2SMessageSpeakerSettings sent;
    private long lastSend;
    private boolean dirty;

    public static void open(SpeakerBlockEntity speaker) {
        Minecraft.getInstance().setScreen(new GuiSpeaker(speaker));
    }

    private GuiSpeaker(SpeakerBlockEntity speaker) {
        super(Component.translatable("webdisplays.gui.speaker.title"));
        pos = speaker.getBlockPos();
        screenPos = speaker.getScreenPos();
        volume = speaker.getVolume();
        range = speaker.getRange();
        channel = speaker.getChannel();
    }

    @Override
    protected void init() {
        int w = 200, x = width / 2 - w / 2, y = height / 2 - 92;

        addRenderableWidget(new Slider(x, y + 24, w, volume / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("webdisplays.gui.speaker.volume", toVolume(value)));
            }

            @Override
            protected void applyValue() {
                volume = toVolume(value);
                changed(false);
            }

            @Override
            void released() {
                changed(true);
            }
        });
        addRenderableWidget(new Slider(x, y + 48, w, (range - SpeakerBlockEntity.MIN_RANGE) / (double) (SpeakerBlockEntity.MAX_RANGE - SpeakerBlockEntity.MIN_RANGE)) {
            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("webdisplays.gui.speaker.range", toRange(value)));
            }

            @Override
            protected void applyValue() {
                range = toRange(value);
                changed(false);
            }

            @Override
            void released() {
                changed(true);
            }
        });
        addRenderableWidget(new Button(x, y + 72, w, 20, channelLabel(), b -> {
            channel = channel.next();
            b.setMessage(channelLabel());
            changed(true);
        }));

        // ---- this player's own settings
        addRenderableWidget(new Slider(x, y + 120, w, AudioSettings.getVolume() / 100.0) {
            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("webdisplays.gui.speaker.myVolume", toVolume(value)));
            }

            @Override
            protected void applyValue() {
                AudioSettings.previewVolume(toVolume(value)); // heard right away, saved when the GUI closes
            }

            @Override
            void released() {
            }
        });
        addRenderableWidget(new Button(x, y + 144, w, 20, pauseMusicLabel(), b -> {
            AudioSettings.setPauseMusic(!AudioSettings.isPauseMusic());
            b.setMessage(pauseMusicLabel());
        }));

        addRenderableWidget(new Button(x, y + 172, w, 20, Component.translatable("gui.done"), b -> onClose()));
    }

    private static int toVolume(double v) {
        return (int) Math.round(Mth.clamp(v, 0, 1) * 100);
    }

    private static int toRange(double v) {
        return SpeakerBlockEntity.MIN_RANGE + (int) Math.round(Mth.clamp(v, 0, 1) * (SpeakerBlockEntity.MAX_RANGE - SpeakerBlockEntity.MIN_RANGE));
    }

    private Component channelLabel() {
        return Component.translatable("webdisplays.gui.speaker.channel",
                Component.translatable("webdisplays.gui.speaker.channel." + channel.name().toLowerCase()));
    }

    private static Component pauseMusicLabel() {
        return Component.translatable("webdisplays.gui.speaker.pauseMusic",
                Component.translatable(AudioSettings.isPauseMusic() ? "webdisplays.audio.on" : "webdisplays.audio.off"));
    }

    /** Applies the settings locally right away; tells the server at most every 100 ms (or now if {@code force}). */
    private void changed(boolean force) {
        SpeakerRegistry.setPreview(pos, new SpeakerRegistry.Settings(volume, range, channel));
        dirty = true;
        if (force || System.currentTimeMillis() - lastSend >= 100) send();
    }

    private void send() {
        dirty = false;
        C2SMessageSpeakerSettings msg = new C2SMessageSpeakerSettings(pos, volume, range, channel);
        if (msg.equals(sent)) return;
        WDNetworkRegistry.INSTANCE.sendToServer(msg);
        sent = msg;
        lastSend = System.currentTimeMillis();
    }

    @Override
    public void tick() {
        // the last drag may have ended outside the slider, where the slider never hears about the release
        if (dirty && System.currentTimeMillis() - lastSend >= 100) send();
    }

    @Override
    public void removed() {
        if (dirty) send();
        SpeakerRegistry.endPreview();
        AudioSettings.save();
        super.removed();
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        int y = height / 2 - 92;
        drawCenteredString(pose, font, title, width / 2, y, 0xFFFFFF);
        Component link = screenPos == null
                ? Component.translatable("webdisplays.gui.speaker.notLinked")
                : Component.translatable("webdisplays.gui.speaker.linked", screenPos.x, screenPos.y, screenPos.z);
        drawCenteredString(pose, font, link, width / 2, y + 12, screenPos == null ? 0xFFAA55 : 0xAAAAAA);
        drawCenteredString(pose, font, Component.translatable("webdisplays.gui.speaker.mine"), width / 2, y + 106, 0xFFFF77);
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private abstract static class Slider extends AbstractSliderButton {
        Slider(int x, int y, int width, double value) {
            super(x, y, width, 20, Component.empty(), value);
            updateMessage();
        }

        abstract void released();

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            released();
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            boolean ret = super.keyPressed(key, scan, mods); // arrow keys move the slider
            if (ret) released();
            return ret;
        }
    }
}
