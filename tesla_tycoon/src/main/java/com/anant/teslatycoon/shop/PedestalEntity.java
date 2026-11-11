package com.anant.teslatycoon.shop;

import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * One display of the Upgrade Depot: the upgrade's 3D model floats and turns over a pedestal with its name and price. Right-click:
 * the money is taken and the physical item is put in the player's hand.
 */
public final class PedestalEntity extends Entity {
    private static final TrackedData<Byte> UPGRADE = DataTracker.registerData(PedestalEntity.class, TrackedDataHandlerRegistry.BYTE);

    public PedestalEntity(EntityType<?> type, World world) {
        super(type, world);
        setNoGravity(true);
        setInvulnerable(true);
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(UPGRADE, (byte) 0);
    }

    public Upgrade upgrade() {
        Upgrade u = Upgrade.byOrdinal(dataTracker.get(UPGRADE));
        return u == null ? Upgrade.HYDRAULICS : u;
    }

    public void setUpgrade(Upgrade u) {
        dataTracker.set(UPGRADE, (byte) u.ordinal());
    }

    @Override
    public boolean canHit() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (!getWorld().isClient && player instanceof ServerPlayerEntity sp && hand == Hand.MAIN_HAND) {
            Tycoon.buy(sp, upgrade());
        }
        return ActionResult.success(getWorld().isClient);
    }

    @Override
    public boolean shouldSave() {
        return false;                       // the depot's displays are re-created by the showroom logic
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
