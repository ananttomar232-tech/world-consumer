package com.anant.teslatycoon.item;

import com.anant.teslatycoon.sign.PosterEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

/** Hangs the luxury wall poster on any wall or glass pillar (the same rules as a painting, 3 x 4 blocks). */
public final class WallPosterItem extends Item {
    public WallPosterItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        BlockPos clicked = context.getBlockPos();
        Direction side = context.getSide();
        if (side.getAxis().isVertical()) {
            return ActionResult.FAIL;
        }
        BlockPos at = clicked.offset(side);
        PlayerEntity player = context.getPlayer();
        ItemStack stack = context.getStack();
        if (player != null && !player.canPlaceOn(at, side, stack)) {
            return ActionResult.FAIL;
        }
        World world = context.getWorld();
        PosterEntity poster = new PosterEntity(world, at, side);
        if (!poster.canStayAttached()) {
            return ActionResult.CONSUME;
        }
        if (!world.isClient) {
            poster.onPlace();
            world.emitGameEvent(player, GameEvent.ENTITY_PLACE, poster.getPos());
            world.spawnEntity(poster);
        }
        stack.decrement(1);
        return ActionResult.success(world.isClient);
    }
}
