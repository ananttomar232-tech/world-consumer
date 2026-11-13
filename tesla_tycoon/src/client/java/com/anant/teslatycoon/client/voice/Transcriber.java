package com.anant.teslatycoon.client.voice;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Speech to text, offline, with whisper.cpp (whisper-jni): nothing leaves the computer. Whisper copes with noisy, quiet and
 * badly-equalised laptop microphones far better than the older recognisers, and it understands Indian English, Hindi and Hinglish.
 * A short vocabulary prompt nudges it towards the words of this game (Gotiya, Cybertruck, petrol ...). Hindi in Devanagari is
 * written back in Roman letters, because that is how the chat (and Gotiya) reads Hinglish.
 */
public final class Transcriber implements AutoCloseable {
    /** What the game is about: whisper prefers these spellings when it is unsure. */
    public static final String VOCABULARY = "Seth Gotiya, Tesla, Cybertruck, Cybercab, Roadster, Model 3, Lamborghini, Mustang, petrol, diesel, "
            + "spikes, blackout, roadblock, showroom, Starlink, billboard, EMP, namaste, bhai, kaise ho, sorry, partner, help.";

    private static boolean loaded;
    private final WhisperJNI whisper;
    private final WhisperContext ctx;
    private final int threads;
    private final boolean fast;

    public Transcriber(Path model, int threads) throws java.io.IOException {
        this(model, threads, false);
    }

    public Transcriber(Path model, int threads, boolean fast) throws java.io.IOException {
        synchronized (Transcriber.class) {
            if (!loaded) {
                WhisperJNI.loadLibrary();
                WhisperJNI.setLibraryLogger(msg -> { });   // whisper.cpp is chatty: keep the game log clean
                loaded = true;
            }
        }
        this.whisper = new WhisperJNI();
        this.ctx = whisper.init(model);
        if (ctx == null) {
            throw new java.io.IOException("cannot load the speech model " + model);
        }
        this.threads = threads;
        this.fast = fast;
    }

    /**
     * @param audio    16 kHz mono, already cleaned ({@link VoiceDsp#clean})
     * @param language "auto", "en" or "hi"
     * @return the text, or "" if nothing real was said
     */
    public synchronized String transcribe(float[] audio, String language) {
        if (audio.length < VoiceDsp.RATE / 4) {
            return "";
        }
        // whisper wants at least ~1 s: pad short words with silence
        float[] pcm = audio;
        if (pcm.length < VoiceDsp.RATE * 3 / 2) {
            pcm = java.util.Arrays.copyOf(audio, VoiceDsp.RATE * 3 / 2);
        }
        String text = run(pcm, language);
        if ("auto".equals(language) && foreignScript(text)) {
            text = run(pcm, "en");                           // a wrong guess (Urdu, Arabic, ...): English is what the chat needs
        }
        return finish(text);
    }

