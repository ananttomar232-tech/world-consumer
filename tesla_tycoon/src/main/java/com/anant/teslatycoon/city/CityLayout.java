package com.anant.teslatycoon.city;

import com.anant.teslatycoon.block.NeonColor;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.registry.ModBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.Direction;

/**
 * The city plan: a 391 x 371 block metropolis. Coordinates are local (x east, z south, y above the plaza surface).
 *
 * <pre>
 *   four N-S avenues (x -186, -90, 52, 146) and six E-W streets (z -172, -100, -44 alley, 0 boulevard, 70, 140), all 15 wide
 *   (the alley is 7, the Grand Boulevard 22 with a planted median), cross each other 24 times; the outer ring is the highway.
 *   The Anant Tesla Showroom and its Upgrade Depot stand on the north side of the boulevard, Seth Gotiya's Petrol Motors directly
 *   across on the south side. Everything else is blocks of skyscrapers, parks and plazas (see {@link Skyline}).
 * </pre>
 *
 * Traffic keeps to the right: on a N-S avenue [a, a+14] the southbound lanes are the west half (curb lane centre a+1.5, inner
 * a+5.5), the northbound lanes the east half (a+9.5 inner, a+13.5 curb); on an E-W street [c, c+14] westbound is the north half
 * (curb c+1.5, inner c+5.5) and eastbound the south half (inner c+9.5, curb c+13.5).
 */
public final class CityLayout {
    public static final int FLOOR_H = 7;
    // plot of the whole city (the margin rows outside the highway ring carry the tallest skyscrapers)
    public static final int X0 = -208;
    public static final int X1 = 182;
    public static final int Z0 = -194;
    public static final int Z1 = 176;
    // the road network: north-south avenues (x ranges) and east-west streets (z ranges); roads end at the outer ring
    public static final int[][] NS = {{-186, -172}, {-90, -76}, {52, 66}, {146, 160}};
    public static final int[][] EW = {{-172, -158}, {-100, -86}, {-44, -38}, {0, 21}, {70, 84}, {140, 154}};
    public static final int RING_X0 = -186;
    public static final int RING_X1 = 160;
    public static final int RING_Z0 = -172;
    public static final int RING_Z1 = 154;
    // the named roads
    public static final int[] AVE_WEST = NS[1];
    public static final int[] AVE_EAST = NS[2];
    public static final int[] ALLEY = EW[2];            // 7 wide service street behind the showroom (the delivery trucks use it)
    public static final int[] BOULEVARD = EW[3];        // the Grand Boulevard, 22 wide, z 0..21
    // showroom
    public static final int SX0 = -60;
    public static final int SX1 = -28;
    public static final int SZ0 = -35;
    public static final int SZ1 = -6;
    public static final int PAD_X = -44;
    public static final int PAD_Z = -20;
    public static final int BAY_X0 = -36;
    public static final int BAY_X1 = -30;
    public static final int BAY_START_Z = -27;
    public static final int DOOR_X0 = -48;
    public static final int DOOR_X1 = -42;
    // the lobby launch pad / elevator shaft (level 5) and the rooftop helipad
    public static final int LIFT_X = -52;               // centre of the 3 x 3 shaft
    public static final int LIFT_Z = -19;
    public static final int HELI_Z = -22;               // helipad centre z (x = PAD_X)
    // upgrade depot (right next door to the showroom, east side)
    public static final int PX0 = -24;
    public static final int PX1 = -4;
    public static final int PZ0 = -27;
    public static final int PZ1 = -6;
    // the security gate in front of the showroom and depot (closes when the alarm sounds)
    public static final int GATE_Z = -1;
    public static final int GATE_X0 = -62;
    public static final int GATE_X1 = -2;
    public static final int GATE_DOOR_X0 = -47;         // the 3-wide pedestrian opening (the Monster Truck is 4.7 wide)
    public static final int GATE_DOOR_X1 = -45;
    // Seth Gotiya's Petrol Motors (south of the boulevard, shifted by the wider boulevard)
    public static final int GOT_DZ = 8;
    public static final int GX0 = -62;
    public static final int GX1 = -18;
    public static final int GZ0 = 22 + GOT_DZ;
    public static final int GZ1 = 46 + GOT_DZ;
    // back alley loading dock
    public static final int DOCK_X0 = -50;
    public static final int DOCK_X1 = -44;

