package com.anant.teslatycoon.cinema;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/** An invisible camera dolly for the cutscenes: the server moves it every tick and the player looks through it. Never saved. */
public final class CameraEntity extends Entity {
    public CameraEntity(EntityType<?> type, World world) {
        super(type, world);
        setNoGravity(true);
        setInvulnerable(true);
        noClip = true;
    }

    @Override
    protected void initDataTracker() {
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient && age > 20 * 60) {
            discard();                                 // a cutscene never lasts this long: never leave one behind
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
    }
}
