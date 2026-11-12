package com.anant.teslatycoon.client.render;

import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

/** The few parts that are generated rather than modelled: steering wheel, rocket nozzle, flame cone. Built lazily, freed on reload. */
public final class ProcMesh {
    private static VertexBuffer wheel;
    private static VertexBuffer nozzle;
    private static VertexBuffer flame;
    private static final java.util.Map<Integer, VertexBuffer> QUADS = new java.util.HashMap<>();

    private ProcMesh() {}

    private static final class B {
        final BufferBuilder bb = new BufferBuilder(1 << 15);

        B() {
            bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
        }

        void v(float[] p, float[] n, float r, float g, float b, float a, int metal, int rough, int emis) {
            bb.vertex(p[0], p[1], p[2], r, g, b, a, 0f, 0f, metal | (rough << 16), emis | (1 << 16), n[0], n[1], n[2]);
        }

        void tri(float[] a, float[] b, float[] c, float[] n, float r, float g, float bl, float al, int metal, int rough, int emis) {
            v(a, n, r, g, bl, al, metal, rough, emis);
            v(b, n, r, g, bl, al, metal, rough, emis);
            v(c, n, r, g, bl, al, metal, rough, emis);
        }

        void box(float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float bl, int metal, int rough) {
            float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            int[][] f = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 4, 7, 3}, {1, 2, 6, 5}, {0, 1, 5, 4}, {3, 7, 6, 2}};
            float[][] n = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}};
            for (int i = 0; i < 6; i++) {
                tri(c[f[i][0]], c[f[i][1]], c[f[i][2]], n[i], r, g, bl, 1f, metal, rough, 0);
                tri(c[f[i][0]], c[f[i][2]], c[f[i][3]], n[i], r, g, bl, 1f, metal, rough, 0);
            }
        }

        VertexBuffer build() {
            VertexBuffer buf = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buf.bind();
            buf.upload(bb.end());
            VertexBuffer.unbind();
            return buf;
        }
    }

    private static final java.util.Map<Integer, VertexBuffer> WHEELS = new java.util.HashMap<>();

    public static VertexBuffer steeringWheel() {
        return steeringWheel(0);
    }

    /**
     * Wheel ring in the XY plane (axis = Z), radius 0.2, with hub and spokes. style 0 = Tesla (black leather, metal logo plate),
     * 1 = classic (wooden rim, chrome spokes), 2 = muscle car (leather rim, three aluminium spokes, red hub),
     * 3 = supercar (flat-bottomed alcantara rim with the yellow 12 o'clock stripe, carbon spokes).
     */
    public static VertexBuffer steeringWheel(int style) {
        VertexBuffer cached = WHEELS.get(style);
        if (cached != null) {
            return cached;
        }
        B b = new B();
        int n = 28;
        int s = 6;
        float big = 0.2f;
        float t = style == 1 ? 0.018f : (style == 4 ? 0.034f : 0.022f);
        float[] rim = switch (style) {
            case 1 -> new float[]{0.42f, 0.22f, 0.09f};
            case 3 -> new float[]{0.09f, 0.09f, 0.1f};
            case 4 -> new float[]{0.86f, 0.08f, 0.12f};          // the fluffy red steering cover of a proper jugaad
            case 5 -> new float[]{0.08f, 0.08f, 0.09f};
            default -> new float[]{0.06f, 0.06f, 0.07f};
        };
        int rimMetal = style == 1 ? 40 : (style == 4 ? 0 : 90);
        int rimRough = style == 1 ? 60 : (style == 4 ? 255 : 130);
        float flat = style == 3 ? -0.72f * big : -10f;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n;
            double a1 = Math.PI * 2 * (i + 1) / n;
            if (style == 6 && Math.sin((a0 + a1) / 2) > 0.42) {
                continue;                                          // the Roadster's butterfly yoke: open at the top
            }
            boolean marker = style == 3 && Math.abs(Math.sin((a0 + a1) / 2) - 1) < 0.03;
            float cr = marker ? 1.0f : rim[0];
            float cg = marker ? 0.78f : rim[1];
            float cb = marker ? 0.05f : rim[2];
            for (int j = 0; j < s; j++) {
                double p0 = Math.PI * 2 * j / s;
                double p1 = Math.PI * 2 * (j + 1) / s;
                float[][] q = new float[4][];
                float[][] nn = new float[4][];
                double[][] ap = {{a0, p0}, {a0, p1}, {a1, p1}, {a1, p0}};
                for (int k = 0; k < 4; k++) {
                    double a = ap[k][0];
                    double p = ap[k][1];
                    float rx = (float) (Math.cos(a) * Math.cos(p));
                    float ry = (float) (Math.sin(a) * Math.cos(p));
                    float rz = (float) Math.sin(p);
                    float[] c2 = rimPoint(style, a, big, flat);
                    q[k] = new float[]{c2[0] + rx * t, c2[1] + ry * t, rz * t};
                    nn[k] = new float[]{rx, ry, rz};
                }
                b.v(q[0], nn[0], cr, cg, cb, 1f, rimMetal, rimRough, 0);
                b.v(q[1], nn[1], cr, cg, cb, 1f, rimMetal, rimRough, 0);
                b.v(q[2], nn[2], cr, cg, cb, 1f, rimMetal, rimRough, 0);
                b.v(q[0], nn[0], cr, cg, cb, 1f, rimMetal, rimRough, 0);
                b.v(q[2], nn[2], cr, cg, cb, 1f, rimMetal, rimRough, 0);
                b.v(q[3], nn[3], cr, cg, cb, 1f, rimMetal, rimRough, 0);
            }
        }
        float[] spoke = switch (style) {
            case 1 -> new float[]{0.78f, 0.79f, 0.82f};
            case 2 -> new float[]{0.62f, 0.64f, 0.68f};
            case 3 -> new float[]{0.05f, 0.05f, 0.06f};
            case 5 -> new float[]{0.7f, 0.71f, 0.74f};             // stainless, like the truck
            default -> new float[]{0.06f, 0.06f, 0.07f};
        };
        int sm = style == 1 || style == 2 || style == 5 ? 230 : 90;
        int sr = style == 1 || style == 2 || style == 5 ? 50 : 130;
        float hub = style == 3 ? 0.065f : 0.055f;
        float[] hubc = switch (style) {
            case 2 -> new float[]{0.55f, 0.04f, 0.05f};
            case 1 -> new float[]{0.12f, 0.1f, 0.09f};
            default -> new float[]{0.09f, 0.09f, 0.1f};
        };
        b.box(-hub, -hub, -0.03f, hub, hub, 0.03f, hubc[0], hubc[1], hubc[2], 120, 110);
        float side = style == 6 ? big * 1.15f : big;
        b.box(-side, -0.014f, -0.012f, -0.05f, 0.014f, 0.012f, spoke[0], spoke[1], spoke[2], sm, sr);
        b.box(0.05f, -0.014f, -0.012f, side, 0.014f, 0.012f, spoke[0], spoke[1], spoke[2], sm, sr);
        float bottom = style == 3 ? 0.72f * big : (style == 5 ? 0.76f * big : (style == 6 ? 0.55f * big : big));
        b.box(-0.014f, -bottom, -0.012f, 0.014f, -0.05f, 0.012f, spoke[0], spoke[1], spoke[2], sm, sr);
        if (style == 0) {
            // brushed-metal logo plate on both faces of the hub, and the two thumb scroll wheels on the spokes
            b.box(-0.032f, -0.024f, -0.036f, 0.032f, 0.03f, -0.031f, 0.72f, 0.73f, 0.78f, 235, 55);
            b.box(-0.032f, -0.024f, 0.031f, 0.032f, 0.03f, 0.036f, 0.72f, 0.73f, 0.78f, 235, 55);
            b.box(-0.15f, 0.014f, -0.016f, -0.1f, 0.03f, 0.016f, 0.45f, 0.46f, 0.5f, 200, 70);
            b.box(0.1f, 0.014f, -0.016f, 0.15f, 0.03f, 0.016f, 0.45f, 0.46f, 0.5f, 200, 70);
        } else if (style == 4) {
            b.box(-0.03f, -0.03f, -0.036f, 0.03f, 0.03f, -0.031f, 0.95f, 0.72f, 0.1f, 220, 60);        // a little golden god on the horn
        } else if (style == 5) {
            b.box(-0.04f, -0.02f, -0.036f, 0.04f, 0.02f, -0.031f, 0.85f, 0.86f, 0.9f, 240, 40);
        } else if (style == 6) {
            b.box(-0.035f, -0.02f, -0.036f, 0.035f, 0.02f, -0.031f, 0.85f, 0.08f, 0.1f, 120, 60);
        } else if (style == 1) {
            b.box(-0.03f, -0.03f, -0.036f, 0.03f, 0.03f, -0.031f, 0.85f, 0.85f, 0.88f, 240, 40);       // chrome horn push
        } else if (style == 2) {
            b.box(-0.035f, -0.02f, -0.036f, 0.035f, 0.02f, -0.031f, 0.8f, 0.8f, 0.82f, 240, 40);       // the running-horse badge plate
        } else {
            b.box(-0.03f, -0.03f, -0.036f, 0.03f, 0.03f, -0.031f, 0.95f, 0.75f, 0.05f, 200, 60);       // gold shield badge
            b.box(-0.17f, -0.02f, -0.02f, -0.13f, 0.02f, 0.0f, 0.75f, 0.05f, 0.05f, 60, 90);           // red drive-mode rotary
        }
        VertexBuffer built = b.build();
        WHEELS.put(style, built);
        if (style == 0) {
            wheel = built;
        }
        return built;
    }

    /** The centre line of the rim at angle a (0 = right, pi/2 = top) for every wheel style. */
    private static float[] rimPoint(int style, double a, float big, float flat) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        if (style == 5) {                                           // Cybertruck: a squircle yoke
            double e = 0.35;
            return new float[]{(float) (Math.signum(c) * Math.pow(Math.abs(c), e) * big * 1.1), (float) (Math.signum(s) * Math.pow(Math.abs(s), e) * big * 0.76)};
        }
        if (style == 6) {                                           // Roadster: butterfly yoke, wide and flat
            return new float[]{(float) (c * big * 1.15), (float) Math.max(s * big * 0.75, -0.55 * big)};
        }
        return new float[]{(float) c * big, Math.max((float) s * big, flat)};
    }

    /** A rocket bell opening towards -Z: dark metal skirt and a glowing core disc. Origin = the throat. */
    public static VertexBuffer nozzle() {
        if (nozzle == null) {
            B b = new B();
            int n = 16;
            float r0 = 0.17f;
            float r1 = 0.29f;
            float len = 0.55f;
            for (int i = 0; i < n; i++) {
                double a0 = Math.PI * 2 * i / n;
                double a1 = Math.PI * 2 * (i + 1) / n;
                float[] p00 = {(float) Math.cos(a0) * r0, (float) Math.sin(a0) * r0, 0};
                float[] p10 = {(float) Math.cos(a1) * r0, (float) Math.sin(a1) * r0, 0};
                float[] p01 = {(float) Math.cos(a0) * r1, (float) Math.sin(a0) * r1, -len};
                float[] p11 = {(float) Math.cos(a1) * r1, (float) Math.sin(a1) * r1, -len};
                float[] nm = {(float) Math.cos((a0 + a1) / 2), (float) Math.sin((a0 + a1) / 2), -0.35f};
                b.tri(p00, p10, p11, nm, 0.11f, 0.12f, 0.14f, 1f, 230, 80, 0);
                b.tri(p00, p11, p01, nm, 0.11f, 0.12f, 0.14f, 1f, 230, 80, 0);
                float[] c = {0, 0, -len * 0.92f};
                float[] q0 = {(float) Math.cos(a0) * r1 * 0.78f, (float) Math.sin(a0) * r1 * 0.78f, -len * 0.92f};
                float[] q1 = {(float) Math.cos(a1) * r1 * 0.78f, (float) Math.sin(a1) * r1 * 0.78f, -len * 0.92f};
                b.tri(c, q1, q0, new float[]{0, 0, -1}, 0.55f, 0.85f, 1f, 1f, 0, 0, 255);
            }
            nozzle = b.build();
        }
        return nozzle;
    }

    /** Unit flame cone: base at z=0 (radius 0.22), tip at z=-1, colour and alpha fade out along the length. */
    public static VertexBuffer flame() {
        if (flame == null) {
            B b = new B();
            int n = 14;
            float r = 0.22f;
            for (int i = 0; i < n; i++) {
                double a0 = Math.PI * 2 * i / n;
                double a1 = Math.PI * 2 * (i + 1) / n;
                float[] p0 = {(float) Math.cos(a0) * r, (float) Math.sin(a0) * r, 0};
                float[] p1 = {(float) Math.cos(a1) * r, (float) Math.sin(a1) * r, 0};
                float[] tip = {0, 0, -1};
                float[] nm = {(float) Math.cos((a0 + a1) / 2), (float) Math.sin((a0 + a1) / 2), 0.2f};
                b.v(p0, nm, 0.85f, 0.95f, 1f, 0.95f, 0, 0, 255);
                b.v(p1, nm, 0.85f, 0.95f, 1f, 0.95f, 0, 0, 255);
                b.v(tip, nm, 0.2f, 0.5f, 1f, 0f, 0, 0, 255);
            }
            flame = b.build();
        }
        return flame;
    }

    /** A unit quad in the XY plane facing +Z (x -0.5..0.5, y -0.5..0.5), image uv 0..1 (v down). Emissive 0..255. */
    public static VertexBuffer quad(int emissive) {
        return QUADS.computeIfAbsent(emissive, em -> {
            B b = new B();
            float[] n = {0, 0, 1};
            float[][] p = {{-0.5f, 0.5f, 0}, {0.5f, 0.5f, 0}, {0.5f, -0.5f, 0}, {-0.5f, -0.5f, 0}};
            float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            int[] order = {0, 3, 2, 0, 2, 1};
            for (int i : order) {
                b.bb.vertex(p[i][0], p[i][1], p[i][2], 1f, 1f, 1f, 1f, uv[i][0], uv[i][1], 0 | (255 << 16), em, n[0], n[1], n[2]);
            }
            return b.build();
        });
    }

    public static void clear() {
        for (VertexBuffer v : QUADS.values()) {
            v.close();
        }
        QUADS.clear();
        for (VertexBuffer v : WHEELS.values()) {
            v.close();
        }
        WHEELS.clear();
        for (VertexBuffer v : new VertexBuffer[]{nozzle, flame}) {
            if (v != null) {
                v.close();
            }
        }
        wheel = null;
        nozzle = null;
        flame = null;
    }
}
