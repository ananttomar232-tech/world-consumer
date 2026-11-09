package com.anant.teslatycoon.block;

import com.anant.teslatycoon.sign.Billboards;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** The steel back board of a highway billboard: right-click an empty one to install city_billboard.png. */
public final class BillboardPanelBlock extends Block {
    public BillboardPanelBlock() {
        super(AbstractBlock.Settings.copy(Blocks.IRON_BLOCK).strength(5.0f, 1200.0f).sounds(BlockSoundGroup.METAL));
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (player instanceof ServerPlayerEntity sp) {
            Billboards.tryInstall(sp, world, pos);
        }
        return ActionResult.CONSUME;
    }
}
