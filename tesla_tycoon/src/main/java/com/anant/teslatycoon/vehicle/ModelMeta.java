package com.anant.teslatycoon.vehicle;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** The small JSON sidecar written by tools/bake_models.py next to every .ttm (dimensions, wheels, anchors). Read on both sides. */
public final class ModelMeta {
    public static final class Wheel {
        public String name;
        public float[] pivot;
        public float radius;
        public float hw;
        public boolean front;
        public boolean left;
    }

    public String name;
    public String title;
    public float length;
    public float width;
    public float height;
    public float wheelbase;
    public float track;
    public float wheelRadius;
    public Wheel[] wheels;
    public float[] seat;
    public float seatHip;
    public float[] steer;
    public float[][] thrusters;
    public String texture;
    public int[] tris;

    public static ModelMeta load(String model) {
        String path = "/assets/tesla_tycoon/vehicles/" + model + ".meta.json";
        try (InputStream in = ModelMeta.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing model meta " + path);
            }
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ModelMeta.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
