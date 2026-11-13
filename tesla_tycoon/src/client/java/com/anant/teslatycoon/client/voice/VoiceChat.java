package com.anant.teslatycoon.client.voice;

import com.anant.teslatycoon.TeslaTycoon;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Push-to-talk voice chat: hold V and speak; it is sent to chat as if typed (so Seth Gotiya answers it).
 *
 *   V pressed   the microphone opens (a soft click) - nothing is recorded before that, nothing after release
 *   holding     every 20 ms frame goes through the voice detector; after you spoke, {@code silenceSeconds} (2-4 s) of quiet sends
 *               what you said and it keeps listening for the next sentence
 *   V released  the microphone closes at once; what you said since the last send is sent right away
 *   sending     cleaned up (VoiceDsp), recognised offline (Transcriber, whisper.cpp), then typed into chat
 * Nothing is sent when no speech was heard (no "phantom" messages from fan noise), and a too-quiet or wrong microphone is reported.
 */
public final class VoiceChat {
    public static KeyBinding TALK;

    private static VoiceConfig cfg;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TeslaTycoon-Speech");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static volatile Transcriber transcriber;
    private static volatile boolean loadStarted;
    private static volatile String loadError;

    // ---- capture state (touched by the mic thread and the client thread)
    private static MicCapture mic;
    private static final Object LOCK = new Object();
    private static float[] buf = new float[VoiceDsp.RATE * 4];
    private static int len;
    private static boolean heard;
    private static int quiet;
    private static double loudestDb = -120;
    private static VoiceDsp.Vad vad = new VoiceDsp.Vad();

    // ---- HUD
    private static volatile boolean listening;
    private static volatile boolean speaking;
    private static volatile double level;
    private static volatile int working;
    private static volatile long dlDone = -1;
    private static volatile long dlTotal;
    private static volatile String note;
    private static volatile int noteColor;
    private static volatile long noteUntil;

    private VoiceChat() {}

    public static void init() {
        TALK = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tesla_tycoon.talk", GLFW.GLFW_KEY_V, "key.categories.tesla_tycoon"));
        cfg = VoiceConfig.load();
        try {
            TeslaTycoon.LOGGER.info("[voice] microphones: {}  (put part of a name into config/tesla_tycoon/voice.json \"micDevice\" to pick one)", MicCapture.devices());
        } catch (Throwable t) {
            TeslaTycoon.LOGGER.info("[voice] cannot list the microphones: {}", t.toString());
        }
    }

    /** Every client tick: follows the key. */
    public static void tick(MinecraftClient client) {
        if (cfg == null || !cfg.enabled) {
            return;
        }
        // warm up the recogniser in the background as soon as we are in a world (if the model is already on disk)
        if (client.player != null && !loadStarted && ModelStore.present(cfg.model)) {
            ensureModel();
        }
        debugFeed(client);
        boolean held = TALK.isPressed() && client.player != null && client.currentScreen == null;
        if (held && mic == null) {
            press(client);
        } else if (!held && mic != null) {
            release(client);
        }
    }

