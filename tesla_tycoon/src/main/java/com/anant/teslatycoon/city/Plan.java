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
    /** A procedural block pattern inside the box of an op (towers, trees ...): the block at a city-local cell, or null = leave it alone. */
    public interface Shape {
        BlockState at(int x, int y, int z);
    }

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
        public final Shape shape;            // non-null: the block of every cell comes from the shape (state is unused)

        Op(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state, boolean keepSolid, NeonColor neon) {
            this(x1, y1, z1, x2, y2, z2, state, keepSolid, neon, null);
        }

        Op(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state, boolean keepSolid, NeonColor neon, Shape shape) {
            this.x1 = Math.min(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.z1 = Math.min(z1, z2);
            this.x2 = Math.max(x1, x2);
            this.y2 = Math.max(y1, y2);
            this.z2 = Math.max(z1, z2);
            this.state = state;
            this.keepSolid = keepSolid;
            this.neon = neon;
            this.shape = shape;
        }

        public long volume() {
            return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        }

        /** The part of this op inside the given x / z range (city-local, inclusive), or null if there is none. */
        public Op clip(int minX, int maxX, int minZ, int maxZ) {
            int a = Math.max(x1, minX);
            int b = Math.min(x2, maxX);
            int c = Math.max(z1, minZ);
            int d = Math.min(z2, maxZ);
            if (a > b || c > d) {
                return null;
            }
            if (a == x1 && b == x2 && c == z1 && d == z2) {
                return this;
            }
            return new Op(a, y1, c, b, y2, d, state, keepSolid, neon, shape);
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

    /** A procedurally patterned box (see {@link Shape}); cells for which the shape returns null are not touched. */
    public void shape(int x1, int y1, int z1, int x2, int y2, int z2, Shape s) {
        ops.add(new Op(x1, y1, z1, x2, y2, z2, null, false, null, s));
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
