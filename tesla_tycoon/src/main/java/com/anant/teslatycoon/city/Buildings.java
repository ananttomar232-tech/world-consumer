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
        // the counter with the shop terminal, and shelves of "stock"
        p.fill(-18, 1, -14, -10, 1, -14, BLACK);
        p.set(-14, 1, -14, ModBlocks.SHOP_TERMINAL.getDefaultState().with(HorizontalFacingBlock.FACING, Direction.SOUTH));
        p.neonFill(-18, 1, -13, -10, 1, -13, NeonColor.CYAN);
        p.fill(PX0 + 1, 1, PZ0 + 1, PX0 + 1, 3, PZ0 + 8, BLACK);
        p.fill(PX1 - 1, 1, PZ0 + 1, PX1 - 1, 3, PZ0 + 8, BLACK);
        p.neonFill(PX0 + 2, 3, PZ0 + 1, PX0 + 2, 3, PZ0 + 8, NeonColor.MAGENTA);
        p.neonFill(PX1 - 2, 3, PZ0 + 1, PX1 - 2, 3, PZ0 + 8, NeonColor.MAGENTA);
        // sign board on the roof edge (entity in front)
        p.fill(-20, 8, PZ1 - 1, -8, 10, PZ1 - 1, BLACK);
        return p;
    }

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
        if (c != null && c.owns(Upgrade.TERMINAL)) {
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
        // workshop: back half of the plot
        p.fill(GX0, 0, 34, GX1, 0, GZ1, GRAY);
        p.fill(GX0, 1, 34, GX1, 7, 34, red);
        p.fill(GX0, 1, GZ1, GX1, 7, GZ1, red);
        p.fill(GX0, 1, 34, GX0, 7, GZ1, red);
        p.fill(GX1, 1, 34, GX1, 7, GZ1, red);
        p.fill(GX0, 8, 34, GX1, 8, GZ1, BLACK);
        for (int x = GX0 + 4; x <= GX1 - 8; x += 14) {
            p.clear(x, 1, 34, x + 8, 6, 34);                 // 6 high: the Monster Truck is 4.9 blocks tall
            p.fill(x - 1, 1, 34, x - 1, 7, 34, orange);
            p.fill(x + 9, 1, 34, x + 9, 7, 34, orange);
            p.fill(x - 1, 7, 34, x + 9, 7, 34, orange);
        }
        for (int x = GX0 + 6; x < GX1; x += 9) {
            p.fill(x, 0, 36, x + 2, 0, GZ1 - 1, BLACK);
        }
        // sign board on the front wall
        p.fill(-49, 9, 34, -31, 12, 34, red);
        // canopy and pumps over the forecourt
        for (int x : new int[]{-58, -22}) {
            for (int z : new int[]{24, 31}) {
                p.fill(x, 1, z, x, 6, z, WALL);
            }
        }
        p.fill(-59, 7, 23, -21, 7, 32, orange);              // high enough for the Monster Truck to drive under
        p.fill(-59, 7, 23, -21, 7, 23, YELLOW);
        for (int x = -54; x <= -30; x += 8) {
            if (x == -46 || x == -38) {
                continue;                                  // the driveway in the middle stays open: the Monster Truck is 4.7 blocks wide
            }
            p.fill(x, 1, 27, x, 2, 27, red);
            p.set(x, 3, 27, Blocks.IRON_BARS.getDefaultState());
            p.set(x, 0, 27, BLACK);
        }
        p.fill(GX0, 0, GZ0, GX1, 0, 33, GRAY);
        for (int x = GX0; x <= GX1; x += 4) {
            p.fill(x, 0, GZ0, x, 0, 33, BLACK);
        }
        for (int x = -60; x <= -20; x += 8) {
            if (x == -44 || x == -36) {
                continue;                                  // open driveway between the garage and the road
            }
            p.fill(x, 1, GZ0, x, 4, GZ0, Blocks.OAK_FENCE.getDefaultState());
        }
        return p;
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

    public static final int[][] BILLBOARDS = {
            {-66, 15, 0}, {8, 15, 0}, {40, 15, 0}, {-22, 15, 0}, {-60, -46, 2}, {0, -46, 2}
    };

    public static Direction billboardFacing(int idx) {
        return BILLBOARDS[idx][2] == 0 ? Direction.NORTH : Direction.SOUTH;
    }
}
