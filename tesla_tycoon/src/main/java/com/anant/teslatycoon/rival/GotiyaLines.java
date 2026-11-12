package com.anant.teslatycoon.rival;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The voiced line library written by tools/gotiya_lines.py (assets/tesla_tycoon/gotiya/lines.json): every line has an id, a key
 * (what it is about), the chat text and a pre-recorded voice clip (sound event tesla_tycoon:voice.&lt;id&gt;).
 * pick() never repeats one of the last few lines of the same key.
 */
public final class GotiyaLines {
    public record Line(String id, String key, String text, String voice) {
        public boolean customer() {
            return "f".equals(voice);
        }
    }

    private static final Map<String, List<Line>> BY_KEY = new HashMap<>();
    private static final Map<String, Deque<String>> RECENT = new HashMap<>();
    private static final Random RNG = new Random();

    static {
        try (InputStream in = GotiyaLines.class.getResourceAsStream("/assets/tesla_tycoon/gotiya/lines.json")) {
            if (in != null) {
                List<Line> all = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Line>>() {}.getType());
                for (Line l : all) {
                    BY_KEY.computeIfAbsent(l.key(), k -> new ArrayList<>()).add(l);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read Gotiya's lines", e);
        }
    }

    private GotiyaLines() {}

    /** Every line id (one voice clip each). */
    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (List<Line> l : BY_KEY.values()) {
            for (Line x : l) {
                out.add(x.id());
            }
        }
        return out;
    }

    public static boolean has(String key) {
        return BY_KEY.containsKey(key);
    }

    public static int count(String key) {
        List<Line> l = BY_KEY.get(key);
        return l == null ? 0 : l.size();
    }

    /** A line of this key that was not used recently (falls back to "fallback" for an unknown key). */
    public static Line pick(String key) {
        List<Line> pool = BY_KEY.get(key);
        if (pool == null || pool.isEmpty()) {
            pool = BY_KEY.get("fallback");
        }
        Deque<String> recent = RECENT.computeIfAbsent(key, k -> new ArrayDeque<>());
        List<Line> fresh = new ArrayList<>();
        for (Line l : pool) {
            if (!recent.contains(l.id())) {
                fresh.add(l);
            }
        }
        if (fresh.isEmpty()) {
            recent.clear();
            fresh = pool;
        }
        Line l = fresh.get(RNG.nextInt(fresh.size()));
        recent.addLast(l.id());
        while (recent.size() > Math.max(1, pool.size() - 1)) {
            recent.removeFirst();
        }
        return l;
    }
}
