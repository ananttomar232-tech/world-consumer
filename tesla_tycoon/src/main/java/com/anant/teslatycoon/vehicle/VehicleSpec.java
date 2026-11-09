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
    public final float occScale;       // how big the seated figure is drawn (fitted to the cabin: nothing may stick out of the car)
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
        this.occScale = meta.occ > 0 ? meta.occ : 0.85f;
        this.seatFeetY = meta.seatHip - 0.70f * occScale;      // the hips of the (scaled) figure are 0.70 above its feet
        this.seatZ = meta.seat[2];
        this.shadow = Math.min(2.2f, meta.length * 0.3f);
    }

    public float length() { return meta.length; }

    /** Pitch of the seated figure's legs (radians, negative = forward): they reach from the hips down to the floor of the cabin. */
    public float legPitch() {
        double len = 0.70 * occScale;
        double drop = meta.seatHip - meta.floor - 0.02;
        return (float) -Math.acos(Math.max(0.0, Math.min(1.0, drop / len)));
    }
}