    private String run(float[] pcm, String language) {
        WhisperFullParams p = new WhisperFullParams(fast ? WhisperSamplingStrategy.GREEDY : WhisperSamplingStrategy.BEAN_SEARCH);
        if (fast) {
            p.greedyBestOf = 1;
        } else {
            p.beamSearchBeamSize = 4;
        }
        p.nThreads = threads;
        p.language = language;
        p.detectLanguage = false;
        p.translate = false;
        p.noContext = true;
        p.singleSegment = false;
        p.noTimestamps = true;
        p.printProgress = false;
        p.printRealtime = false;
        p.printSpecial = false;
        p.printTimestamps = false;
        p.suppressBlank = true;
        p.suppressNonSpeechTokens = true;
        p.temperature = 0f;
        p.temperatureInc = 0.2f;
        p.noSpeechThold = 0.6f;
        p.initialPrompt = VOCABULARY;
        // the encoder normally always chews a 30 s window; a push-to-talk sentence is a few seconds: fit the window to the clip
        // (+2 s of slack) - several times faster, the text stays the same
        if (!"0".equals(System.getProperty("tesla_tycoon.audioctx"))) {
            p.audioCtx = Math.min(1500, (int) Math.ceil((pcm.length / (double) VoiceDsp.RATE + 2.0) * 50));
        }
        int r = whisper.full(ctx, p, pcm, pcm.length);
        if (r != 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = whisper.fullNSegments(ctx);
        for (int i = 0; i < n; i++) {
            sb.append(whisper.fullGetSegmentText(ctx, i));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------------------------------------------ text clean-up
    // what whisper is known to "hear" in silence or noise (from its training subtitles): never sent to chat
    private static final Pattern HALLUCINATION = Pattern.compile(
            "^(\\W*|(thank you|thanks)( (so much|very much))?( for watching)?|thanks for watching|please subscribe.*|subscribe.*|you|bye|"
                    + "\\[.*\\]|\\(.*\\)|music|applause|silence|blank_audio|sous-titrage.*|subtitles by.*|amara\\.org.*|"
                    + "\\*.*\\*|okay\\.?|so\\.?|हाँ|धन्यवाद)\\W*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static String finish(String raw) {
        String t = raw.replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", " ").replace(VOCABULARY, " ").replaceAll("\\s+", " ").trim();
        if (t.matches(".*[\\u0900-\\u097F].*")) {
            t = romanize(t);
        }
        t = dedupe(t.replaceAll("\\s+", " ").trim());
        if (t.isEmpty() || HALLUCINATION.matcher(t).matches()) {
            return "";
        }
        // the same word five times over is a recogniser loop, not a sentence
        if (t.matches("(?i)^(\\b\\w+\\b)(\\W+\\1\\b){4,}.*") || t.matches("(?i).*(\\w{1,3})\\1{5,}.*")) {
            return "";
        }
        return t.length() > 240 ? t.substring(0, 240) : t;
    }

    /** Whisper sometimes says a short sentence again and again ("Show me ... cost? Show me ... cost? Show me ..."): keep it once. */
    static String dedupe(String t) {
        String[] parts = t.split("(?<=[.?!])\\s+");
        java.util.List<String> keep = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String firstKey = null;
        for (String part : parts) {
            String key = part.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").trim();
            if (key.isEmpty()) {
                continue;
            }
            if (firstKey == null) {
                firstKey = key;
            }
            if (seen.contains(key) || (keep.size() > 0 && firstKey.startsWith(key) && key.length() >= 6)) {
                continue;                                        // a repeat, or the cut-off start of another repeat
            }
            seen.add(key);
            keep.add(part);
        }
        return String.join(" ", keep);
    }

    static boolean foreignScript(String t) {
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            Character.UnicodeScript s = Character.UnicodeScript.of(ch);
            if (Character.isLetter(ch) && s != Character.UnicodeScript.LATIN && s != Character.UnicodeScript.DEVANAGARI) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------ Devanagari -> Roman
    private static final String[][] CONS = {
            {"क", "k"}, {"ख", "kh"}, {"ग", "g"}, {"घ", "gh"}, {"ङ", "n"}, {"च", "ch"}, {"छ", "chh"}, {"ज", "j"}, {"झ", "jh"}, {"ञ", "n"},
            {"ट", "t"}, {"ठ", "th"}, {"ड", "d"}, {"ढ", "dh"}, {"ण", "n"}, {"त", "t"}, {"थ", "th"}, {"द", "d"}, {"ध", "dh"}, {"न", "n"},
            {"प", "p"}, {"फ", "ph"}, {"ब", "b"}, {"भ", "bh"}, {"म", "m"}, {"य", "y"}, {"र", "r"}, {"ल", "l"}, {"व", "v"}, {"श", "sh"},
            {"ष", "sh"}, {"स", "s"}, {"ह", "h"}, {"क़", "q"}, {"ख़", "kh"}, {"ग़", "g"}, {"ज़", "z"}, {"ड़", "d"}, {"ढ़", "rh"}, {"फ़", "f"}};
    private static final String[][] VOW = {{"अ", "a"}, {"आ", "aa"}, {"इ", "i"}, {"ई", "ee"}, {"उ", "u"}, {"ऊ", "oo"}, {"ऋ", "ri"}, {"ए", "e"},
            {"ऐ", "ai"}, {"ओ", "o"}, {"औ", "au"}, {"ऑ", "o"}};
    private static final String[][] SIGN = {{"ा", "a"}, {"ि", "i"}, {"ी", "i"}, {"ु", "u"}, {"ू", "u"}, {"ृ", "ri"}, {"े", "e"}, {"ै", "ai"},
            {"ो", "o"}, {"ौ", "au"}, {"ॉ", "o"}};

    /** Hindi in Devanagari -> how it is typed in Hinglish chat ("कैसे हो" -> "kaise ho"). The final inherent 'a' of a word is dropped. */
    public static String romanize(String s) {
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            String two = i + 1 < s.length() ? s.substring(i, i + 2) : null;
            String one = s.substring(i, i + 1);
            String cons = null;
            int used = 1;
            if (two != null && two.charAt(1) == '़') {            // consonant + nukta
                cons = lookup(CONS, two);
                used = 2;
            }
            if (cons == null) {
                cons = lookup(CONS, one);
                used = 1;
            }
            if (cons != null) {
                out.append(cons);
                i += used;
                String next = i < s.length() ? s.substring(i, i + 1) : "";
                String sign = lookup(SIGN, next);
                if (sign != null) {
                    out.append(sign);
                    i++;
                } else if (next.equals("्")) {                    // halant: no vowel
                    i++;
                } else {
                    boolean wordEnd = i >= s.length() || !Character.isLetter(s.charAt(i)) && !isMark(s.charAt(i));
                    if (!wordEnd && !next.equals("ं") && !next.equals("ँ")) {
                        out.append('a');
                    } else if (!wordEnd) {
                        out.append('a');
                    }
                }
                continue;
            }
            String vow = lookup(VOW, one);
            if (vow != null) {
                out.append(vow);
            } else if (one.equals("ं") || one.equals("ँ")) {  // anusvara / chandrabindu
                out.append('n');
            } else if (one.equals("ः")) {
                out.append('h');
            } else if (one.equals("।") || one.equals("॥")) {  // danda
                out.append('.');
            } else if (one.charAt(0) >= '०' && one.charAt(0) <= '९') {
                out.append((char) ('0' + one.charAt(0) - '०'));
            } else if (!isMark(one.charAt(0))) {
                out.append(one);
            }
            i++;
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isMark(char c) {
        return c >= 'ऀ' && c <= 'ॿ' && Character.getType(c) != Character.OTHER_LETTER;
    }

    private static String lookup(String[][] table, String key) {
        for (String[] e : table) {
            if (e[0].equals(key)) {
                return e[1];
            }
        }
        return null;
    }

    @Override
    public void close() {
        whisper.free(ctx);
    }
}
