package com.anant.teslatycoon.city;

import static com.anant.teslatycoon.city.CityLayout.*;

import com.anant.teslatycoon.block.NeonColor;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;

/** The buildings: the stacked glass showroom, its roof, the upgrade shop, Gotiya's petrol station and the billboard frames. */
public final class Buildings {
    private Buildings() {}

    // ============================================================================================================ showroom
    /** One glass floor (1..5). Floor k's slab is also the ceiling of floor k-1. Includes the stairs that arrive from below. */
    public static Plan showroomFloor(int k) {
        Plan p = new Plan();
        int ys = floorSlab(k);
        if (k > 1) {
            p.clear(SX0 + 1, ys + 1, SZ0 + 1, SX1 - 1, ys + 14, SZ1 - 1);     // the old roof's banner, legs and lights
        }
        // floor slab: quartz with black pinstripes; the previous roof (if any) is replaced
        p.fill(SX0, ys, SZ0, SX1, ys, SZ1, QUARTZ);
        for (int x = SX0 + 4; x < SX1; x += 4) {
            p.fill(x, ys, SZ0, x, ys, SZ1, PBLACK);
        }
        // glass curtain walls with black pillars
        p.fill(SX0, ys + 1, SZ0, SX1, ys + 6, SZ0, GLASS);
        p.fill(SX0, ys + 1, SZ1, SX1, ys + 6, SZ1, GLASS);
        p.fill(SX0, ys + 1, SZ0, SX0, ys + 6, SZ1, GLASS);
        p.fill(SX1, ys + 1, SZ0, SX1, ys + 6, SZ1, GLASS);
        for (int x = SX0; x <= SX1; x += 8) {
            p.fill(x, ys + 1, SZ0, x, ys + 6, SZ0, BLACK);
            p.fill(x, ys + 1, SZ1, x, ys + 6, SZ1, BLACK);
        }
        for (int z = SZ0; z <= SZ1; z += 8) {
            p.fill(SX0, ys + 1, z, SX0, ys + 6, z, BLACK);
            p.fill(SX1, ys + 1, z, SX1, ys + 6, z, BLACK);
        }
        p.fill(SX1, ys + 1, SZ1, SX1, ys + 6, SZ1, BLACK);
        p.fill(SX0, ys + 1, SZ1, SX0, ys + 6, SZ1, BLACK);
        // glowing sills
        p.neonFill(SX0 + 1, ys + 1, SZ0 + 1, SX1 - 1, ys + 1, SZ0 + 1, NeonColor.CYAN);
        p.neonFill(SX0 + 1, ys + 1, SZ0 + 1, SX0 + 1, ys + 1, SZ1 - 1, NeonColor.CYAN);
        p.neonFill(SX1 - 1, ys + 1, SZ0 + 1, SX1 - 1, ys + 1, SZ1 - 1, NeonColor.CYAN);
        // ceiling lights (the slab above is added by the next floor or by the roof)
        for (int x = SX0 + 4; x < SX1; x += 6) {
            for (int z = SZ0 + 4; z < SZ1; z += 6) {
                p.neon(x, ys + 6, z, NeonColor.WHITE);
            }
        }
        pad(p, ys);
        // feature wall for the wall posters (north wall, inside)
        p.fill(-58, ys + 1, SZ0 + 1, -48, ys + 6, SZ0 + 1, BLACK);
        p.neonFill(-58, ys + 6, SZ0 + 2, -48, ys + 6, SZ0 + 2, NeonColor.MAGENTA);
        if (k == 1) {
            groundFloorExtras(p);
        } else {
            // stairs arriving from the floor below: opening in this slab, 7 steps running north, glass railings
            int below = floorSlab(k - 1);
            p.clear(-59, ys, -30, -57, ys, -24);
            for (int i = 0; i < 7; i++) {
                for (int x = -59; x <= -57; x++) {
                    p.set(x, below + 1 + i, -24 - i, stairs(Direction.NORTH));
                }
            }
            p.fill(-56, ys + 1, -30, -56, ys + 2, -24, Blocks.GLASS_PANE.getDefaultState());
            p.fill(-59, ys + 1, -23, -57, ys + 2, -23, Blocks.GLASS_PANE.getDefaultState());
        }
        return p;
    }

