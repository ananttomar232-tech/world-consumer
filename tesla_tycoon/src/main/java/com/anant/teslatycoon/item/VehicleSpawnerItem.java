package com.anant.teslatycoon.item;

import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpec;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Creative-tab key that parks one of the vehicles where you click (handy for filming; the shop and test drives are the real way). */
public final class VehicleSpawnerItem extends Item {
    private final VehicleSpec spec;

    public VehicleSpawnerItem(VehicleSpec spec, Settings settings) {
        super(settings);
        this.spec = spec;
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        BlockPos at = ctx.getBlockPos().offset(ctx.getSide());
        VehicleEntity v = new VehicleEntity(ModEntities.vehicle(spec), world);
        float yaw = ctx.getPlayer() != null ? ctx.getPlayer().getYaw() + 180f : 0f;
        v.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0f);
        v.setMode(VehicleEntity.MODE_DRIVEN);
        if (ctx.getPlayer() != null) {
            v.setOwner(ctx.getPlayer().getUuid());
        }
        world.spawnEntity(v);
        if (ctx.getPlayer() == null || !ctx.getPlayer().getAbilities().creativeMode) {
            ctx.getStack().decrement(1);
        }
        return ActionResult.CONSUME;
    }
}