    static final BlockState GLASS = Blocks.GLASS.getDefaultState();
    static final BlockState BLACK = Blocks.BLACK_CONCRETE.getDefaultState();
    static final BlockState GRAY = Blocks.GRAY_CONCRETE.getDefaultState();
    static final BlockState LGRAY = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
    static final BlockState WHITE = Blocks.WHITE_CONCRETE.getDefaultState();
    static final BlockState YELLOW = Blocks.YELLOW_CONCRETE.getDefaultState();
    static final BlockState QUARTZ = Blocks.SMOOTH_QUARTZ.getDefaultState();
    static final BlockState PBLACK = Blocks.POLISHED_BLACKSTONE.getDefaultState();
    static final BlockState ASPHALT = ModBlocks.ASPHALT.getDefaultState();
    static final BlockState STONE = Blocks.STONE.getDefaultState();
    static final BlockState WALL = Blocks.POLISHED_BLACKSTONE_WALL.getDefaultState();
    static final BlockState GRASS = Blocks.GRASS_BLOCK.getDefaultState();

    private CityLayout() {}

    // ------------------------------------------------------------------------------------------------------------ lanes
    /** Lane centre (continuous coordinate) of a 15-wide road starting at {@code a}: 0 = north / west curb lane ... 3 = south / east curb lane. */
    public static double lane(int a, int k) {
        return a + new double[]{1.5, 5.5, 9.5, 13.5}[k];
    }

    // ------------------------------------------------------------------------------------------------------------ terrain
    /** Ground, plaza, roads with markings, intersections and the planted median of the boulevard. */
    public static Plan terrain() {
        Plan p = new Plan();
        // clear the air (trees, hills) and make solid ground under everything
        p.clear(X0, 1, Z0, X1, 44, Z1);
        p.ground(X0, -12, Z0, X1, -1, Z1, STONE);                  // solid under everything (only where there was air or water)
        // the final blocks of the two surface layers in one pass: y = 0 is the plaza (light grey with a grey stripe every 8 blocks),
        // the roads are cut down to y = -1 (asphalt, 1-block curbs); no cell is written twice
        p.shape(X0, -1, Z0, X1, 0, Z1, (x, y, z) -> {
            boolean road = isRoad(x, z);
            if (y == -1) {
                return road ? ASPHALT : null;
            }
            return road ? Plan.AIR : (Math.floorMod(x, 8) == 0 ? GRAY : LGRAY);
        });
        markings(p);
        crossings(p);
        median(p);
        return p;
    }

    /** True for a cell of the road network (the avenues and streets inside the highway ring). */
    public static boolean isRoad(int x, int z) {
        if (x < RING_X0 || x > RING_X1 || z < RING_Z0 || z > RING_Z1) {
            return false;
        }
        for (int[] r : NS) {
            if (x >= r[0] && x <= r[1]) {
                return true;
            }
        }
        for (int[] r : EW) {
            if (z >= r[0] && z <= r[1]) {
                return true;
            }
        }
        return false;
    }

