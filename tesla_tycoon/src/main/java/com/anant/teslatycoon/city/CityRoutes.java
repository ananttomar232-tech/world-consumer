package com.anant.teslatycoon.city;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drive-by-numbers: the closed loops the robotaxis follow, the test-drive loop of the customers, and the stop position of every
 * robotaxi station. All coordinates are city-local (x, z); traffic keeps to the right, cars use the curb lane next to the
 * stations. The loops are clockwise (every corner is a right turn).
 */
public final class CityRoutes {
    private CityRoutes() {}

    /** One closed loop of waypoints. */
    public static final class Route {
        public final List<double[]> pts = new ArrayList<>();
        public final Set<Integer> corners = new HashSet<>();
        /** index into {@link #pts} -> station id (an index into Buildings.STATIONS) */
        public final java.util.Map<Integer, Integer> stops = new java.util.LinkedHashMap<>();
        public double[] cum;
        public double length;

        void finish() {
            cum = new double[pts.size()];
            double s = 0;
            for (int i = 0; i < pts.size(); i++) {
                cum[i] = s;
                double[] a = pts.get(i);
                double[] b = pts.get((i + 1) % pts.size());
                s += Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            length = s;
        }

        /** The point at distance s along the loop (wraps), as {x, z, index of the next waypoint}. */
        public double[] at(double s) {
            s = ((s % length) + length) % length;
            int lo = 0;
            for (int i = 0; i < cum.length; i++) {
                if (cum[i] <= s) {
                    lo = i;
                }
            }
            double[] a = pts.get(lo);
            double[] b = pts.get((lo + 1) % pts.size());
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double t = len < 1e-6 ? 0 : (s - cum[lo]) / len;
            return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, (lo + 1) % pts.size()};
        }

        /** The distance along the loop of waypoint i. */
        public double sOf(int i) {
            return cum[Math.floorMod(i, cum.length)];
        }
    }

    /** Where a cab stops for each station of {@link Buildings#STATIONS} (the middle of the curb lane in front of the bay). */
    public static final double[][] STOP = {
            {-10, -158.5}, {147.5, -20}, {-10, 141.5}, {-172.5, -65},
            {-20, -86.5}, {53.5, -20}, {-76.5, -20}, {30, 71.5},
            {8, 2.0}, {53.5, 42}
    };

    /** Walks the corners of a clockwise loop; stations that lie on an edge become stop waypoints, the rest of the edge gets points every ~22 blocks. */
    private static Route loop(double[][] corners, int[] stations) {
        Route r = new Route();
        for (int i = 0; i < corners.length; i++) {
            double[] a = corners[i];
            double[] b = corners[(i + 1) % corners.length];
            r.corners.add(r.pts.size());
            r.pts.add(a.clone());
            boolean alongX = Math.abs(b[1] - a[1]) < 1e-6;
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double dx = (b[0] - a[0]) / len;
            double dz = (b[1] - a[1]) / len;
            // stations on this edge, nearest first
            List<double[]> here = new ArrayList<>();
            for (int st : stations) {
                double[] p = STOP[st];
                double along = (p[0] - a[0]) * dx + (p[1] - a[1]) * dz;
                double off = alongX ? Math.abs(p[1] - a[1]) : Math.abs(p[0] - a[0]);
                if (off < 0.6 && along > 8 && along < len - 8) {
                    here.add(new double[]{along, st});
                }
            }
            here.sort((p, q) -> Double.compare(p[0], q[0]));
            double cursor = 0;
            for (double[] h : here) {
                for (double s = cursor + 22; s < h[0] - 12; s += 22) {
                    r.pts.add(new double[]{a[0] + dx * s, a[1] + dz * s});
                }
                r.stops.put(r.pts.size(), (int) h[1]);
                r.pts.add(new double[]{a[0] + dx * h[0], a[1] + dz * h[0]});
                cursor = h[0];
            }
            for (double s = cursor + 22; s < len - 10; s += 22) {
                r.pts.add(new double[]{a[0] + dx * s, a[1] + dz * s});
            }
        }
        r.finish();
        return r;
    }

    /** The highway ring (clockwise): north side eastbound, east side southbound, south side westbound, west side northbound. */
    public static Route outer() {
        return loop(new double[][]{{-172.5, -158.5}, {147.5, -158.5}, {147.5, 141.5}, {-172.5, 141.5}}, new int[]{0, 1, 2, 3});
    }

    /** The inner ring around the district: H1, V2, H4, V1. */
    public static Route inner() {
        return loop(new double[][]{{-76.5, -86.5}, {53.5, -86.5}, {53.5, 71.5}, {-76.5, 71.5}}, new int[]{4, 5, 9, 7, 6});
    }

    /** The core loop: MAIN westbound, V1 northbound, the alley eastbound, V2 southbound. */
    public static Route core() {
        return loop(new double[][]{{53.5, 2.0}, {-76.5, 2.0}, {-76.5, -39.5}, {53.5, -39.5}}, new int[]{8, 6, 5});
    }

    /**
     * The customers' test drive: out of the bay, west along the boulevard, round the block (V1, alley, V2) and back along the
     * boulevard into the bay. Returns waypoints {x, z}; corner flags in {@code cornersOut}.
     */
    public static List<double[]> testDrive(Set<Integer> cornersOut) {
        List<double[]> p = new ArrayList<>();
        p.add(new double[]{-32.5, -14});
        p.add(new double[]{-32.5, -4});
        cornersOut.add(p.size());
        p.add(new double[]{-32.5, 2.0});
        p.add(new double[]{-55, 2.0});
        cornersOut.add(p.size());
        p.add(new double[]{-76.5, 2.0});
        p.add(new double[]{-76.5, -18});
        cornersOut.add(p.size());
        p.add(new double[]{-76.5, -39.5});
        p.add(new double[]{-40, -39.5});
        p.add(new double[]{0, -39.5});
        p.add(new double[]{30, -39.5});
        cornersOut.add(p.size());
        p.add(new double[]{53.5, -39.5});
        p.add(new double[]{53.5, -18});
        cornersOut.add(p.size());
        p.add(new double[]{53.5, 2.0});
        p.add(new double[]{25, 2.0});
        p.add(new double[]{-5, 2.0});
        p.add(new double[]{-22, 2.0});
        cornersOut.add(p.size());
        p.add(new double[]{-32.5, 2.0});
        p.add(new double[]{-32.5, -6});
        p.add(new double[]{-32.5, -16});
        p.add(new double[]{-32.5, -24});
        return p;
    }
}
