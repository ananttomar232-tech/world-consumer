package com.anant.teslatycoon.block;

import com.anant.teslatycoon.rival.RivalDirector;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** Seth Gotiya's road puncture spikes (sabotage 1). Armour tires crush them, anything else gets punctured. */
public final class SpikesBlock extends Block {
    private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 3, 16);

    public SpikesBlock() {
        super(AbstractBlock.Settings.copy(Blocks.IRON_BARS).strength(0.5f).sounds(BlockSoundGroup.CHAIN).noCollision().nonOpaque());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        if (world.isClient) {
            return;
        }
        if (entity instanceof VehicleEntity v) {
            RivalDirector.vehicleHitSpikes(world, pos, v);
        } else if (entity instanceof LivingEntity le && entity.age % 10 == 0) {
            le.damage(world.getDamageSources().cactus(), 1.0f);
        }
    }
}
