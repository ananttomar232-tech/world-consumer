package com.anant.teslatycoon.city;

import com.anant.teslatycoon.block.NeonBlock;
import com.anant.teslatycoon.block.NeonColor;
import com.anant.teslatycoon.registry.ModBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

/**
 * A list of box operations in city-local coordinates (x east, z south, y relative to the plaza surface block). The same plan is
 * executed in time slices by {@link CityBuilder}; the NEON operations can also be replayed alone to switch the power.
 */
public final class Plan {
    public static final class Op {
        public final int x1;
        public final int y1;
        public final int z1;
        public final int x2;
        public final int y2;
        public final int z2;
        public final BlockState state;
        public final boolean keepSolid;      // only replace air / liquids (ground fill)
        public final NeonColor neon;         // non-null: a NEON block, LIT decided at execution time

        Op(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state, boolean keepSolid, NeonColor neon) {
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
            this.z2 = Math.max(z1, z2);
            this.state = state;
            this.keepSolid = keepSolid;
            this.neon = neon;
        }

        public long volume() {
            return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        }
    }

    public static final BlockState AIR = Blocks.AIR.getDefaultState();

    public final List<Op> ops = new ArrayList<>();

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
        ops.add(new Op(x1, y1, z1, x2, y2, z2, s, false, null));
    }

    public void set(int x, int y, int z, BlockState s) {
        ops.add(new Op(x, y, z, x, y, z, s, false, null));
    }

    public void clear(int x1, int y1, int z1, int x2, int y2, int z2) {
        ops.add(new Op(x1, y1, z1, x2, y2, z2, AIR, false, null));
    }

    /** Fills only where there is air or liquid (used to make solid ground under the city). */
    public void ground(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
        ops.add(new Op(x1, y1, z1, x2, y2, z2, s, true, null));
    }

    public void neon(int x, int y, int z, NeonColor c) {
        ops.add(new Op(x, y, z, x, y, z, ModBlocks.NEON.getDefaultState().with(NeonBlock.COLOR, c), false, c));
    }

    public void neonFill(int x1, int y1, int z1, int x2, int y2, int z2, NeonColor c) {
        ops.add(new Op(x1, y1, z1, x2, y2, z2, ModBlocks.NEON.getDefaultState().with(NeonBlock.COLOR, c), false, c));
    }

    public static BlockPos world(BlockPos origin, int lx, int ly, int lz) {
        return new BlockPos(origin.getX() + lx, origin.getY() + ly, origin.getZ() + lz);
    }
}
