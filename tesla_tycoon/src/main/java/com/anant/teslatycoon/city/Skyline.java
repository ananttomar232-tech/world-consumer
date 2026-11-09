package com.anant.teslatycoon.city;

import static com.anant.teslatycoon.city.CityLayout.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * The metropolis around the district: a hundred and more skyscrapers and modern buildings in the blocks between the avenues, the
 * tallest in the outer rows so that the horizon is a wall of glowing towers. Every tower is a single procedural {@link Plan.Shape}
 * (a hollow shell: pilasters, window bands, a lit-window pattern, a stepped crown and an antenna), so the plan stays small; the
 * windows are static light blocks (sea lanterns, shroomlights, froglights) that no blackout switches off.
 */
public final class Skyline {
    private Skyline() {}

    private static final BlockState[] GLOW = {
            Blocks.SEA_LANTERN.getDefaultState(), Blocks.SHROOMLIGHT.getDefaultState(), Blocks.PEARLESCENT_FROGLIGHT.getDefaultState(),
            Blocks.VERDANT_FROGLIGHT.getDefaultState(), Blocks.OCHRE_FROGLIGHT.getDefaultState(), Blocks.SEA_LANTERN.getDefaultState()};
    /** wall, pilaster, band */
    private static final BlockState[][] WALLS = {
            {Blocks.GRAY_CONCRETE.getDefaultState(), Blocks.BLACK_CONCRETE.getDefaultState(), Blocks.LIGHT_GRAY_CONCRETE.getDefaultState()},
            {Blocks.BLACK_CONCRETE.getDefaultState(), Blocks.POLISHED_BLACKSTONE.getDefaultState(), Blocks.GRAY_CONCRETE.getDefaultState()},
            {Blocks.LIGHT_GRAY_CONCRETE.getDefaultState(), Blocks.GRAY_CONCRETE.getDefaultState(), Blocks.WHITE_CONCRETE.getDefaultState()},
            {Blocks.BLUE_TERRACOTTA.getDefaultState(), Blocks.BLACK_CONCRETE.getDefaultState(), Blocks.LIGHT_BLUE_TERRACOTTA.getDefaultState()},
            {Blocks.PURPLE_TERRACOTTA.getDefaultState(), Blocks.BLACK_CONCRETE.getDefaultState(), Blocks.MAGENTA_TERRACOTTA.getDefaultState()},
            {Blocks.CYAN_TERRACOTTA.getDefaultState(), Blocks.GRAY_CONCRETE.getDefaultState(), Blocks.LIGHT_GRAY_CONCRETE.getDefaultState()}};
    private static final BlockState WINDOW = Blocks.TINTED_GLASS.getDefaultState();
    private static final BlockState ROOF = Blocks.BLACK_CONCRETE.getDefaultState();
    private static final BlockState PARAPET = Blocks.POLISHED_BLACKSTONE.getDefaultState();
    private static final BlockState ROD = Blocks.LIGHTNING_ROD.getDefaultState();
    private static final BlockState BEACON = Blocks.MAGMA_BLOCK.getDefaultState();

    private static final int SETBACK = 6;           // between an avenue's edge and a tower
    private static final double CENTRE_X = -12;
    private static final double CENTRE_Z = -10;

    static List<int[]> blocks(int lo, int hi, int[][] roads) {
        List<int[]> out = new ArrayList<>();
        int start = lo;
        for (int[] r : roads) {
            out.add(new int[]{start, r[0] - 1});
            start = r[1] + 1;
        }
        out.add(new int[]{start, hi});
        return out;
    }

    /** True for the blocks that are not tower blocks: the showroom compound, Gotiya's block and the two parks beside the compound. */
    private static boolean reserved(int bx0, int bx1, int bz0, int bz1) {
        boolean midX = bx0 == -75 && bx1 == 51;
        boolean parkX = (bx0 == -171 && bx1 == -91) || (bx0 == 67 && bx1 == 145);
        boolean northOfBoulevard = bz0 == -37 && bz1 == -1;
        boolean southOfBoulevard = bz0 == 22 && bz1 == 69;
        return (midX && (northOfBoulevard || southOfBoulevard)) || (parkX && northOfBoulevard);
    }

