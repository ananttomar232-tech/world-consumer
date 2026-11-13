package com.anant.teslatycoon.client.voice;

import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * The microphone, opened only while push-to-talk is held. It asks the sound card for 16 kHz mono first (what the recogniser
 * wants); most laptop mics only offer 48 or 44.1 kHz, sometimes stereo, so those are converted on the fly (stereo is averaged,
 * which also cancels a little of the noise of cheap dual-mic arrays). Delivers 20 ms frames of 16 kHz mono floats.
 */
public final class MicCapture {
    private static final float[][] FORMATS = {{16000, 1}, {48000, 1}, {44100, 1}, {48000, 2}, {44100, 2}, {32000, 1}, {22050, 1}, {16000, 2}};

    private final TargetDataLine line;
    private final AudioFormat format;
    private final Consumer<float[]> sink;
    private volatile boolean running;
    private Thread thread;

    private MicCapture(TargetDataLine line, AudioFormat format, Consumer<float[]> sink) {
        this.line = line;
        this.format = format;
        this.sink = sink;
    }

    /** Opens the microphone (the named one if it exists, else the system default). Throws if there is none or it is busy. */
    public static MicCapture open(String device, Consumer<float[]> sink) throws LineUnavailableException {
        LineUnavailableException last = null;
        for (Mixer.Info mi : mixers(device)) {
            for (float[] f : FORMATS) {
                AudioFormat fmt = new AudioFormat(f[0], 16, (int) f[1], true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
                try {
                    TargetDataLine line = mi == null ? (TargetDataLine) AudioSystem.getLine(info) : (TargetDataLine) AudioSystem.getMixer(mi).getLine(info);
                    line.open(fmt, (int) (fmt.getFrameRate() * fmt.getFrameSize() / 10));          // 100 ms buffer: low latency
                    return new MicCapture(line, fmt, sink);
                } catch (LineUnavailableException e) {
                    last = e;
                } catch (IllegalArgumentException e) {
                    // this format is not offered by the device: try the next one
                }
            }
        }
        throw last != null ? last : new LineUnavailableException("no microphone found");
    }

    private static java.util.List<Mixer.Info> mixers(String device) {
        java.util.List<Mixer.Info> out = new java.util.ArrayList<>();
        if (device != null && !device.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().toLowerCase(java.util.Locale.ROOT).contains(device.toLowerCase(java.util.Locale.ROOT))
                        && AudioSystem.getMixer(mi).isLineSupported(new DataLine.Info(TargetDataLine.class, null))) {
                    out.add(mi);
                }
            }
        }
        out.add(null);                                       // the system default
        return out;
    }

    /** Names of every recording device (for the config file). */
    public static java.util.List<String> devices() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(mi).isLineSupported(new DataLine.Info(TargetDataLine.class, null))) {
                out.add(mi.getName());
            }
        }
        return out;
    }

    public String describe() {
        return (int) format.getSampleRate() + " Hz, " + (format.getChannels() == 1 ? "mono" : "stereo");
    }

    public void start() {
        running = true;
        line.flush();
        line.start();
        thread = new Thread(this::loop, "TeslaTycoon-Mic");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops listening at once (push-to-talk released). */
    public void stop() {
        running = false;
        line.stop();
        line.flush();
        line.close();
    }

    private void loop() {
        int ch = format.getChannels();
        int rate = (int) format.getSampleRate();
        int chunkFrames = rate / 50;                           // 20 ms
        byte[] buf = new byte[chunkFrames * ch * 2];
        Resampler rs = new Resampler(rate);
        float[] pending = new float[VoiceDsp.FRAME * 4];
        int have = 0;
        while (running) {
            int n = line.read(buf, 0, buf.length);
            if (n <= 0) {
                continue;
            }
            int frames = n / (2 * ch);
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                float s = 0;
                for (int c = 0; c < ch; c++) {
                    int o = (i * ch + c) * 2;
                    s += (short) ((buf[o] & 0xff) | (buf[o + 1] << 8)) / 32768f;
                }
                mono[i] = s / ch;
            }
            float[] out = rs.process(mono);
            for (float v : out) {
                if (have == pending.length) {
                    pending = java.util.Arrays.copyOf(pending, pending.length * 2);
                }
                pending[have++] = v;
            }
            int done = 0;
            while (have - done >= VoiceDsp.FRAME) {
                float[] fr = new float[VoiceDsp.FRAME];
                System.arraycopy(pending, done, fr, 0, VoiceDsp.FRAME);
                done += VoiceDsp.FRAME;
                if (running) {
                    sink.accept(fr);
                }
            }
            System.arraycopy(pending, done, pending, 0, have - done);
            have -= done;
        }
    }

    /** Streaming converter to 16 kHz: a moving-average anti-alias filter, then linear interpolation (keeps its phase between chunks). */
    static final class Resampler {
        private final double ratio;
        private final int width;
        private final float[] hist;
        private int hpos;
        private double acc;
        private double pos;
        private float prev;
        private boolean first = true;

        Resampler(int rate) {
            this.ratio = (double) rate / VoiceDsp.RATE;
            this.width = Math.max(1, (int) Math.round(ratio));
            this.hist = new float[width];
        }

        float[] process(float[] in) {
            if (ratio == 1.0) {
                return in;
            }
            float[] filt = new float[in.length];
            for (int i = 0; i < in.length; i++) {
                acc += in[i] - hist[hpos];
                hist[hpos] = in[i];
                hpos = (hpos + 1) % width;
                filt[i] = (float) (acc / width);
            }
            java.util.List<Float> out = new java.util.ArrayList<>();
            // positions are relative to [prev, filt[0], filt[1], ...] (index 0 = the last sample of the previous chunk)
            if (first) {
                prev = filt.length > 0 ? filt[0] : 0;
                first = false;
            }
            while (pos < filt.length) {
                int a = (int) Math.floor(pos);
                double t = pos - a;
                float sa = a == 0 ? prev : filt[a - 1];
                float sb = filt[Math.min(a, filt.length - 1)];
                out.add((float) (sa * (1 - t) + sb * t));
                pos += ratio;
            }
            pos -= filt.length;
            if (filt.length > 0) {
                prev = filt[filt.length - 1];
            }
            float[] r = new float[out.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = out.get(i);
            }
            return r;
        }
    }
}
