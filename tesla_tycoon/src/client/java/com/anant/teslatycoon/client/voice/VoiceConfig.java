package com.anant.teslatycoon.client.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/tesla_tycoon/voice.json - written with the defaults on first start, edit it while the game is closed.
 * <pre>
 *   enabled         true / false
 *   model           "base" (148 MB, the default: accurate and quick) | "small" (488 MB: better Hindi, needs a fast CPU) | "tiny" (75 MB: old laptops)
 *   language        "auto" (English, Hindi, Hinglish) | "en" | "hi"
 *   silenceSeconds  2.0 .. 4.0 - while V is held, this much quiet after you spoke sends what you said
 *   denoise         true - remove steady background noise (fan, hum, hiss) before recognition
 *   micDevice       ""  - part of the name of the microphone to use ("" = the system default); the log lists the names
 *   threads         0   - CPU threads for recognition (0 = automatic)
 *   quality         "best" (beam search: the most accurate, the default) | "fast" (about twice as quick, slightly less accurate)
 * </pre>
 */
public final class VoiceConfig {
    public boolean enabled = true;
    public String model = "base";
    public String language = "auto";
    public double silenceSeconds = 2.5;
    public boolean denoise = true;
    public String micDevice = "";
    public int threads = 0;
    public String quality = "best";

    public static Path dir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("tesla_tycoon");
    }

    public static VoiceConfig load() {
        Path p = dir().resolve("voice.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        VoiceConfig c = null;
        try {
            if (Files.exists(p)) {
                c = gson.fromJson(Files.readString(p, StandardCharsets.UTF_8), VoiceConfig.class);
            }
        } catch (Exception e) {
            com.anant.teslatycoon.TeslaTycoon.LOGGER.warn("voice.json is broken, using the defaults: {}", e.toString());
        }
        if (c == null) {
            c = new VoiceConfig();
        }
        c.silenceSeconds = Math.max(2.0, Math.min(4.0, c.silenceSeconds));
        if (!java.util.List.of("tiny", "base", "small").contains(c.model)) {
            c.model = "base";
        }
        if (!"fast".equals(c.quality)) {
            c.quality = "best";
        }
        if (!java.util.List.of("auto", "en", "hi").contains(c.language)) {
            c.language = "auto";
        }
        try {
            Files.createDirectories(dir());
            Files.writeString(p, gson.toJson(c), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // read-only config folder: run with what we have
        }
        return c;
    }

    public int threadCount() {
        if (threads > 0) {
            return threads;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(8, cores - 2));          // leave cores for the game itself
    }
}
