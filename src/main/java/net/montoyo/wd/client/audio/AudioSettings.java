package net.montoyo.wd.client.audio;

import net.minecraft.util.Mth;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Per-player audio preferences, kept in {@code config/webdisplays-audio.properties}. They only affect this client
 * and are independent of Minecraft's own sound sliders.
 */
public final class AudioSettings {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/Audio");
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("webdisplays-audio.properties");

    /** Overall WebDisplays volume, percent. */
    private static int volume = 100;
    /** Pause Minecraft's background music while a screen is playing within earshot. */
    private static boolean pauseMusic = true;
    /** How far a screen without speakers can be heard, in blocks. */
    private static int screenRange = 32;

    private AudioSettings() {
    }

    public static int getVolume() {
        return volume;
    }

    public static void setVolume(int v) {
        previewVolume(v);
        save();
    }

    /** Changes the volume without writing the file (while a slider is being dragged). */
    public static void previewVolume(int v) {
        volume = Mth.clamp(v, 0, 100);
    }

    public static boolean isPauseMusic() {
        return pauseMusic;
    }

    public static void setPauseMusic(boolean v) {
        pauseMusic = v;
        save();
    }

    public static int getScreenRange() {
        return screenRange;
    }

    public static void setScreenRange(int v) {
        screenRange = Mth.clamp(v, 4, 64);
        save();
    }

    public static void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(r);
            volume = Mth.clamp(Integer.parseInt(p.getProperty("volume", "100").trim()), 0, 100);
            pauseMusic = Boolean.parseBoolean(p.getProperty("pauseMusic", "true").trim());
            screenRange = Mth.clamp(Integer.parseInt(p.getProperty("screenRange", "32").trim()), 4, 64);
        } catch (Exception e) {
            LOG.warn("Could not read {}, using defaults", FILE, e);
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("volume", Integer.toString(volume));
        p.setProperty("pauseMusic", Boolean.toString(pauseMusic));
        p.setProperty("screenRange", Integer.toString(screenRange));
        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(w, "WebDisplays audio (volume 0-100, screenRange 4-64 blocks); also /wdaudio in game");
        } catch (Exception e) {
            LOG.warn("Could not write {}", FILE, e);
        }
    }
}
