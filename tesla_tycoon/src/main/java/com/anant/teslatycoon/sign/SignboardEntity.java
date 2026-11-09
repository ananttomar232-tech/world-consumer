package com.anant.teslatycoon.sign;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/**
 * A big flat sign drawn from the uploaded PNGs: the Anant Tesla Showroom banner, the highway billboards (empty frame until the
 * Billboard Marketing Pack is installed), and the text signs of the rival dealership and the upgrade shop.
 */
public final class SignboardEntity extends Entity {
    public static final int SHOWROOM = 0;
    public static final int BILLBOARD = 1;
    public static final int PETROL = 2;
    public static final int SHOP = 3;

    /** width x height in blocks for each kind (banner and billboard keep the aspect ratio of their PNGs). */
    public static final float[][] SIZE = {{21.0f, 8.9f}, {16.0f, 8.93f}, {19.0f, 3.4f}, {13.0f, 2.8f}};

    private static final TrackedData<Byte> KIND = DataTracker.registerData(SignboardEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Boolean> AD = DataTracker.registerData(SignboardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    public SignboardEntity(EntityType<?> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(KIND, (byte) 0);
        dataTracker.startTracking(AD, false);
    }

    public int kind() {
        return dataTracker.get(KIND);
    }

    public void setKind(int kind) {
        dataTracker.set(KIND, (byte) kind);
    }

    public boolean hasAd() {
        return dataTracker.get(AD);
    }

    public void setAd(boolean ad) {
        dataTracker.set(AD, ad);
    }

    public float signWidth() {
        return SIZE[Math.max(0, Math.min(3, kind()))][0];
    }

    public float signHeight() {
        return SIZE[Math.max(0, Math.min(3, kind()))][1];
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 220.0 * 220.0;
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        setKind(nbt.getByte("Kind"));
        setAd(nbt.getBoolean("Ad"));
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putByte("Kind", (byte) kind());
        nbt.putBoolean("Ad", hasAd());
    }
}
