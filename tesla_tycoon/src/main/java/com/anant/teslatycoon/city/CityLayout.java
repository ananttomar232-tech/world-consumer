package com.anant.teslatycoon.city;

import com.anant.teslatycoon.block.NeonColor;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.Direction;

/**
 * The city plan. Coordinates are local (x east, z south, y above the plaza surface). The main road runs east-west at z 0..13 with
 * the Anant Tesla Showroom on its north side and Seth Gotiya's Petrol Motors directly across; a ring road (main road, west and east
 * connectors, back alley) gives the test drives somewhere to go.
 */
public final class CityLayout {
    public static final int FLOOR_H = 7;
    // plot of the whole city
    public static final int X0 = -90;
    public static final int X1 = 66;
    public static final int Z0 = -48;
    public static final int Z1 = 60;
    // roads (surface block at y = -1; sidewalks and plazas at y = 0)
    public static final int[] MAIN = {-88, 0, 64, 13};
    public static final int[] WEST = {-88, -44, -76, 13};
    public static final int[] EAST = {52, -44, 64, 13};
    public static final int[] ALLEY = {-88, -44, 64, -38};
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
    // upgrade shop
    public static final int PX0 = -24;
    public static final int PX1 = -4;
    public static final int PZ0 = -27;
    public static final int PZ1 = -6;
    // Seth Gotiya's Petrol Motors (south of the main road)
    public static final int GX0 = -62;
    public static final int GX1 = -18;
    public static final int GZ0 = 22;
    public static final int GZ1 = 46;
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

    private CityLayout() {}

    // ------------------------------------------------------------------------------------------------------------ whole city
    public static Plan base() {
        Plan p = new Plan();
        // clear the air (trees, hills) and make solid ground under everything
        p.clear(X0, 1, Z0, X1, 14, Z1);
        p.clear(SX0 - 3, 15, SZ0 - 3, SX1 + 3, 52, SZ1 + 3);
        p.ground(X0, -12, Z0, X1, -2, Z1, STONE);
        p.fill(X0, -1, Z0, X1, -1, Z1, STONE);
        // plaza everywhere at y = 0, then roads dig down to y = -1 (the 1-block curb)
        p.fill(X0, 0, Z0, X1, 0, Z1, LGRAY);
        for (int[] r : new int[][]{MAIN, WEST, EAST, ALLEY}) {
            p.fill(r[0], 0, r[1], r[2], 0, r[3], Plan.AIR);
            p.fill(r[0], -1, r[1], r[2], -1, r[3], ASPHALT);
        }
        // lane markings: double yellow centre line on the main road, white dashes on the lane lines
        p.fill(MAIN[0], -1, 6, MAIN[2], -1, 6, YELLOW);
        p.fill(MAIN[0], -1, 7, MAIN[2], -1, 7, YELLOW);
        for (int x = MAIN[0] + 2; x < MAIN[2]; x += 6) {
            p.fill(x, -1, 3, x + 2, -1, 3, WHITE);
            p.fill(x, -1, 10, x + 2, -1, 10, WHITE);
        }
        for (int z = WEST[1] + 2; z < 12; z += 6) {
            p.fill(-82, -1, z, -82, -1, z + 2, YELLOW);
            p.fill(58, -1, z, 58, -1, z + 2, YELLOW);
        }
        for (int x = ALLEY[0] + 2; x < ALLEY[2]; x += 6) {
            p.fill(x, -1, -41, x + 2, -1, -41, YELLOW);
        }
        // plaza pavement pattern + parks
        for (int x = X0; x <= X1; x += 8) {
            p.fill(x, 0, Z0, x, 0, Z1, GRAY);
        }
        park(p, -74, -36, -62, -8);
        park(p, 24, -36, 50, -8);
        park(p, 24, 16, 50, 30);
        park(p, -88, 16, -66, 40);
        crosswalk(p, -40, 0, -32, 13);
        crosswalk(p, 10, 0, 18, 13);
        lamps(p);
        return p;
    }

    static void park(Plan p, int x0, int z0, int x1, int z1) {
        p.fill(x0, 0, z0, x1, 0, z1, Blocks.GRASS_BLOCK.getDefaultState());
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

    static void tree(Plan p, int x, int z) {
        BlockState log = Blocks.OAK_LOG.getDefaultState();
        BlockState leaf = Blocks.OAK_LEAVES.getDefaultState().with(net.minecraft.block.LeavesBlock.PERSISTENT, true);
        p.fill(x, 1, z, x, 4, z, log);
        p.fill(x - 2, 4, z - 2, x + 2, 5, z + 2, leaf);
        p.fill(x - 1, 6, z - 1, x + 1, 7, z + 1, leaf);
        p.set(x, 4, z, log);
    }

    static void crosswalk(Plan p, int x0, int z0, int x1, int z1) {
        for (int z = z0; z <= z1; z += 2) {
            p.fill(x0, -1, z, x1, -1, z, WHITE);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ street lamps
    static void lamps(Plan p) {
        for (int x = -84; x <= 62; x += 12) {
            lamp(p, x, -2, NeonColor.CYAN);
            if (x + 6 != -42) {                          // no lamp in the middle of Gotiya's driveway
                lamp(p, x + 6, 16, NeonColor.MAGENTA);
            }
        }
        for (int z = -40; z <= 8; z += 12) {
            lamp(p, -74, z, NeonColor.CYAN);
            lamp(p, 50, z, NeonColor.MAGENTA);
        }
        for (int x = -80; x <= 60; x += 14) {
            lamp(p, x, -37, NeonColor.CYAN);
        }
    }

    static void lamp(Plan p, int x, int z, NeonColor c) {
        p.fill(x, 1, z, x, 6, z, WALL);
        p.fill(x - 1, 7, z, x + 1, 7, z, PBLACK);
        p.neon(x, 6, z, c);
        p.neon(x - 1, 6, z, NeonColor.WHITE);
        p.neon(x + 1, 6, z, NeonColor.WHITE);
        p.set(x, 0, z, PBLACK);
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