    public static Plan build() {
        Plan p = new Plan();
        List<int[]> xb = blocks(X0, X1, NS);
        List<int[]> zb = blocks(Z0, Z1, EW);
        for (int[] bx : xb) {
            for (int[] bz : zb) {
                if (reserved(bx[0], bx[1], bz[0], bz[1])) {
                    continue;
                }
                block(p, bx[0], bx[1], bz[0], bz[1]);
            }
        }
        return p;
    }

    private static void block(Plan p, int bx0, int bx1, int bz0, int bz1) {
        int ux0 = bx0 + (bx0 == X0 ? 2 : SETBACK);
        int ux1 = bx1 - (bx1 == X1 ? 2 : SETBACK);
        int uz0 = bz0 + (bz0 == Z0 ? 2 : SETBACK);
        int uz1 = bz1 - (bz1 == Z1 ? 2 : SETBACK);
        int w = ux1 - ux0 + 1;
        int d = uz1 - uz0 + 1;
        if (w < 11 || d < 11) {
            return;
        }
        int nx = Math.max(1, w / 22);
        int nz = Math.max(1, d / 22);
        double cw = w / (double) nx;
        double cd = d / (double) nz;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int cx0 = ux0 + (int) Math.round(i * cw);
                int cx1 = ux0 + (int) Math.round((i + 1) * cw) - 1;
                int cz0 = uz0 + (int) Math.round(j * cd);
                int cz1 = uz0 + (int) Math.round((j + 1) * cd) - 1;
                cell(p, cx0, cx1, cz0, cz1);
            }
        }
    }

    private static void cell(Plan p, int cx0, int cx1, int cz0, int cz1) {
        Random r = new Random(((long) cx0 * 73856093L) ^ ((long) cz0 * 19349663L) ^ 0x5DEECE66DL);
        int cw = cx1 - cx0 + 1;
        int cd = cz1 - cz0 + 1;
        int tw = clamp(cw - (2 + r.nextInt(4)), 11, 30);
        int td = clamp(cd - (2 + r.nextInt(4)), 11, 30);
        int x0 = cx0 + Math.max(0, (cw - tw) / 2 + r.nextInt(3) - 1);
        int z0 = cz0 + Math.max(0, (cd - td) / 2 + r.nextInt(3) - 1);
        x0 = Math.min(x0, cx1 - tw + 1);
        z0 = Math.min(z0, cz1 - td + 1);
        double mx = x0 + tw / 2.0;
        double mz = z0 + td / 2.0;
        double dist = Math.hypot(mx - CENTRE_X, mz - CENTRE_Z);
        double t = Math.min(1.0, dist / 215.0);
        double st = t * t * (3 - 2 * t);
        int h = (int) ((24 + 88 * st) * (0.66 + 0.7 * r.nextDouble()));
        if (r.nextInt(12) == 0) {
            h += 26;
        }
        if (dist < 75) {
            h = Math.min(h, 30 + r.nextInt(8));
        }
        h = clamp(h, 14, 134);
        int style = h < 26 ? 4 : r.nextInt(4);
        Tower tower = new Tower(x0, z0, tw, td, h, style, r.nextInt(WALLS.length), r.nextInt(GLOW.length), r.nextInt(1 << 20));
        p.shape(x0, 1, z0, x0 + tw - 1, h + 9, z0 + td - 1, tower);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** One building. All fields are final and at() allocates nothing: it is called for every cell of the tower's box. */
    static final class Tower implements Plan.Shape {
        final int x0;
        final int z0;
        final int w;
        final int d;
        final int h;
        final int style;
        final int seed;
        final int crownY;
        final int antenna;
        final BlockState wall;
        final BlockState pillar;
        final BlockState band;
        final BlockState glow;
        final int litPercent;

        Tower(int x0, int z0, int w, int d, int h, int style, int wallIdx, int glowIdx, int seed) {
            this.x0 = x0;
            this.z0 = z0;
            this.w = w;
            this.d = d;
            this.h = h;
            this.style = style;
            this.seed = seed;
            this.crownY = (h >= 48 && w >= 14 && d >= 14) ? (int) (h * 0.82) : h + 1;
            this.antenna = h >= 58 ? 6 + (seed % 5) : 0;
            BlockState[] pal = WALLS[wallIdx];
            this.wall = style == 2 ? Blocks.BLACK_CONCRETE.getDefaultState() : pal[0];
            this.pillar = pal[1];
            this.band = pal[2];
            this.glow = GLOW[glowIdx];
            this.litPercent = style == 2 ? 7 : (style == 1 ? 34 : 24);
        }

        private static int hash(int a, int b, int c, int seed) {
            int v = a * 73856093 ^ b * 19349663 ^ c * 83492791 ^ seed * 0x9E3779B1;
            v ^= v >>> 13;
            v *= 0x5bd1e995;
            v ^= v >>> 15;
            return v & 0x7fffffff;
        }

        @Override
        public BlockState at(int x, int y, int z) {
            int rx = x - x0;
            int rz = z - z0;
            boolean crown = crownY < h && y > crownY;
            int ix0 = crown ? 2 : 0;
            int ix1 = crown ? w - 3 : w - 1;
            int iz0 = crown ? 2 : 0;
            int iz1 = crown ? d - 3 : d - 1;
            if (y > h) {
                if (y == h + 1 && rx >= ix0 && rx <= ix1 && rz >= iz0 && rz <= iz1 && (rx == ix0 || rx == ix1 || rz == iz0 || rz == iz1)) {
                    return PARAPET;
                }
                if (antenna > 0 && rx == w / 2 && rz == d / 2 && y <= h + antenna) {
                    return y == h + antenna ? BEACON : ROD;
                }
                return null;
            }
            if (y == crownY && crownY < h) {                    // the ledge of the stepped crown: a glowing ring round the setback
                if (rx >= 0 && rx < w && rz >= 0 && rz < d && (rx <= 1 || rx >= w - 2 || rz <= 1 || rz >= d - 2)) {
                    return (rx == 0 || rx == w - 1 || rz == 0 || rz == d - 1) ? glow : pillar;
                }
                return null;
            }
            if (rx < ix0 || rx > ix1 || rz < iz0 || rz > iz1) {
                return null;
            }
            boolean ex = rx == ix0 || rx == ix1;
            boolean ez = rz == iz0 || rz == iz1;
            if (y == h) {
                return ROOF;
            }
            if (!ex && !ez) {
                return null;                                    // hollow inside
            }
            boolean corner = ex && ez;
            int along = ex ? rz - iz0 : rx - ix0;
            int face = ex ? (rx == ix0 ? 0 : 1) : (rz == iz0 ? 2 : 3);
            // ground floor: a dark plinth, a lit shop-window storey, a sign band
            if (y <= 4) {
                if (y == 1) {
                    return pillar;
                }
                if (corner || along % 6 == 0) {
                    return pillar;
                }
                if (y == 4) {
                    return (hash(face, along / 3, 4, seed) % 100) < 55 ? glow : band;
                }
                return (hash(face, along / 3, y, seed) % 100) < 48 ? Blocks.SHROOMLIGHT.getDefaultState() : WINDOW;
            }
            switch (style) {
                case 1 -> {                                      // glass curtain wall
                    if (corner) {
                        return (y % 10 < 8) ? glow : pillar;
                    }
                    if (along % 8 == 0) {
                        return pillar;
                    }
                    if (y % 4 == 0) {
                        return band;
                    }
                    return (hash(face, along / 8, y / 4, seed) % 100) < litPercent ? glow : WINDOW;
                }
                case 2 -> {                                      // black monolith with glowing vertical strips
                    if (corner) {
                        return glow;
                    }
                    if (along % 6 == 3) {
                        return glow;
                    }
                    return (hash(face, along / 2, y / 3, seed) % 100) < litPercent ? glow : wall;
                }
                case 4 -> {                                      // modern low-rise: big glass, white floor plates
                    if (corner || along % 6 == 0) {
                        return pillar;
                    }
                    if (y % 4 == 0) {
                        return band;
                    }
                    return (hash(face, along / 3, y / 4, seed) % 100) < 30 ? glow : WINDOW;
                }
                default -> {                                     // 0 and 3: concrete with window bays
                    if (corner) {
                        return (y % 12 < 9) ? glow : pillar;
                    }
                    if (style == 3 && y % 16 == 15) {
                        return glow;                             // a glowing ring every 16 floors
                    }
                    if (along % 4 == 0) {
                        return pillar;
                    }
                    if (y % 5 == 4) {
                        return band;
                    }
                    int lit = litPercent + (h - y < 12 ? 12 : 0);
                    return (hash(face, along / 4, y / 5, seed) % 100) < lit ? glow : WINDOW;
                }
            }
        }
    }
}
