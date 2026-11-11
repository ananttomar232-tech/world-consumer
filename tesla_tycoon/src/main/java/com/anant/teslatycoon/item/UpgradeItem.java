package com.anant.teslatycoon.item;

import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * What a pedestal of the Upgrade Depot hands over: the physical upgrade. Carry it to the showroom (a new level) or out onto the street
 * (a countermeasure) and right-click to build / deploy it.
 */
public final class UpgradeItem extends Item {
    public final Upgrade upgrade;

    public UpgradeItem(Upgrade upgrade, Settings settings) {
        super(settings);
        this.upgrade = upgrade;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            Tycoon.deploy(sp, upgrade, stack);
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        if (!ctx.getWorld().isClient && ctx.getPlayer() instanceof ServerPlayerEntity sp) {
            Tycoon.deploy(sp, upgrade, ctx.getStack());
        }
        return ActionResult.success(ctx.getWorld().isClient);
    }
}
