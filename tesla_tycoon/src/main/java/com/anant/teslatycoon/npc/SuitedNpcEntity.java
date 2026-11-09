package com.anant.teslatycoon.npc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A suited villager that is moved by a script, not by pathfinding: the showroom manager, the rich customers, the shop clerk and
 * Seth Gotiya himself. It follows a list of waypoints at walking speed (the vanilla limb animation does the rest).
 */
public final class SuitedNpcEntity extends PathAwareEntity {
    public static final int MANAGER = 0;
    public static final int CUSTOMER = 1;
    public static final int GOTIYA = 2;
    public static final int CLERK = 3;

    private static final TrackedData<Byte> ROLE = DataTracker.registerData(SuitedNpcEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Byte> LOOK = DataTracker.registerData(SuitedNpcEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Boolean> SITTING = DataTracker.registerData(SuitedNpcEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> CRYING = DataTracker.registerData(SuitedNpcEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    public static final int LOOKS = 6;

    private final List<Vec3d> path = new ArrayList<>();
    private int pathIndex;
    private double walkSpeed = 0.16;
    private Runnable onArrive;
    private float faceYaw = Float.NaN;
    public int lifeTicks;
    public int tag;

    public SuitedNpcEntity(EntityType<? extends SuitedNpcEntity> type, World world) {
        super(type, world);
        this.setPersistent();
        this.setInvulnerable(true);
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes().add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0).add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        dataTracker.startTracking(ROLE, (byte) CUSTOMER);
        dataTracker.startTracking(LOOK, (byte) 0);
        dataTracker.startTracking(SITTING, false);
        dataTracker.startTracking(CRYING, false);
    }

    @Override
    protected void initGoals() {
        // scripted: no goals
    }

    public int role() {
        return dataTracker.get(ROLE);
    }

    public void setRole(int role) {
        dataTracker.set(ROLE, (byte) role);
    }

    public int look() {
        return dataTracker.get(LOOK);
    }

    public void setLook(int look) {
        dataTracker.set(LOOK, (byte) (look % LOOKS));
    }

    public boolean isSitting() {
        return dataTracker.get(SITTING);
    }

    public void setSitting(boolean sitting) {
        dataTracker.set(SITTING, sitting);
    }

    public boolean isCrying() {
        return dataTracker.get(CRYING);
    }

    public void setCrying(boolean crying) {
        dataTracker.set(CRYING, crying);
    }

    /** Walks the given points one after the other; {@code arrive} runs once at the end. */
    public void walk(List<Vec3d> points, double speed, Runnable arrive) {
        path.clear();
        path.addAll(points);
        pathIndex = 0;
        walkSpeed = speed;
        onArrive = arrive;
    }

    public void faceTowards(float yaw) {
        faceYaw = yaw;
    }

    public boolean isWalking() {
        return pathIndex < path.size();
    }

    @Override
    public void tick() {
        super.tick();
        lifeTicks++;
        if (getWorld().isClient) {
            if (isCrying() && age % 4 == 0) {
                getWorld().addParticle(net.minecraft.particle.ParticleTypes.FALLING_WATER, getX() + (random.nextDouble() - 0.5) * 0.4, getEyeY() - 0.1,
                        getZ() + (random.nextDouble() - 0.5) * 0.4, 0, -0.05, 0);
            }
            return;
        }
        if (hasVehicle()) {
            return;
        }
        if (pathIndex < path.size()) {
            Vec3d target = path.get(pathIndex);
            Vec3d d = target.subtract(getPos());
            double flat = Math.hypot(d.x, d.z);
            if (flat < walkSpeed + 0.05) {
                setPosition(target.x, target.y, target.z);
                pathIndex++;
                if (pathIndex >= path.size()) {
                    path.clear();
                    Runnable r = onArrive;
                    onArrive = null;
                    if (r != null) {
                        r.run();
                    }
                }
            } else {
                double nx = d.x / flat * walkSpeed;
                double nz = d.z / flat * walkSpeed;
                double ny = d.y * Math.min(1.0, walkSpeed / flat);
                float yaw = (float) (MathHelper.atan2(-d.x, d.z) * 180.0 / Math.PI);
                setYaw(yaw);
                setBodyYaw(yaw);
                setHeadYaw(yaw);
                setPosition(getX() + nx, getY() + ny, getZ() + nz);
                setVelocity(Vec3d.ZERO);
            }
        } else if (!Float.isNaN(faceYaw)) {
            setYaw(faceYaw);
            setBodyYaw(faceYaw);
            setHeadYaw(faceYaw);
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return source.isOf(net.minecraft.entity.damage.DamageTypes.OUT_OF_WORLD) && super.damage(source, amount);
    }

    /** /kill must work on these damage-proof scripted NPCs as well (the directors simply bring the staff back). */
    @Override
    public void kill() {
        discard();
    }

    @Override
    public boolean canBeLeashedBy(net.minecraft.entity.player.PlayerEntity player) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void pushAwayFrom(net.minecraft.entity.Entity entity) {
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putByte("Role", (byte) role());
        nbt.putByte("Look", (byte) look());
        nbt.putBoolean("Sitting", isSitting());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        setRole(nbt.getByte("Role"));
        setLook(nbt.getByte("Look"));
        setSitting(nbt.getBoolean("Sitting"));
    }

    /** NPCs are rebuilt by the directors; nothing about them is stored in chunks. */
    @Override
    public boolean shouldSave() {
        return false;
    }
}
