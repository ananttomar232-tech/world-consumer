package com.anant.teslatycoon.client.voice;

/**
 * Cleans up what a cheap laptop microphone records before the speech recogniser hears it. Everything works on 16 kHz mono floats
 * (-1..1). Pure Java, no game classes, so it can be tested on its own.
 *
 * Live (while V is held), frame by frame (20 ms):
 *   {@link Vad} - is this frame speech? An adaptive noise floor follows the fan / room hum, so a noisy laptop does not count as
 *   talking and a quiet voice still does.
 * Afterwards, on the whole utterance ({@link #clean}):
 *   1. DC removal + a 90 Hz high-pass: rumble, desk knocks and mains hum go,
 *   2. gentle spectral noise reduction: the steady noise (fan, hiss, hum harmonics) is measured from the quietest moments of the
 *      clip itself and subtracted - gently, because over-cleaned speech confuses the recogniser more than a little noise does,
 *   3. trimming: leading / trailing silence is cut (keeping 0.3 s around the speech),
 *   4. automatic gain: a whispered or far-away voice is brought up to a normal level (up to +30 dB), peaks are soft-limited, so a
 *      too-loud mic that clips does not distort into nonsense either.
 */
public final class VoiceDsp {
    public static final int RATE = 16000;
    public static final int FRAME = 320;                   // 20 ms

    private VoiceDsp() {}

    // ------------------------------------------------------------------------------------------------------ live voice activity
    /**
     * Frame-by-frame speech detector with an adaptive noise floor. The floor follows the *typical* background level (it falls
     * quickly towards quieter frames and rises slowly), so the random swells of a noisy laptop mic (fan, hiss) stay below the
     * threshold and only a voice - clearly louder for a while - counts. 60 ms above the threshold starts speech, a 200 ms hangover
     * bridges the short gaps between words.
     */
    public static final class Vad {
        private double floorDb = -60;
        private boolean init;
        private int above;
        private int hang;
        private int frames;
        public double lastDb = -100;
        public double level;                                // 0..1 for the HUD meter

        /** true if this 20 ms frame is speech. */
        public boolean speech(float[] f, int off) {
            double sum = 0;
            for (int i = 0; i < FRAME; i++) {
                double v = f[off + i];
                sum += v * v;
            }
            double db = Math.max(-75, 10 * Math.log10(sum / FRAME + 1e-12));     // (a driver's digital silence must not drag the floor away)
            lastDb = db;
            frames++;
            if (!init) {
                floorDb = db;
                init = true;
            }
            if (db < floorDb) {
                floorDb += (db - floorDb) * 0.25;           // quieter: follow fast (but one dropout does not reset it)
            } else if (db - floorDb < 12) {
                floorDb += (db - floorDb) * (frames < 25 ? 0.2 : 0.012);   // background drifting up: follow slowly
            } else if (above > 100) {
                floorDb += (db - floorDb) * 0.05;           // loud without a single pause for 2 s: that is a new background, not a voice
            } else {
                floorDb += 0.004;                           // loud (a voice): hardly move
            }
            level = Math.max(0, Math.min(1, (db + 60) / 50));
            boolean loud = db - floorDb > 7.0 && db > -66;
            above = loud ? above + 1 : 0;
            if (above >= 3) {
                hang = 10;
                return true;
            }
            if (hang > 0) {
                hang--;
                return true;
            }
            return false;
        }

        public double floorDb() {
            return floorDb;
        }
    }

    // ------------------------------------------------------------------------------------------------------ offline clean-up
    public static final class Result {
        public final float[] audio;
        public final int speechMs;
        public final double gainDb;

        Result(float[] audio, int speechMs, double gainDb) {
            this.audio = audio;
            this.speechMs = speechMs;
            this.gainDb = gainDb;
        }
    }

    public static Result clean(float[] in, boolean denoise) {
        float[] x = highPass(in);
        if (denoise && x.length > 4 * 512) {
            x = spectralDenoise(x);
        }
        // where is the speech? (the same detector as live, on the cleaned signal)
        int nf = x.length / FRAME;
        boolean[] sp = new boolean[nf];
        Vad vad = new Vad();
        int count = 0;
        for (int i = 0; i < nf; i++) {
            sp[i] = vad.speech(x, i * FRAME);
            if (sp[i]) {
                count++;
            }
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < nf; i++) {
            if (sp[i]) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return new Result(new float[0], 0, 0);
        }
        int pad = 25;                                        // 500 ms either side: never cut a soft first or last word
        int a = Math.max(0, first - pad - 2) * FRAME;
        int b = Math.min(x.length, (last + pad + 1) * FRAME);
        float[] y = new float[b - a];
        System.arraycopy(x, a, y, 0, y.length);
        // gain: the loud end of the speech (95th percentile of |x| over speech frames) goes to 0.5
        float[] mags = new float[count * FRAME];
        int k = 0;
        for (int i = 0; i < nf; i++) {
            if (sp[i]) {
                for (int j = 0; j < FRAME; j++) {
                    mags[k++] = Math.abs(x[i * FRAME + j]);
                }
            }
        }
        java.util.Arrays.sort(mags);
        double p95 = mags[(int) (mags.length * 0.95)];
        double gain = Math.min(31.6, Math.max(0.5, 0.5 / Math.max(p95, 1e-5)));    // -6 .. +30 dB
        for (int i = 0; i < y.length; i++) {
            double v = y[i] * gain;
            y[i] = (float) (Math.abs(v) < 0.8 ? v : Math.signum(v) * (0.8 + 0.2 * Math.tanh((Math.abs(v) - 0.8) / 0.2)));   // soft limit
        }
        return new Result(y, count * 20, 20 * Math.log10(gain));
    }