    static void pad(Plan p, int ys) {
        for (int dz = -5; dz <= 5; dz++) {
            for (int dx = -5; dx <= 5; dx++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > 5.0) {
                    continue;
                }
                if (r > 4.0) {
                    p.neon(PAD_X + dx, ys, PAD_Z + dz, NeonColor.CYAN);
                } else {
                    p.set(PAD_X + dx, ys, PAD_Z + dz, PBLACK);
                }
            }
        }
    }

    static void groundFloorExtras(Plan p) {
        int ys = 0;
        // entrance and test-drive bay door in the south (road side) wall, with glowing frames
        p.clear(DOOR_X0, 1, SZ1, DOOR_X1, 5, SZ1);
        p.neonFill(DOOR_X0 - 1, 1, SZ1, DOOR_X0 - 1, 6, SZ1, NeonColor.MAGENTA);
        p.neonFill(DOOR_X1 + 1, 1, SZ1, DOOR_X1 + 1, 6, SZ1, NeonColor.MAGENTA);
        p.neonFill(DOOR_X0 - 1, 6, SZ1, DOOR_X1 + 1, 6, SZ1, NeonColor.MAGENTA);
        p.clear(BAY_X0, 1, SZ1, BAY_X1, 5, SZ1);
        p.neonFill(BAY_X0 - 1, 1, SZ1, BAY_X0 - 1, 6, SZ1, NeonColor.CYAN);
        p.neonFill(BAY_X1 + 1, 1, SZ1, BAY_X1 + 1, 6, SZ1, NeonColor.CYAN);
        p.neonFill(BAY_X0 - 1, 6, SZ1, BAY_X1 + 1, 6, SZ1, NeonColor.CYAN);
        // hazard-striped test-drive lane, running out over the pavement in half-height steps down to the road
        p.fill(BAY_X0, ys, SZ0 + 2, BAY_X1, ys, SZ1, BLACK);
        for (int z = SZ0 + 2; z <= SZ1; z += 2) {
            p.fill(BAY_X0, ys, z, BAY_X1, ys, z, YELLOW);
        }
        p.neonFill(BAY_X0 - 1, ys, SZ0 + 2, BAY_X0 - 1, ys, SZ1, NeonColor.CYAN);
        p.neonFill(BAY_X1 + 1, ys, SZ0 + 2, BAY_X1 + 1, ys, SZ1, NeonColor.CYAN);
        p.fill(BAY_X0, ys, SZ1 + 1, BAY_X1, ys, -1, slab(QUARTZ));
        // reception desk, the manager's stool and a lounge
        p.fill(-58, 1, -12, -49, 1, -11, QUARTZ);
        p.fill(-58, 2, -12, -49, 2, -11, BLACK);
        p.neonFill(-58, 1, -10, -49, 1, -10, NeonColor.CYAN);
        p.set(-54, 1, -14, QUARTZ);
        p.neon(-58, 3, -12, NeonColor.WHITE);
        p.neon(-49, 3, -12, NeonColor.WHITE);
        p.fill(-40, 1, -12, -38, 1, -11, BLACK);
        // loading dock door at the back and its bumper platform in the alley
        p.clear(DOCK_X0, 1, SZ0, DOCK_X1, 4, SZ0);
        p.neonFill(DOCK_X0 - 1, 1, SZ0, DOCK_X0 - 1, 5, SZ0, NeonColor.RED);
        p.neonFill(DOCK_X1 + 1, 1, SZ0, DOCK_X1 + 1, 5, SZ0, NeonColor.RED);
        p.neonFill(DOCK_X0 - 1, 5, SZ0, DOCK_X1 + 1, 5, SZ0, NeonColor.RED);
        p.fill(DOCK_X0 - 1, 0, SZ0 - 2, DOCK_X1 + 1, 0, SZ0 - 1, GRAY);
        p.fill(DOCK_X0 - 1, 0, SZ0 - 3, DOCK_X1 + 1, 0, SZ0 - 3, YELLOW);
    }

    /** Roof slab and everything on it, for a showroom that has {@code level} floors. */
    public static Plan showroomRoof(int level, Company c) {
        Plan p = new Plan();
        int yr = FLOOR_H * level;
        p.clear(SX0 - 1, yr + 1, SZ0 - 1, SX1 + 1, yr + 16, SZ1 + 1);
        p.fill(SX0, yr, SZ0, SX1, yr, SZ1, BLACK);
        p.fill(SX0, yr + 1, SZ0, SX1, yr + 1, SZ0, QUARTZ);
        p.fill(SX0, yr + 1, SZ0, SX0, yr + 1, SZ1, QUARTZ);
        p.fill(SX1, yr + 1, SZ0, SX1, yr + 1, SZ1, QUARTZ);
        p.neonFill(SX0, yr + 1, SZ1, SX1, yr + 1, SZ1, NeonColor.CYAN);
        // banner board (the entity with showroom_banner.png is spawned in front of it) with its two legs
        p.fill(-54, yr + 2, SZ1 - 1, -34, yr + 10, SZ1 - 1, BLACK);
        p.fill(-54, yr + 1, SZ1 - 1, -54, yr + 1, SZ1 - 1, BLACK);
        p.fill(-34, yr + 1, SZ1 - 1, -34, yr + 1, SZ1 - 1, BLACK);
        p.neonFill(-55, yr + 2, SZ1 - 1, -55, yr + 10, SZ1 - 1, NeonColor.CYAN);
        p.neonFill(-33, yr + 2, SZ1 - 1, -33, yr + 10, SZ1 - 1, NeonColor.CYAN);
        p.neonFill(-54, yr + 11, SZ1 - 1, -34, yr + 11, SZ1 - 1, NeonColor.CYAN);
        // Tesla Solar Roof (counter to the blackout)
        if (c != null && c.owns(Upgrade.SOLAR)) {
            BlockState solar = Blocks.DAYLIGHT_DETECTOR.getDefaultState();
            for (int z = SZ0 + 2; z <= SZ0 + 8; z += 2) {
                p.fill(SX0 + 3, yr + 1, z, SX1 - 3, yr + 1, z, solar);
            }
        }
        if (c != null && c.owns(Upgrade.STARLINK)) {
            p.set(SX1 - 3, yr + 1, SZ0 + 12, ModBlocks.STARLINK_DISH.getDefaultState());
            p.fill(SX1 - 3, yr + 2, SZ0 + 12, SX1 - 3, yr + 3, SZ0 + 12, WALL);
        }
        // the rooftop launchpad for the Flying Roadster (level 5)
        if (level >= 5) {
            int cz = -22;
            for (int dz = -7; dz <= 7; dz++) {
                for (int dx = -7; dx <= 7; dx++) {
                    double r = Math.sqrt(dx * dx + dz * dz);
                    if (r > 7.0) {
                        continue;
                    }
                    if (r > 6.0) {
                        p.set(PAD_X + dx, yr, cz + dz, YELLOW);
                    } else if (r > 3.6 && r <= 4.6) {
                        p.neon(PAD_X + dx, yr, cz + dz, NeonColor.RED);
                    } else {
                        p.set(PAD_X + dx, yr, cz + dz, PBLACK);
                    }
                }
            }
            p.fill(PAD_X - 6, yr, cz, PAD_X + 6, yr, cz, WHITE);
            p.fill(PAD_X, yr, cz - 6, PAD_X, yr, cz + 6, WHITE);
        }
        return p;
    }

    // ============================================================================================================ finale: gate, lift
    /** The roof-top beacons and the gate posts that flash red while the alarm sounds: {x, y, z} city-local (see {@link #alarmLights}). */
    public static List<int[]> alarmLights(int level) {
        List<int[]> out = new ArrayList<>();
        for (int x = GATE_X0; x <= GATE_X1; x += 12) {
            out.add(new int[]{x, 8, GATE_Z});
        }
        int yr = FLOOR_H * level;
        out.add(new int[]{SX0, yr + 3, SZ0});
        out.add(new int[]{SX1, yr + 3, SZ0});
        out.add(new int[]{SX0, yr + 3, SZ1});
        out.add(new int[]{SX1, yr + 3, SZ1});
        return out;
    }

    /**
     * The security gate along the boulevard pavement: a black plinth, iron bars, black pillars and a red neon rail, with a 3-wide
     * pedestrian opening. Closed = built; open = removed. The Monster Truck (4.7 blocks wide) cannot get through the opening.
     */
    public static Plan gate(boolean closed) {
        Plan p = new Plan();
        int z = GATE_Z;
        if (!closed) {
            p.clear(GATE_X0 - 1, 1, z, GATE_X1 + 1, 8, z);
            return p;
        }
        BlockState bars = Blocks.IRON_BARS.getDefaultState().with(net.minecraft.block.PaneBlock.EAST, true).with(net.minecraft.block.PaneBlock.WEST, true);
        p.fill(GATE_X0, 1, z, GATE_X1, 1, z, BLACK);
        p.fill(GATE_X0, 2, z, GATE_X1, 5, z, bars);
        for (int x = GATE_X0; x <= GATE_X1; x += 6) {
            p.fill(x, 1, z, x, 6, z, BLACK);
        }
        p.fill(GATE_X1, 1, z, GATE_X1, 6, z, BLACK);
        p.neonFill(GATE_X0, 6, z, GATE_X1, 6, z, NeonColor.RED);
        for (int x = GATE_X0; x <= GATE_X1; x += 12) {
            p.fill(x, 7, z, x, 7, z, BLACK);
            p.neon(x, 8, z, NeonColor.RED);
        }
        p.clear(GATE_DOOR_X0, 1, z, GATE_DOOR_X1, 8, z);
        p.neonFill(GATE_DOOR_X0 - 1, 1, z, GATE_DOOR_X0 - 1, 7, z, NeonColor.CYAN);
        p.neonFill(GATE_DOOR_X1 + 1, 1, z, GATE_DOOR_X1 + 1, 7, z, NeonColor.CYAN);
        return p;
    }

    /** The hole the Monster Truck makes when the gate shatters (x range of the cleared part). */
    public static final int GATE_HOLE_X0 = -50;
    public static final int GATE_HOLE_X1 = -26;

    public static Plan gateHole() {
        Plan p = new Plan();
        p.clear(GATE_HOLE_X0, 1, GATE_Z, GATE_HOLE_X1, 8, GATE_Z);
        return p;
    }

    /**
     * Level 5: the high-speed lift. A 3 x 3 shaft in glass runs through every floor slab and the roof (the openings are cleared),
     * the launch pad glows on the ground floor, a glass cabin tops the shaft on the roof, and four red beacons crown the roof.
     */
    public static Plan lift() {
        Plan p = new Plan();
        int cx = LIFT_X;
        int cz = LIFT_Z;
        for (int k = 1; k <= 5; k++) {
            int y = FLOOR_H * k;
            p.clear(cx - 1, y, cz - 1, cx + 1, y, cz + 1);
        }
        // shaft walls: glass with black corner pillars, a doorway on the south side of the ground floor
        p.fill(cx - 2, 1, cz - 2, cx + 2, 38, cz - 2, GLASS);
        p.fill(cx - 2, 1, cz + 2, cx + 2, 38, cz + 2, GLASS);
        p.fill(cx - 2, 1, cz - 2, cx - 2, 38, cz + 2, GLASS);
        p.fill(cx + 2, 1, cz - 2, cx + 2, 38, cz + 2, GLASS);
        for (int dx : new int[]{-2, 2}) {
            for (int dz : new int[]{-2, 2}) {
                p.fill(cx + dx, 1, cz + dz, cx + dx, 39, cz + dz, BLACK);
            }
        }
        p.clear(cx - 1, 1, cz + 2, cx + 1, 3, cz + 2);
        p.neonFill(cx - 2, 4, cz + 2, cx - 2, 4, cz + 2, NeonColor.CYAN);
        // glowing rings round the shaft on every floor
        for (int k = 0; k < 5; k++) {
            int y = FLOOR_H * k + 5;
            p.neonFill(cx - 2, y, cz - 2, cx + 2, y, cz - 2, NeonColor.CYAN);
            p.neonFill(cx - 2, y, cz + 2, cx + 2, y, cz + 2, NeonColor.CYAN);
            p.neonFill(cx - 2, y, cz - 2, cx - 2, y, cz + 2, NeonColor.CYAN);
            p.neonFill(cx + 2, y, cz - 2, cx + 2, y, cz + 2, NeonColor.CYAN);
        }
        // the cabin's ceiling on the roof and its cap
        p.fill(cx - 1, 35, cz - 1, cx + 1, 35, cz + 1, GLASS);
        p.fill(cx - 2, 39, cz - 2, cx + 2, 39, cz + 2, BLACK);
        p.neonFill(cx - 1, 40, cz - 1, cx + 1, 40, cz + 1, NeonColor.MAGENTA);
        // the launch pad on the ground floor
        p.neonFill(cx - 1, 0, cz - 1, cx + 1, 0, cz + 1, NeonColor.CYAN);
        p.neon(cx, 0, cz, NeonColor.WHITE);
        // the alarm beacons on the roof corners
        for (int[] b : alarmLights(5)) {
            if (b[2] == GATE_Z) {
                continue;
            }
            p.fill(b[0], 36, b[2], b[0], b[1] - 1, b[2], BLACK);
            p.neon(b[0], b[1], b[2], NeonColor.RED);
        }
        return p;
    }

    // ============================================================================================================ upgrade shop
    public static Plan shop() {
        Plan p = new Plan();
        p.fill(PX0, 0, PZ0, PX1, 0, PZ1, QUARTZ);
        p.fill(PX0, 1, PZ0, PX1, 6, PZ0, QUARTZ);
        p.fill(PX0, 1, PZ0, PX0, 6, PZ1, GLASS);
        p.fill(PX1, 1, PZ0, PX1, 6, PZ1, GLASS);
        p.fill(PX0, 1, PZ1, PX1, 6, PZ1, GLASS);
        for (int x = PX0; x <= PX1; x += 5) {
            p.fill(x, 1, PZ1, x, 6, PZ1, BLACK);
            p.fill(x, 1, PZ0, x, 6, PZ0, BLACK);
        }
        for (int z = PZ0; z <= PZ1; z += 7) {
            p.fill(PX0, 1, z, PX0, 6, z, BLACK);
            p.fill(PX1, 1, z, PX1, 6, z, BLACK);
        }
        p.clear(-17, 1, PZ1, -11, 4, PZ1);
        p.neonFill(-18, 1, PZ1, -18, 5, PZ1, NeonColor.CYAN);
        p.neonFill(-10, 1, PZ1, -10, 5, PZ1, NeonColor.CYAN);
        p.neonFill(-18, 5, PZ1, -10, 5, PZ1, NeonColor.CYAN);
        p.fill(PX0, 7, PZ0, PX1, 7, PZ1, BLACK);
        p.neonFill(PX0, 8, PZ1, PX1, 8, PZ1, NeonColor.MAGENTA);
        for (int x = PX0 + 3; x < PX1; x += 5) {
            for (int z = PZ0 + 3; z < PZ1; z += 5) {
                p.neon(x, 6, z, NeonColor.WHITE);
            }
        }
        // the display pedestals (one per upgrade, see PEDESTALS): a quartz pillar on a glowing tile; the floating model is an entity
        p.clear(PX0 + 1, 1, PZ0 + 1, PX1 - 1, 5, PZ1 - 1);
        for (int[] d : PEDESTALS) {
            p.neon(d[0], 0, d[1], NeonColor.CYAN);
            p.set(d[0], 1, d[1], Blocks.QUARTZ_PILLAR.getDefaultState());
        }
        p.neonFill(PX0 + 1, 6, PZ0 + 1, PX0 + 1, 6, PZ1 - 1, NeonColor.MAGENTA);
        p.neonFill(PX1 - 1, 6, PZ0 + 1, PX1 - 1, 6, PZ1 - 1, NeonColor.MAGENTA);
        // sign board on the roof edge (entity in front)
        p.fill(-20, 8, PZ1 - 1, -8, 10, PZ1 - 1, BLACK);
        return p;
    }

    /** Where the pedestal of each upgrade stands in the depot ({x, z}, in the order of Upgrade.values()): a U from the door inwards. */
    public static final int[][] PEDESTALS = {
            {-21, -9}, {-21, -13}, {-21, -17}, {-21, -21},
            {-17, -24}, {-14, -24}, {-11, -24},
            {-7, -21}, {-7, -17}, {-7, -13}
    };

    /** Extras that appear in the shop and next to the showroom once the counter-items are bought. */
    public static Plan upgrades(Company c) {
        Plan p = new Plan();
        if (c != null && c.owns(Upgrade.SOLAR)) {
            // MegaPack battery containers beside the showroom
            for (int i = 0; i < 3; i++) {
                int x = SX0 - 6;
                int z = SZ0 + 3 + i * 4;
                p.fill(x, 1, z, x + 2, 2, z + 2, ModBlocks.MEGAPACK.getDefaultState());
                p.neonFill(x, 3, z, x + 2, 3, z + 2, NeonColor.CYAN);
            }
        }
        if (c != null && c.level >= 4) {
            p.set(BAY_X0 - 3, 1, -12, ModBlocks.ROBOTAXI_TERMINAL.getDefaultState().with(HorizontalFacingBlock.FACING, Direction.SOUTH));
            p.neonFill(BAY_X0 - 3, 0, -12, BAY_X0 - 3, 0, -12, NeonColor.CYAN);
        }
        return p;
    }

    // ============================================================================================================ Gotiya's petrol motors
    public static Plan petrol() {
        Plan p = new Plan();
        BlockState red = Blocks.RED_CONCRETE.getDefaultState();
        BlockState orange = Blocks.ORANGE_CONCRETE.getDefaultState();
        int d = GOT_DZ;
        int wz = 34 + d;                                    // front wall of the workshop
        // workshop: back half of the plot
        p.fill(GX0, 0, wz, GX1, 0, GZ1, GRAY);
        p.fill(GX0, 1, wz, GX1, 7, wz, red);
        p.fill(GX0, 1, GZ1, GX1, 7, GZ1, red);
        p.fill(GX0, 1, wz, GX0, 7, GZ1, red);
        p.fill(GX1, 1, wz, GX1, 7, GZ1, red);
        p.fill(GX0, 8, wz, GX1, 8, GZ1, BLACK);
        for (int x = GX0 + 4; x <= GX1 - 8; x += 14) {
            p.clear(x, 1, wz, x + 8, 6, wz);                 // 6 high: the Monster Truck is 4.9 blocks tall
            p.fill(x - 1, 1, wz, x - 1, 7, wz, orange);
            p.fill(x + 9, 1, wz, x + 9, 7, wz, orange);
            p.fill(x - 1, 7, wz, x + 9, 7, wz, orange);
        }
        for (int x = GX0 + 6; x < GX1; x += 9) {
            p.fill(x, 0, wz + 2, x + 2, 0, GZ1 - 1, BLACK);
        }
        // sign board on the front wall
        p.fill(-49, 9, wz, -31, 12, wz, red);
        // canopy and pumps over the forecourt
        for (int x : new int[]{-58, -22}) {
            for (int z : new int[]{24 + d, 31 + d}) {
                p.fill(x, 1, z, x, 6, z, WALL);
            }
        }
        p.fill(-59, 7, 23 + d, -21, 7, 32 + d, orange);      // high enough for the Monster Truck to drive under
        p.fill(-59, 7, 23 + d, -21, 7, 23 + d, YELLOW);
        for (int x = -54; x <= -30; x += 8) {
            if (x == -46 || x == -38) {
                continue;                                  // the driveway in the middle stays open: the Monster Truck is 4.7 blocks wide
            }
            p.fill(x, 1, 27 + d, x, 2, 27 + d, red);
            p.set(x, 3, 27 + d, Blocks.IRON_BARS.getDefaultState());
            p.set(x, 0, 27 + d, BLACK);
        }
        p.fill(GX0, 0, GZ0, GX1, 0, 33 + d, GRAY);
        for (int x = GX0; x <= GX1; x += 4) {
            p.fill(x, 0, GZ0, x, 0, 33 + d, BLACK);
        }
        for (int x = -60; x <= -20; x += 8) {
            if (x == -44 || x == -36) {
                continue;                                  // open driveway between the garage and the road
            }
            p.fill(x, 1, GZ0, x, 4, GZ0, Blocks.OAK_FENCE.getDefaultState());
        }
        p.ops.addAll(rivalShowroom(1, false, false).ops);
        return p;
    }

    // ============================================================================================================ Gotiya's showroom (v4)
    /** The display spots in his showroom (x, z of the car centre); the middle lane x -44..-35 stays free for the Monster Truck. */
    public static final double[][] RIVAL_SPOTS = {{-55.5, 48.5}, {-49.0, 48.5}, {-30.0, 48.5}, {-23.5, 48.5}};

    /**
     * The old workshop behind the pumps becomes Seth Gotiya's petrol-car showroom: a glass front, a polished floor, ceiling lights, red
     * neon and lit display spots. It grows with the player's level (he "upgrades" too): more lights, gold trim, spotlights, a neon roof.
     * destroyed: the ruin after /destroy. partner: the joint EV + petrol showroom (cyan neon) once he joins the player.
     */
    public static Plan rivalShowroom(int level, boolean destroyed, boolean partner) {
        Plan p = new Plan();
        int d = GOT_DZ;
        int wz = 34 + d;
        BlockState red = Blocks.RED_CONCRETE.getDefaultState();
        BlockState orange = Blocks.ORANGE_CONCRETE.getDefaultState();
        if (destroyed) {
            ruin(p, wz);
            return p;
        }
        NeonColor neon = partner ? NeonColor.CYAN : NeonColor.RED;
        // the walls and roof again (a ruin can be rebuilt by the partnership)
        p.fill(GX0, 1, wz, GX1, 7, wz, red);
        p.fill(GX0, 1, GZ1, GX1, 7, GZ1, red);
        p.fill(GX0, 1, wz, GX0, 7, GZ1, red);
        p.fill(GX1, 1, wz, GX1, 7, GZ1, red);
        p.fill(GX0, 8, wz, GX1, 8, GZ1, BLACK);
        p.clear(GX0 + 1, 1, wz + 1, GX1 - 1, 7, GZ1 - 1);
        // polished floor, black frames under the cars
        p.fill(GX0 + 1, 0, wz + 1, GX1 - 1, 0, GZ1 - 1, Blocks.SMOOTH_QUARTZ.getDefaultState());
        p.fill(-44, 0, wz + 1, -35, 0, GZ1 - 1, Blocks.POLISHED_BLACKSTONE.getDefaultState());       // the truck lane
        int cars = rivalCars(level);
        for (int i = 0; i < RIVAL_SPOTS.length; i++) {
            int cx = (int) Math.floor(RIVAL_SPOTS[i][0]);
            int cz = (int) Math.floor(RIVAL_SPOTS[i][1]);
            p.fill(cx - 2, 0, cz - 3, cx + 2, 0, cz + 3, BLACK);
            if (i < cars && level >= 2) {
                p.neonFill(cx - 2, 0, cz - 3, cx + 2, 0, cz - 3, neon);                            // a glowing edge in front of each car
                p.neonFill(cx - 2, 0, cz + 3, cx + 2, 0, cz + 3, neon);
            }
        }
        // the front: big glass shop windows either side of the open middle door
        p.fill(GX0 + 1, 1, wz, -46, 6, wz, Blocks.GLASS_PANE.getDefaultState());
        p.fill(-33, 1, wz, GX1 - 1, 6, wz, Blocks.GLASS_PANE.getDefaultState());
        p.clear(-45, 1, wz, -34, 6, wz);
        p.fill(-46, 1, wz, -46, 7, wz, orange);
        p.fill(-33, 1, wz, -33, 7, wz, orange);
        p.fill(-46, 7, wz, -33, 7, wz, orange);
        // ceiling lights: more of them every level
        int step = level >= 3 ? 4 : 6;
        for (int x = GX0 + 3; x <= GX1 - 3; x += step) {
            for (int z = wz + 3; z <= GZ1 - 2; z += 5) {
                p.set(x, 7, z, Blocks.SEA_LANTERN.getDefaultState());
            }
        }
        // red (or cyan) neon along the back wall and a lit sign band over the door
        p.neonFill(GX0 + 1, 6, GZ1 - 1, GX1 - 1, 6, GZ1 - 1, neon);
        p.neonFill(-45, 7, wz - 1, -34, 7, wz - 1, neon);
        // lanterns under the forecourt canopy
        for (int x = -56; x <= -24; x += 8) {
            p.set(x, 6, 27 + d, Blocks.LANTERN.getDefaultState().with(net.minecraft.block.LanternBlock.HANGING, true));
        }
        if (level >= 3 || partner) {
            p.fill(GX0, 8, wz - 1, GX1, 8, wz - 1, Blocks.GOLD_BLOCK.getDefaultState());               // gold trim along the facade
            for (int x : new int[]{GX0 + 1, -46, -33, GX1 - 1}) {
                p.neonFill(x, 1, wz - 1, x, 6, wz - 1, neon);                                         // neon pillars
            }
        }
        if (level >= 4 || partner) {
            for (int x = GX0 + 2; x <= GX1 - 2; x += 5) {
                p.set(x, 9, wz, Blocks.END_ROD.getDefaultState());                                     // spotlights on the roof edge
            }
            p.fill(-42, 0, wz - 6, -37, 0, wz - 1, Blocks.RED_WOOL.getDefaultState());                   // the red carpet
        }
        if (level >= 5 || partner) {
            p.neonFill(GX0, 9, GZ1, GX1, 9, GZ1, neon);                                                 // neon roof outline
            p.neonFill(GX0, 9, wz, GX0, 9, GZ1, neon);
            p.neonFill(GX1, 9, wz, GX1, 9, GZ1, neon);
        }
        return p;
    }

    /** How many petrol cars he has on show at the player's level (he upgrades as the player does). */
    public static int rivalCars(int level) {
        return Math.max(1, Math.min(RIVAL_SPOTS.length, level));
    }

    /** After /destroy: broken walls, the roof caved in, rubble, smoke from the wreck. Deterministic (the same ruin every time). */
    private static void ruin(Plan p, int wz) {
        int d = GOT_DZ;
        p.clear(GX0, 1, 23 + d, GX1, 8, GZ1);
        p.fill(GX0, 0, 23 + d, GX1, 0, GZ1, Blocks.COBBLED_DEEPSLATE.getDefaultState());
        BlockState[] rubble = {Blocks.COBBLESTONE.getDefaultState(), Blocks.GRAVEL.getDefaultState(), Blocks.COAL_BLOCK.getDefaultState(),
                Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState(), Blocks.RED_CONCRETE.getDefaultState(), Blocks.CRACKED_STONE_BRICKS.getDefaultState()};
        for (int x = GX0; x <= GX1; x++) {
            for (int z = 23 + d; z <= GZ1; z++) {
                int h = (int) (Math.floorMod(x * 734287 + z * 912931, 97));
                boolean wall = x == GX0 || x == GX1 || z == GZ1 || z == wz;
                int height = wall ? h % 4 : (h < 22 ? 1 : (h < 30 ? 2 : 0));
                for (int y = 1; y <= height; y++) {
                    p.set(x, y, z, rubble[(h + y) % rubble.length]);
                }
                if (h == 5 || h == 41 || h == 77) {
                    p.set(x, height + 1, z, Blocks.CAMPFIRE.getDefaultState());                              // smoke rising from the wreck
                }
            }
        }
        // two canopy pillars still standing, bent
        p.fill(-58, 1, 24 + d, -58, 4, 24 + d, WALL);
        p.fill(-22, 1, 31 + d, -22, 3, 31 + d, WALL);
        p.set(-57, 4, 24 + d, Blocks.ORANGE_CONCRETE.getDefaultState());
    }

    // ============================================================================================================ billboards
    /** A highway billboard frame. Returns the plan; {@link CityBuilder} spawns the sign entity at {@link #billboardCentre}. */
    public static Plan billboard(int bx, int bz, Direction facing) {
        Plan p = new Plan();
        boolean alongX = facing.getAxis() == Direction.Axis.Z;
        BlockState panel = ModBlocks.BILLBOARD_PANEL.getDefaultState();
        if (alongX) {
            p.fill(bx - 8, 5, bz, bx + 7, 13, bz, panel);
            p.fill(bx - 9, 1, bz, bx - 9, 14, bz, BLACK);
            p.fill(bx + 8, 1, bz, bx + 8, 14, bz, BLACK);
            p.fill(bx - 9, 4, bz, bx + 8, 4, bz, QUARTZ);
            p.neonFill(bx - 8, 14, bz, bx + 7, 14, bz, NeonColor.CYAN);
        } else {
            p.fill(bx, 5, bz - 8, bx, 13, bz + 7, panel);
            p.fill(bx, 1, bz - 9, bx, 14, bz - 9, BLACK);
            p.fill(bx, 1, bz + 8, bx, 14, bz + 8, BLACK);
            p.fill(bx, 4, bz - 9, bx, 4, bz + 8, QUARTZ);
            p.neonFill(bx, 14, bz - 8, bx, 14, bz + 7, NeonColor.CYAN);
        }
        return p;
    }

    public static double[] billboardCentre(int bx, int bz, Direction facing) {
        double off = 0.06;
        return switch (facing) {
            case NORTH -> new double[]{bx, 9.5, bz - off};
            case SOUTH -> new double[]{bx, 9.5, bz + 1 + off};
            case WEST -> new double[]{bx - off, 9.5, bz};
            default -> new double[]{bx + 1 + off, 9.5, bz};
        };
    }

    /**
     * The six city billboards, far apart on the highway ring (each one a drive of 130 to 200 blocks from the showroom): the outer
     * avenues' turns in the north-west / north-east / east / south / south-west / west. {bx, bz} is the board's centre line cell.
     */
    public static final int[][] BILLBOARDS = {
            {-120, -154}, {100, -154}, {143, 40}, {60, 137}, {-120, 137}, {-169, 0}
    };
    private static final Direction[] BILLBOARD_FACING = {Direction.NORTH, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.SOUTH, Direction.WEST};

    public static Direction billboardFacing(int idx) {
        return BILLBOARD_FACING[idx];
    }

    /** The cell {x, z} of billboard idx's centre, for distance checks. */
    public static boolean nearBillboard(int x, int z, int margin) {
        for (int i = 0; i < BILLBOARDS.length; i++) {
            boolean alongX = BILLBOARD_FACING[i].getAxis() == Direction.Axis.Z;
            int bx = BILLBOARDS[i][0];
            int bz = BILLBOARDS[i][1];
            int hx = alongX ? 10 : 3;
            int hz = alongX ? 3 : 10;
            if (x >= bx - hx - margin && x <= bx + hx + margin && z >= bz - hz - margin && z <= bz + hz + margin) {
                return true;
            }
        }
        return false;
    }

    /** Robotaxi stations {x, z, side} (side = where the road lies: 0 north, 1 south, 2 west, 3 east; see CityLayout.station). */
    public static final int[][] STATIONS = {
            {-10, -155, 0}, {143, -20, 3}, {-10, 137, 1}, {-169, -65, 2},        // the outer ring (north, east, south, west)
            {-20, -83, 0}, {49, -20, 3}, {-73, -20, 2}, {30, 67, 1},             // the inner ring and the core loop
            {8, -3, 1}, {49, 42, 3}                                              // in front of the showroom, and the inner ring's south side
    };
}
