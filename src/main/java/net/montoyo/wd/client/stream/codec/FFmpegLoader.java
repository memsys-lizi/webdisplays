package net.montoyo.wd.client.stream.codec;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the FFmpeg natives bundled in the mod jar.
 * <p>
 * Inside Forge the mod jar is served through a "union:" filesystem that JavaCPP cannot reliably extract from,
 * so the libraries listed in {@code META-INF/webdisplays-natives.txt} (generated at build time) are copied to a
 * plain directory first and JavaCPP is told to look there before its own resources.
 * Outside of a packaged jar (dev / benchmark) the list is absent and JavaCPP's normal loading is used.
 */
public final class FFmpegLoader {
    private static final Logger LOG = LogManager.getLogger("WebDisplays/FFmpeg");
    private static final String NATIVES_LIST = "META-INF/webdisplays-natives.txt";

    private static Boolean loaded;
    private static String failure;

    private FFmpegLoader() {
    }

    /**
     * @param nativesRoot directory to extract natives into (e.g. {@code .minecraft/webdisplays/natives})
     * @return whether FFmpeg is usable
     */
    public static synchronized boolean ensureLoaded(Path nativesRoot) {
        if (loaded != null) return loaded;

        try {
            Path dir = extractNatives(nativesRoot);
            if (dir != null) {
                // must be set before org.bytedeco.javacpp.Loader is initialized
                System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");
                System.setProperty("org.bytedeco.javacpp.cachedir", dir.resolve("cache").toString());
                String libPath = System.getProperty("java.library.path", "");
                System.setProperty("java.library.path", dir + (libPath.isEmpty() ? "" : File.pathSeparator + libPath));
            }

            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avutil.class);
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.swscale.class);
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avcodec.class);

            int v = org.bytedeco.ffmpeg.global.avcodec.avcodec_version();
            LOG.info("FFmpeg loaded (libavcodec {}.{}.{})", v >> 16, (v >> 8) & 0xFF, v & 0xFF);
            loaded = true;
        } catch (Throwable t) {
            LOG.error("Could not load FFmpeg, screen streaming is unavailable", t);
            failure = t.toString();
            loaded = false;
        }
        return loaded;
    }

    public static synchronized String getFailure() {
        return failure;
    }

    private static Path extractNatives(Path nativesRoot) throws IOException {
        List<String> entries = new ArrayList<>();
        try (InputStream in = FFmpegLoader.class.getClassLoader().getResourceAsStream(NATIVES_LIST)) {
            if (in == null) return null; // not running from the packaged jar

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) entries.add(line);
            }
        }

        // versioned directory, so an update never mixes libraries
        Path dir = nativesRoot.resolve(Integer.toHexString(String.join("\n", entries).hashCode()));
        Files.createDirectories(dir);

        long start = System.currentTimeMillis();
        int copied = 0;
        for (String entry : entries) {
            Path target = dir.resolve(entry.substring(entry.lastIndexOf('/') + 1));
            if (Files.exists(target)) continue;

            try (InputStream in = FFmpegLoader.class.getClassLoader().getResourceAsStream(entry)) {
                if (in == null) throw new IOException("Missing bundled native " + entry);
                Path tmp = Files.createTempFile(dir, "extract", ".tmp");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        if (copied > 0)
            LOG.info("Extracted {} native libraries to {} in {} ms", copied, dir, System.currentTimeMillis() - start);
        return dir;
    }
}