    /** DC blocker + 2nd order Butterworth high-pass at 90 Hz. */
    public static float[] highPass(float[] in) {
        float[] out = new float[in.length];
        double w = 2 * Math.PI * 90.0 / RATE;
        double cs = Math.cos(w);
        double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
        double b0 = (1 + cs) / 2;
        double b1 = -(1 + cs);
        double b2 = (1 + cs) / 2;
        double a0 = 1 + alpha;
        double a1 = -2 * cs;
        double a2 = 1 - alpha;
        double x1 = 0;
        double x2 = 0;
        double y1 = 0;
        double y2 = 0;
        double mean = 0;
        for (float v : in) {
            mean += v;
        }
        mean /= Math.max(1, in.length);
        for (int i = 0; i < in.length; i++) {
            double x0 = in[i] - mean;
            double y0 = (b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
            out[i] = (float) y0;
        }
        return out;
    }

    /**
     * Short-time spectral attenuation (512-point frames, 50 % overlap, sqrt-Hann): the noise power of each frequency is the 20th
     * percentile of that frequency over the whole clip (a push-to-talk clip always has quiet moments); the gain per bin is a
     * smoothed Wiener-style curve with a floor of -14 dB, so the voice is never carved hollow.
     */
    public static float[] spectralDenoise(float[] x) {
        int n = 512;
        int hop = 256;
        int frames = (x.length - n) / hop + 1;
        int bins = n / 2 + 1;
        double[] win = new double[n];
        for (int i = 0; i < n; i++) {
            win[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));
        }
        double[][] re = new double[frames][];
        double[][] im = new double[frames][];
        double[][] pw = new double[bins][frames];
        for (int f = 0; f < frames; f++) {
            double[] r = new double[n];
            double[] m = new double[n];
            for (int i = 0; i < n; i++) {
                r[i] = x[f * hop + i] * win[i];
            }
            fft(r, m, false);
            re[f] = r;
            im[f] = m;
            for (int b = 0; b < bins; b++) {
                pw[b][f] = r[b] * r[b] + m[b] * m[b];
            }
        }
        double[] noise = new double[bins];
        for (int b = 0; b < bins; b++) {
            double[] s = pw[b].clone();
            java.util.Arrays.sort(s);
            noise[b] = s[(int) (s.length * 0.2)];
        }
        double floor = 0.2;                                  // -14 dB
        double over = 1.4;
        double[] prevG = new double[bins];
        java.util.Arrays.fill(prevG, 1.0);
        float[] out = new float[x.length];
        for (int f = 0; f < frames; f++) {
            double[] r = re[f];
            double[] m = im[f];
            for (int b = 0; b < bins; b++) {
                double snr = pw[b][f] / (over * noise[b] + 1e-12);
                double g = Math.max(floor, 1.0 - 1.0 / Math.max(snr, 1e-6));
                g = 0.6 * g + 0.4 * prevG[b];                // less "musical noise"
                prevG[b] = g;
                r[b] *= g;
                m[b] *= g;
                if (b > 0 && b < n / 2) {
                    r[n - b] = r[b];
                    m[n - b] = -m[b];
                }
            }
            fft(r, m, true);
            for (int i = 0; i < n; i++) {
                out[f * hop + i] += (float) (r[i] * win[i]);
            }
        }
        return out;
    }

    /** In-place radix-2 FFT (inverse scaled by 1/n). */
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j;
                    int b = a + len / 2;
                    double ur = re[a];
                    double ui = im[a];
                    double vr = re[b] * cr - im[b] * ci;
                    double vi = re[b] * ci + im[b] * cr;
                    re[a] = ur + vr;
                    im[a] = ui + vi;
                    re[b] = ur - vr;
                    im[b] = ui - vi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    /** Linear-interpolation resampler with a simple box pre-filter when going down (48 / 44.1 kHz mics -> 16 kHz). */
    public static float[] resample(float[] in, int fromRate) {
        if (fromRate == RATE) {
            return in;
        }
        double ratio = (double) fromRate / RATE;
        float[] src = in;
        if (ratio > 1.5) {
            int w = (int) Math.round(ratio);
            src = new float[in.length];
            double acc = 0;
            for (int i = 0; i < in.length; i++) {
                acc += in[i];
                if (i >= w) {
                    acc -= in[i - w];
                }
                src[i] = (float) (acc / Math.min(i + 1, w));
            }
        }
        int outLen = (int) (src.length / ratio);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double p = i * ratio;
            int a = (int) p;
            int b = Math.min(a + 1, src.length - 1);
            double t = p - a;
            out[i] = (float) (src[a] * (1 - t) + src[b] * t);
        }
        return out;
    }
}