    private static void press(MinecraftClient client) {
        ensureModel();
        synchronized (LOCK) {
            len = 0;
            heard = false;
            quiet = 0;
            loudestDb = -120;
            vad = new VoiceDsp.Vad();
        }
        try {
            mic = MicCapture.open(cfg.micDevice, VoiceChat::onFrame);
            mic.start();
            listening = true;
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.8f, 0.35f));
        } catch (Throwable e) {
            mic = null;
            listening = false;
            show("No microphone available (" + e.getMessage() + "). Is it plugged in, or used by another program?", 0xFF6060, 6000);
        }
    }

    private static void release(MinecraftClient client) {
        MicCapture m = mic;
        mic = null;
        listening = false;
        speaking = false;
        if (m != null) {
            m.stop();
        }
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1.3f, 0.3f));
        synchronized (LOCK) {
            if (heard) {
                submit(java.util.Arrays.copyOf(buf, len));
            } else if (len > VoiceDsp.RATE / 2) {
                if (loudestDb < -58) {
                    show("I heard nothing at all - is the microphone muted, or the wrong one? (config/tesla_tycoon/voice.json)", 0xFFB040, 6000);
                } else {
                    show("No speech heard - hold V while you talk.", 0xFFB040, 3000);
                }
            }
            len = 0;
            heard = false;
        }
    }

    /** One 20 ms frame from the microphone (mic thread). */
    private static void onFrame(float[] f) {
        synchronized (LOCK) {
            if (len + f.length > buf.length) {
                buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            }
            System.arraycopy(f, 0, buf, len, f.length);
            len += f.length;
            boolean sp = vad.speech(f, 0);
            level = vad.level;
            loudestDb = Math.max(loudestDb, vad.lastDb);
            speaking = sp;
            if (sp) {
                heard = true;
                quiet = 0;
            } else {
                quiet++;
            }
            boolean pause = heard && quiet >= (int) (cfg.silenceSeconds * 50);
            boolean tooLong = len >= VoiceDsp.RATE * 28;
            if (pause || tooLong) {
                if (heard) {
                    submit(java.util.Arrays.copyOf(buf, len));
                }
                len = 0;
                heard = false;
                quiet = 0;
            }
        }
    }

    private static boolean debugDone;

    /** Tests only (-Dtesla_tycoon.voicetest=file.wav, a machine without a microphone): 60 s after joining, the recording is fed
     * through exactly the path a held V key takes - frames, voice detector, auto-send on pauses, release - instead of the mic. */
    private static void debugFeed(MinecraftClient client) {
        String file = System.getProperty("tesla_tycoon.voicetest");
        if (file == null || debugDone || client.player == null || client.player.age < 20 * 60 || transcriber == null) {
            return;
        }
        debugDone = true;
        new Thread(() -> {
            try (javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.File(file))) {
                javax.sound.sampled.AudioFormat f = in.getFormat();
                int ch = f.getChannels();
                byte[] b = in.readAllBytes();
                float[] mono = new float[b.length / (2 * ch)];
                for (int i = 0; i < mono.length; i++) {
                    float v = 0;
                    for (int c = 0; c < ch; c++) {
                        int o = (i * ch + c) * 2;
                        v += (short) ((b[o] & 0xff) | (b[o + 1] << 8)) / 32768f;
                    }
                    mono[i] = v / ch;
                }
                float[] x = new MicCapture.Resampler((int) f.getSampleRate()).process(mono);
                synchronized (LOCK) {
                    len = 0;
                    heard = false;
                    quiet = 0;
                    vad = new VoiceDsp.Vad();
                }
                listening = true;
                TeslaTycoon.LOGGER.info("[voice] test recording {} ({} s) instead of the microphone", file, x.length / VoiceDsp.RATE);
                for (int i = 0; i + VoiceDsp.FRAME <= x.length; i += VoiceDsp.FRAME) {
                    onFrame(java.util.Arrays.copyOfRange(x, i, i + VoiceDsp.FRAME));
                    Thread.sleep(20);                                  // real time, like the microphone
                }
                listening = false;
                synchronized (LOCK) {
                    if (heard) {
                        submit(java.util.Arrays.copyOf(buf, len));
                    }
                    len = 0;
                    heard = false;
                }
            } catch (Exception e) {
                TeslaTycoon.LOGGER.warn("[voice] test recording failed", e);
            }
        }, "TeslaTycoon-VoiceTest").start();
    }

    // ------------------------------------------------------------------------------------------------------ recognition
    private static void ensureModel() {
        if (loadStarted) {
            return;
        }
        loadStarted = true;
        WORKER.execute(() -> {
            try {
                if (!ModelStore.present(cfg.model)) {
                    dlDone = 0;
                    ModelStore.download(cfg.model, (d, t) -> {
                        dlDone = d;
                        dlTotal = t;
                    });
                    dlDone = -1;
                    show("Speech model ready. Hold V and talk to Gotiya!", 0x7CFC9A, 4000);
                }
                transcriber = new Transcriber(ModelStore.path(cfg.model), cfg.threadCount(), "fast".equals(cfg.quality));
                TeslaTycoon.LOGGER.info("[voice] speech recognition ready ({} model, {} threads)", cfg.model, cfg.threadCount());
            } catch (Throwable e) {
                dlDone = -1;
                loadError = e.getMessage() == null ? e.toString() : e.getMessage();
                TeslaTycoon.LOGGER.warn("[voice] speech recognition unavailable: {}", e.toString());
                show("Voice chat unavailable: " + loadError, 0xFF6060, 8000);
            }
        });
    }

    private static void submit(float[] audio) {
        working++;
        WORKER.execute(() -> {
            try {
                Transcriber t = transcriber;
                if (t == null) {
                    show(loadError != null ? "Voice chat unavailable: " + loadError : "The speech model is still loading, say it again in a moment.", 0xFF6060, 5000);
                    return;
                }
                VoiceDsp.Result r = VoiceDsp.clean(audio, cfg.denoise);
                if (r.speechMs < 250) {
                    show("Didn't catch that - speak a little louder or closer to the mic.", 0xFFB040, 4000);
                    return;
                }
                long t0 = System.nanoTime();
                String text = t.transcribe(r.audio, cfg.language);
                TeslaTycoon.LOGGER.info("[voice] {} ms speech, gain {} dB, recognised in {} ms: \"{}\"", r.speechMs, Math.round(r.gainDb),
                        (System.nanoTime() - t0) / 1_000_000, text);
                if (text.isEmpty()) {
                    show("Didn't catch that - try again a little slower.", 0xFFB040, 4000);
                    return;
                }
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    if (mc.player != null && mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendChatMessage(text);
                    }
                });
            } catch (Throwable e) {
                TeslaTycoon.LOGGER.warn("[voice] recognition failed", e);
                show("Voice recognition failed: " + e, 0xFF6060, 5000);
            } finally {
                working--;
            }
        });
    }

    private static void show(String msg, int color, long ms) {
        note = msg;
        noteColor = color;
        noteUntil = System.currentTimeMillis() + ms;
    }

    // ------------------------------------------------------------------------------------------------------ HUD
    private static long lastTrace;

    public static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || cfg == null || !cfg.enabled) {
            return;
        }
        var tr = mc.textRenderer;
        int cx = mc.getWindow().getScaledWidth() / 2;
        int y = mc.getWindow().getScaledHeight() / 2 + 22;          // just under the crosshair: chat (drawn later) can never cover it
        String line = null;
        int color = 0xFFFFFF;
        if (dlDone >= 0) {
            long pct = dlTotal > 0 ? dlDone * 100 / dlTotal : 0;
            line = "Downloading the speech model (one time): " + pct + "%  " + dlDone / 1000_000 + " / " + Math.max(1, dlTotal / 1000_000) + " MB";
            color = 0x9AD8FF;
        } else if (listening) {
            line = (speaking ? "● Listening - talking" : "● Listening - speak now") + "   (release V to send)";
            color = speaking ? 0x7CFC9A : 0xFF7070;
        } else if (working > 0) {
            line = "Understanding what you said...";
            color = 0xFFE07A;
        } else if (note != null && System.currentTimeMillis() < noteUntil) {
            line = note;
            color = noteColor;
        }
        if (System.getProperty("tesla_tycoon.voicetest") != null && System.currentTimeMillis() - lastTrace > 1000) {
            lastTrace = System.currentTimeMillis();
            TeslaTycoon.LOGGER.info("[voice] hud: listening={} working={} line={}", listening, working, line);
        }
        if (line == null) {
            return;
        }
        int w = tr.getWidth(line);
        int bar = listening ? 64 : 0;
        int total = w + (bar > 0 ? bar + 8 : 0);
        int x0 = cx - total / 2;
        ctx.fill(x0 - 5, y - 4, x0 + total + 5, y + 12, 0xB0101418);
        ctx.drawTextWithShadow(tr, line, x0, y, color);
        if (bar > 0) {
            int bx = x0 + w + 8;
            ctx.fill(bx, y + 1, bx + bar, y + 8, 0xFF2A2F36);
            int lv = (int) Math.round(Math.max(0, Math.min(1, level)) * bar);
            ctx.fill(bx, y + 1, bx + lv, y + 8, speaking ? 0xFF4CDB7A : 0xFF8A93A0);
        }
    }
}
