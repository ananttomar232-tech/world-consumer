package com.anant.teslatycoon.vehicle;

/** Everything that differs between the six vehicles: which baked model, hit box, handling numbers, seat. */
public final class VehicleSpec {
    public final String id;
    public final String model;
    public final String lang;
    public final ModelMeta meta;
    public final float hitW;
    public final float hitH;
    public final double maxSpeed;      // blocks per tick on the ground
    public final double flySpeed;      // blocks per tick in the air (roadster)
    public final double accel;         // peak acceleration, blocks per tick^2
    public final double brake;
    public final double reverseFactor;
    public final float maxYawDeg;      // degrees per tick at full lock, low speed
    public final float stepHeight;
    public final boolean canFly;
    public final boolean heavy;        // armoured / ram capable
    public final float seatX;
    public final float seatFeetY;      // where the driver's FEET go (model space, blocks)
    public final float seatZ;
    public final float shadow;

    VehicleSpec(String id, String model, String lang, double maxSpeed, double flySpeed, double accel, double brake, float maxYawDeg,
                boolean canFly, boolean heavy, float stepHeight) {
        this.id = id;
        this.model = model;
        this.lang = lang;
        this.meta = ModelMeta.load(model);
        this.hitW = Math.max(meta.width + 0.3f, 0.55f * meta.length);
        this.hitH = meta.height + 0.1f;
        this.maxSpeed = maxSpeed;
        this.flySpeed = flySpeed;
        this.accel = accel;
        this.brake = brake;
        this.reverseFactor = 0.38;
        this.maxYawDeg = maxYawDeg;
        this.stepHeight = stepHeight;
        this.canFly = canFly;
        this.heavy = heavy;
        this.seatX = meta.seat[0];
        this.seatFeetY = meta.seatHip - 0.64f;     // the seated driver is drawn at 0.85 scale: hips sit 0.75 * 0.85 above the feet
        this.seatZ = meta.seat[2];
        this.shadow = Math.min(2.2f, meta.length * 0.3f);
    }

    public float length() { return meta.length; }
}