    /** The stretches of [lo, hi] between the crossing roads, shortened by 5 on every side that meets a crossing (stop line + zebra). */
    static List<int[]> segments(int lo, int hi, int[][] crossings) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < crossings.length; i++) {
            int s = crossings[i][1] + 1 + 5;
            int e = crossings[i + 1][0] - 1 - 5;
            if (e - s >= 3) {
                out.add(new int[]{s, e});
            }
        }
        return out;
    }

    private static void dashesAlongZ(Plan p, int x, int z0, int z1, BlockState s) {
        for (int z = z0; z + 2 <= z1; z += 6) {
            p.fill(x, -1, z, x, -1, z + 2, s);
        }
    }

    private static void dashesAlongX(Plan p, int z, int x0, int x1, BlockState s) {
        for (int x = x0; x + 2 <= x1; x += 6) {
            p.fill(x, -1, z, x + 2, -1, z, s);
        }
    }

    private static void markings(Plan p) {
        for (int[] r : NS) {
            for (int[] seg : segments(RING_Z0, RING_Z1, EW)) {
                p.fill(r[0] + 7, -1, seg[0], r[0] + 7, -1, seg[1], YELLOW);                 // centre line
                dashesAlongZ(p, r[0] + 3, seg[0], seg[1], WHITE);                           // lane lines
                dashesAlongZ(p, r[0] + 11, seg[0], seg[1], WHITE);
                p.fill(r[0], -1, seg[0], r[0], -1, seg[1], WHITE);                          // edge lines
                p.fill(r[1], -1, seg[0], r[1], -1, seg[1], WHITE);
            }
        }
        for (int[] r : EW) {
            int w = r[1] - r[0] + 1;
            for (int[] seg : segments(RING_X0, RING_X1, NS)) {
                if (w == 15) {
                    p.fill(seg[0], -1, r[0] + 7, seg[1], -1, r[0] + 7, YELLOW);
                    dashesAlongX(p, r[0] + 3, seg[0], seg[1], WHITE);
                    dashesAlongX(p, r[0] + 11, seg[0], seg[1], WHITE);
                    p.fill(seg[0], -1, r[0], seg[1], -1, r[0], WHITE);
                    p.fill(seg[0], -1, r[1], seg[1], -1, r[1], WHITE);
                } else if (w == 22) {
                    dashesAlongX(p, r[0] + 4, seg[0], seg[1], WHITE);                       // the boulevard: two 4-wide lanes each way
                    dashesAlongX(p, r[0] + 17, seg[0], seg[1], WHITE);
                    p.fill(seg[0], -1, r[0], seg[1], -1, r[0], WHITE);
                    p.fill(seg[0], -1, r[1], seg[1], -1, r[1], WHITE);
                    p.fill(seg[0], -1, r[0] + 8, seg[1], -1, r[0] + 8, YELLOW);             // double yellow along the median
                    p.fill(seg[0], -1, r[0] + 13, seg[1], -1, r[0] + 13, YELLOW);
                } else {
                    dashesAlongX(p, r[0] + 3, seg[0], seg[1], YELLOW);                      // the alley
                }
            }
        }
    }

    /** Stop lines and zebra crossings on the four approaches of every intersection. */
    private static void crossings(Plan p) {
        for (int[] v : NS) {
            for (int[] h : EW) {
                for (int x = v[0] + 1; x < v[1]; x += 2) {
                    p.fill(x, -1, h[0] - 4, x, -1, h[0] - 2, WHITE);          // north approach
                    p.fill(x, -1, h[1] + 2, x, -1, h[1] + 4, WHITE);          // south approach
                }
                p.fill(v[0], -1, h[0] - 5, v[1], -1, h[0] - 5, WHITE);
                p.fill(v[0], -1, h[1] + 5, v[1], -1, h[1] + 5, WHITE);
                for (int z = h[0] + 1; z < h[1]; z += 2) {
                    p.fill(v[0] - 4, -1, z, v[0] - 2, -1, z, WHITE);          // west approach
                    p.fill(v[1] + 2, -1, z, v[1] + 4, -1, z, WHITE);          // east approach
                }
                p.fill(v[0] - 5, -1, h[0], v[0] - 5, -1, h[1], WHITE);
                p.fill(v[1] + 5, -1, h[0], v[1] + 5, -1, h[1], WHITE);
            }
        }
    }

    /** x ranges where the boulevard's median is planted (it is open in front of the showroom and the petrol station: U-turns, ramming). */
    static List<int[]> medianRuns() {
        List<int[]> out = new ArrayList<>();
        for (int[] seg : segments(RING_X0, RING_X1, NS)) {
            int a = seg[0];
            int b = seg[1];
            if (a < -52 && b > -26) {                      // the gap x -52..-26
                if (a <= -53) {
                    out.add(new int[]{a, -53});
                }
                if (b >= -25) {
                    out.add(new int[]{-25, b});
                }
            } else {
                out.add(new int[]{a, b});
            }
        }
        return out;
    }

    private static void median(Plan p) {
        int z0 = BOULEVARD[0] + 9;
        int z1 = BOULEVARD[0] + 12;
        for (int[] run : medianRuns()) {
            p.fill(run[0], 0, z0, run[1], 0, z1, GRASS);
            p.fill(run[0], 0, z0, run[1], 0, z0, LGRAY);
            p.fill(run[0], 0, z1, run[1], 0, z1, LGRAY);
            for (int x = run[0] + 4; x < run[1] - 2; x += 8) {
                tree(p, x, z0 + 1);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ street furniture
    /** Lamps, street trees, traffic lights, parks, robotaxi stations: everything that makes the streets look lived in. */
    public static Plan furniture() {
        Plan p = new Plan();
        // the parks of the old district and two blocks of greenery next to the showroom compound
        park(p, -70, -36, -62, -8);
        park(p, 24, -36, 46, -8);
        park(p, 24, 24, 50, 38);
        park(p, 24, 46, 50, 66);
        park(p, -73, 24, -65, 66);
        park(p, -168, -34, -94, -4);
        park(p, 70, -34, 142, -4);
        // N-S avenues: a tree and a lamp every 7 blocks on each pavement (staggered), not near the cross streets
        for (int[] r : NS) {
            for (int z = RING_Z0 + 9; z < RING_Z1 - 6; z += 14) {
                if (!nearCrossing(z, EW, 7)) {
                    streetTree(p, r[0] - 3, z);
                    streetTree(p, r[1] + 3, z + 7);
                }
                if (!nearCrossing(z + 7, EW, 7)) {
                    streetLamp(p, r[0] - 2, z + 7, NeonColor.CYAN);
                }
                if (!nearCrossing(z, EW, 7)) {
                    streetLamp(p, r[1] + 2, z, NeonColor.MAGENTA);
                }
            }
        }
        // E-W streets
        for (int[] r : EW) {
            boolean boulevard = r == BOULEVARD;
            boolean alley = r == ALLEY;
            for (int x = RING_X0 + 9; x < RING_X1 - 6; x += 14) {
                int xt = x;
                int xl = x + 7;
                if (alley) {
                    if (!nearCrossing(xl, NS, 7)) {
                        streetLamp(p, xl, r[0] - 2, NeonColor.CYAN);
                    }
                    continue;
                }
                boolean frontage = r[0] == BOULEVARD[0];
                // north pavement
                if (!nearCrossing(xt, NS, 7) && !(frontage && ((xt > -56 && xt < -24) || (xt > -22 && xt < -8)))) {
                    streetTree(p, xt, r[0] - 3);
                }
                if (!nearCrossing(xl, NS, 7) && !(frontage && xl > -50 && xl < -24)) {
                    streetLamp(p, xl, r[0] - 2, NeonColor.CYAN);
                }
                // south pavement (Gotiya's frontage and driveway stay open)
                boolean gotiya = boulevard && xt > GX0 - 4 && xt < GX1 + 6;
                if (!nearCrossing(xt + 7, NS, 7) && !gotiya) {
                    streetTree(p, xt + 7, r[1] + 3);
                }
                if (!nearCrossing(xt, NS, 7) && !(boulevard && xt > GX0 - 4 && xt < GX1 + 6)) {
                    streetLamp(p, xt, r[1] + 2, NeonColor.MAGENTA);
                }
            }
        }
        // the median of the boulevard: lamps
        for (int[] run : medianRuns()) {
            for (int x = run[0] + 8; x < run[1] - 2; x += 16) {
                lamp(p, x, BOULEVARD[0] + 11, NeonColor.WHITE);
            }
        }
        // traffic lights on the four corners of every intersection
        for (int[] v : NS) {
            for (int[] h : EW) {
                trafficLight(p, v[0] - 1, h[0] - 1);
                trafficLight(p, v[1] + 1, h[0] - 1);
                trafficLight(p, v[0] - 1, h[1] + 1);
                trafficLight(p, v[1] + 1, h[1] + 1);
            }
        }
        for (int[] s : Buildings.STATIONS) {
            station(p, s[0], s[1], s[2]);
        }
        return p;
    }

    /** Trees and lamps keep clear of billboards and station shelters. */
    private static boolean clearOfSigns(int x, int z) {
        if (Buildings.nearBillboard(x, z, 5)) {
            return false;
        }
        for (int[] st : Buildings.STATIONS) {
            if (Math.abs(x - st[0]) <= 7 && Math.abs(z - st[1]) <= 7) {
                return false;
            }
        }
        return true;
    }

    private static void streetTree(Plan p, int x, int z) {
        if (clearOfSigns(x, z)) {
            tree(p, x, z);
        }
    }

    private static void streetLamp(Plan p, int x, int z, NeonColor c) {
        if (clearOfSigns(x, z)) {
            lamp(p, x, z, c);
        }
    }

    private static boolean nearCrossing(int v, int[][] roads, int margin) {
        for (int[] r : roads) {
            if (v >= r[0] - margin && v <= r[1] + margin) {
                return true;
            }
        }
        return false;
    }

    static void park(Plan p, int x0, int z0, int x1, int z1) {
        p.fill(x0, 0, z0, x1, 0, z1, GRASS);
        p.fill(x0, 0, z0, x1, 0, z0, LGRAY);
        p.fill(x0, 0, z1, x1, 0, z1, LGRAY);
        p.fill(x0, 0, z0, x0, 0, z1, LGRAY);
        p.fill(x1, 0, z0, x1, 0, z1, LGRAY);
        for (int x = x0 + 3; x < x1 - 1; x += 6) {
            for (int z = z0 + 3; z < z1 - 1; z += 7) {
                tree(p, x, z);
            }
        }
    }

    private static final BlockState LOG = Blocks.OAK_LOG.getDefaultState();
    private static final BlockState LEAF = Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true);

    /** A street tree as one shape op: 4 high trunk, two leaf layers. */
    static void tree(Plan p, int x, int z) {
        p.shape(x - 2, 1, z - 2, x + 2, 7, z + 2, (cx, cy, cz) -> {
            int dx = Math.abs(cx - x);
            int dz = Math.abs(cz - z);
            if (dx == 0 && dz == 0 && cy <= 4) {
                return LOG;
            }
            if (cy >= 4 && cy <= 5 && dx <= 2 && dz <= 2 && !(dx == 2 && dz == 2)) {
                return LEAF;
            }
            if (cy >= 6 && cy <= 7 && dx <= 1 && dz <= 1 && !(cy == 7 && dx == 1 && dz == 1)) {
                return LEAF;
            }
            return null;
        });
    }

    static void lamp(Plan p, int x, int z, NeonColor c) {
        p.fill(x, 1, z, x, 6, z, WALL);
        p.fill(x - 1, 7, z, x + 1, 7, z, PBLACK);
        p.neon(x, 6, z, c);
        p.neon(x - 1, 6, z, NeonColor.WHITE);
        p.neon(x + 1, 6, z, NeonColor.WHITE);
        p.set(x, 0, z, PBLACK);
    }

    static void trafficLight(Plan p, int x, int z) {
        p.fill(x, 1, z, x, 5, z, WALL);
        p.neon(x, 6, z, NeonColor.RED);
        p.neon(x, 7, z, NeonColor.CYAN);
        p.set(x, 0, z, PBLACK);
    }

    /**
     * A robotaxi station on the pavement: a roofed shelter (7 long along the road, 3 deep) with a glass back wall and a bench, a
     * glowing sign post, and the pick-up bay marked in yellow on the curb lane. (x, z) is the centre of the shelter; side says where
     * the road lies: 0 north, 1 south, 2 west, 3 east of it (the shelter's road side is 3 cells from the road's edge cell).
     */
    static void station(Plan p, int x, int z, int side) {
        boolean ew = side <= 1;                                   // the road runs east-west, so the shelter is long in x
        int dx = side == 2 ? -1 : (side == 3 ? 1 : 0);
        int dz = side == 0 ? -1 : (side == 1 ? 1 : 0);
        int ax = ew ? 3 : 1;
        int az = ew ? 1 : 3;
        int x0 = x - ax;
        int x1 = x + ax;
        int z0 = z - az;
        int z1 = z + az;
        for (int cx : new int[]{x0, x1}) {
            for (int cz : new int[]{z0, z1}) {
                p.fill(cx, 1, cz, cx, 4, cz, WALL);
            }
        }
        p.fill(x0, 5, z0, x1, 5, z1, BLACK);
        BlockState pane = Blocks.GLASS_PANE.getDefaultState();
        BlockState bench = Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState();
        if (ew) {
            int back = dz > 0 ? z0 : z1;                          // away from the road
            p.fill(x0, 1, back, x1, 4, back, pane);
            p.fill(x - 2, 1, back - dz, x + 2, 1, back - dz, bench);
            int roadSide = dz > 0 ? z1 : z0;
            p.neonFill(x0, 5, roadSide + dz, x1, 5, roadSide + dz, NeonColor.CYAN);
        } else {
            int back = dx > 0 ? x0 : x1;
            p.fill(back, 1, z0, back, 4, z1, pane);
            p.fill(back - dx, 1, z - 2, back - dx, 1, z + 2, bench);
            int roadSide = dx > 0 ? x1 : x0;
            p.neonFill(roadSide + dx, 5, z0, roadSide + dx, 5, z1, NeonColor.CYAN);
        }
        // sign post at the end of the shelter (along the road)
        int sx = ew ? x1 + 2 : x;
        int sz = ew ? z : z1 + 2;
        p.fill(sx, 1, sz, sx, 7, sz, WALL);
        p.neon(sx, 8, sz, NeonColor.MAGENTA);
        p.neon(sx, 9, sz, NeonColor.CYAN);
        // the pick-up bay on the curb lane: two yellow bars across the lane
        int edgeX = x + dx * (ax + 2);
        int edgeZ = z + dz * (az + 2);
        if (ew) {
            for (int bx : new int[]{x - 4, x + 4}) {
                p.fill(bx, -1, edgeZ, bx, -1, edgeZ + dz * 2, YELLOW);
            }
        } else {
            for (int bz : new int[]{z - 4, z + 4}) {
                p.fill(edgeX, -1, bz, edgeX + dx * 2, -1, bz, YELLOW);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ shared helpers
    static BlockState stairs(Direction facing) {
        return Blocks.QUARTZ_STAIRS.getDefaultState().with(StairsBlock.FACING, facing);
    }

    static BlockState slab(BlockState block) {
        return Blocks.SMOOTH_STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    /** Local coordinates of the showroom floor surface (the block row the cars stand on) for a floor 1..5. */
    public static int floorSlab(int floor) {
        return FLOOR_H * (floor - 1);
    }

    public static boolean wants(Company c, Upgrade u) {
        return c != null && c.owns(u);
    }
}
