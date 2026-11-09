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

    /** Wheel ring in the XY plane (axis = Z), radius 0.2, with hub and three spokes. */
    public static VertexBuffer steeringWheel() {
        if (wheel == null) {
            B b = new B();
            int n = 24;
            int s = 6;
            float big = 0.2f;
            float t = 0.022f;
            for (int i = 0; i < n; i++) {
                double a0 = Math.PI * 2 * i / n;
                double a1 = Math.PI * 2 * (i + 1) / n;
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
                        q[k] = new float[]{(float) Math.cos(a) * big + rx * t, (float) Math.sin(a) * big + ry * t, rz * t};
                        nn[k] = new float[]{rx, ry, rz};
                    }
                    b.v(q[0], nn[0], 0.06f, 0.06f, 0.07f, 1f, 90, 130, 0);
                    b.v(q[1], nn[1], 0.06f, 0.06f, 0.07f, 1f, 90, 130, 0);
                    b.v(q[2], nn[2], 0.06f, 0.06f, 0.07f, 1f, 90, 130, 0);
                    b.v(q[0], nn[0], 0.06f, 0.06f, 0.07f, 1f, 90, 130, 0);
                    b.v(q[2], nn[2], 0.06f, 0.06f, 0.07f, 1f, 90, 130, 0);
                    b.v(q[3], nn[3], 0.06f, 0.06f, 0.07f, 1f, 90, 130, 0);
                }
            }
            b.box(-0.055f, -0.055f, -0.03f, 0.055f, 0.055f, 0.03f, 0.09f, 0.09f, 0.1f, 120, 110);
            b.box(-big, -0.014f, -0.012f, -0.05f, 0.014f, 0.012f, 0.06f, 0.06f, 0.07f, 90, 130);
            b.box(0.05f, -0.014f, -0.012f, big, 0.014f, 0.012f, 0.06f, 0.06f, 0.07f, 90, 130);
            b.box(-0.014f, -big, -0.012f, 0.014f, -0.05f, 0.012f, 0.06f, 0.06f, 0.07f, 90, 130);
            // brushed-metal logo plate on both faces of the hub, and the two thumb scroll wheels on the spokes
            b.box(-0.032f, -0.024f, -0.036f, 0.032f, 0.03f, -0.031f, 0.72f, 0.73f, 0.78f, 235, 55);
            b.box(-0.032f, -0.024f, 0.031f, 0.032f, 0.03f, 0.036f, 0.72f, 0.73f, 0.78f, 235, 55);
            b.box(-0.15f, 0.014f, -0.016f, -0.1f, 0.03f, 0.016f, 0.45f, 0.46f, 0.5f, 200, 70);
            b.box(0.1f, 0.014f, -0.016f, 0.15f, 0.03f, 0.016f, 0.45f, 0.46f, 0.5f, 200, 70);
            wheel = b.build();
        }
        return wheel;
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
        for (VertexBuffer v : new VertexBuffer[]{wheel, nozzle, flame}) {
            if (v != null) {
                v.close();
            }
        }
        wheel = null;
        nozzle = null;
        flame = null;
    }
}
