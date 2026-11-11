package com.anant.teslatycoon.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.Dismounting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/**
 * One class for all six vehicles (the {@link VehicleSpec} decides the model and the handling).
 *
 * <p>Smoothness comes from the same design vanilla boats use: the client of the driver simulates the vehicle itself (so there is
 * no input-to-screen network round trip), the server only validates the vanilla VehicleMove packet, and every other client
 * interpolates. The driver is positioned from the vehicle after it moved, in the same tick, so camera and car never disagree.
 * Steps are climbed with {@code stepHeight} 1.2 and hidden from the camera by a decaying visual offset.
 */
public class VehicleEntity extends Entity {
    public static final int MODE_DRIVEN = 0;    // a player's car
    public static final int MODE_DISPLAY = 1;   // a rotating showroom exhibit
    public static final int MODE_AI = 2;        // driven by the server: customers, the delivery truck, Gotiya's truck

    public static final int F_THRUST = 1;
    public static final int F_FLYING = 2;
    public static final int F_RAM = 4;
    public static final int F_PUNCTURED = 8;
    public static final int F_DISABLED = 16;    // EMP'd
    public static final int F_JAMMED = 32;      // Cybercab GPS jammed
    public static final int F_ARMOR = 64;       // armour tires
    public static final int F_HYDRAULICS = 128; // jump hydraulics

    private static final TrackedData<Byte> MODE = DataTracker.registerData(VehicleEntity.class, TrackedDataHandlerRegistry.BYTE);
    private static final TrackedData<Integer> FLAGS = DataTracker.registerData(VehicleEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Optional<UUID>> OWNER = DataTracker.registerData(VehicleEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    /** The keyboard of the local player; set by the client initializer (null on a dedicated server). */
    public interface ClientBridge {
        void poll(VehicleEntity vehicle, Controls out);
        void sync(VehicleEntity vehicle, Controls controls);
    }

    /** Game rules that live outside the physics (spikes, roadblocks, upgrades); set by the tycoon logic. */
    public interface ServerHook {
        void tick(VehicleEntity vehicle);
        void interactDisplay(ServerPlayerEntity player, VehicleEntity display);
    }

    public static ClientBridge bridge;
    public static ServerHook hook;

    /** What the driver (or the AI) asks for this tick. steer: +1 = left. */
    public static final class Controls {
        public float throttle;
        public float steer;
        public boolean jump;
        public boolean descend;
        public boolean ram;

        public void clear() {
            throttle = 0;
            steer = 0;
            jump = false;
            descend = false;
            ram = false;
        }

        public int bits() {
            return (jump ? 1 : 0) | (descend ? 2 : 0) | (ram ? 4 : 0);
        }
    }

    /** A waypoint route for server-driven vehicles. */
    public static final class Ai {
        public final List<Vec3d> points = new ArrayList<>();
        public int index;
        public double cruise = 0.7;
        public double arrive = 2.6;
        public boolean finished;
        public boolean stopAtEnd = true;
        public Runnable onFinished;
        public Runnable onWaypoint;
        public int stuckTicks;
        /** Waypoint indices where the car stops (and waits {@link #hold} ticks) before it drives on: robotaxi stations. */
        public final java.util.Set<Integer> stops = new java.util.HashSet<>();
        /** Waypoint indices that are corners: the car slows down for them. */
        public final java.util.Set<Integer> corners = new java.util.HashSet<>();
        /** While positive the car waits where it is (counts down; set it very high and clear it to wait for an event). */
        public int hold;
        /** While positive the car backs straight up (ramming recoil). */
        public int reverse;
    }

    public final VehicleSpec spec;
    private final Controls controls = new Controls();
    private Ai ai;

    // ---- simulation state (only meaningful on the side that simulates)
    public double speed;
    private double airSpeed;
    private double vy;
    private float moveYaw;
    private float steer;
    private boolean flying;
    private int flightTicks;
    private int hopCooldown;
    private float yawDelta;
    private double lastSpeed;
    private double lastVisFwd;
    private int airTicks;

    // ---- visual state (every side)
    public float wheelSpin;
    public float prevWheelSpin;
    public float steerAngle;        // -1..1, + = left
    public float prevSteerAngle;
    public float visPitch;
    public float prevVisPitch;
    public float visRoll;
    public float prevVisRoll;
    public float visualY;           // negative right after a step up: the body is drawn lower and rises
    public float prevVisualY;
    public boolean localThrust;
    public int spawnAge;

    // ---- interpolation for vehicles this side does not simulate
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private float lerpYaw;
    private float lerpPitch;
    private int lerpSteps;

    private int unoccupiedTicks;
    public int serverControlBits;

    public VehicleEntity(EntityType<?> type, World world) {
        super(type, world);
        this.spec = VehicleSpecs.of(type);
        this.setStepHeight(spec.stepHeight);
        this.setNoGravity(true);          // we apply gravity ourselves; also keeps the server's "floating vehicle" kick away
        this.setInvulnerable(true);
    }

    // ------------------------------------------------------------------------------------------------------ data
    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(MODE, (byte) MODE_DRIVEN);
        dataTracker.startTracking(FLAGS, 0);
        dataTracker.startTracking(OWNER, Optional.empty());
    }

    public int getMode() {
        return dataTracker.get(MODE);
    }

    public void setMode(int mode) {
        dataTracker.set(MODE, (byte) mode);
    }

    public int flags() {
        return dataTracker.get(FLAGS);
    }

    public boolean hasFlag(int f) {
        return (flags() & f) != 0;
    }

    public void setFlag(int f, boolean on) {
        int cur = flags();
        int next = on ? cur | f : cur & ~f;
        if (next != cur) {
            dataTracker.set(FLAGS, next);
        }
    }

    public UUID getOwner() {
        return dataTracker.get(OWNER).orElse(null);
    }

    public void setOwner(UUID owner) {
        dataTracker.set(OWNER, Optional.ofNullable(owner));
    }

    public Ai ai() {
        return ai;
    }

    public void setAi(Ai ai) {
        this.ai = ai;
        if (ai != null) {
            setMode(MODE_AI);
        }
    }

    public boolean isFlying() {
        return flying || hasFlag(F_FLYING);
    }

    /** The shift key must not throw a pilot out of the sky. */
    public boolean blocksDismount() {
        return spec.canFly && isFlying() && !isOnGround();
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        setMode(nbt.getByte("Mode"));
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putByte("Mode", (byte) getMode());
    }

    /** Vehicles are rebuilt by the showroom logic; nothing is stored in chunks. */
    @Override
    public boolean shouldSave() {
        return false;
    }

    // ------------------------------------------------------------------------------------------------------ vanilla hooks
    @Override
    public boolean canHit() {
        return !isRemoved();
    }

    @Override
    public boolean isCollidable() {
        return getMode() == MODE_DISPLAY;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    /** Left-click (a punch): a parked or driven car pops straight back into the player's inventory as its item. */
    @Override
    public boolean handleAttack(Entity attacker) {
        if (!getWorld().isClient && attacker instanceof ServerPlayerEntity sp && getMode() == MODE_DRIVEN) {
            net.minecraft.item.Item item = com.anant.teslatycoon.registry.ModItems.SPAWNERS.get(spec);
            if (item != null) {
                removeAllPassengers();
                sp.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(item));
                getWorld().playSound(null, getX(), getY(), getZ(), net.minecraft.sound.SoundEvents.ENTITY_ITEM_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 0.8f);
                if (getWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.POOF, getX(), getY() + 0.8, getZ(), 18, 0.8, 0.4, 0.8, 0.02);
                }
                discard();
            }
        }
        return true;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengerList().isEmpty() && getMode() != MODE_DISPLAY;
    }

    @Override
    public LivingEntity getControllingPassenger() {
        Entity first = getFirstPassenger();
        return first instanceof PlayerEntity p && getMode() == MODE_DRIVEN ? p : null;
    }

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (player.shouldCancelInteraction()) {
            return ActionResult.PASS;
        }
        int mode = getMode();
        if (mode == MODE_DISPLAY) {
            if (!getWorld().isClient && hook != null && player instanceof ServerPlayerEntity sp) {
                hook.interactDisplay(sp, this);
            }
            return ActionResult.success(getWorld().isClient);
        }
        if (mode != MODE_DRIVEN) {
            return ActionResult.PASS;
        }
        if (!getWorld().isClient) {
            return player.startRiding(this) ? ActionResult.CONSUME : ActionResult.PASS;
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps, boolean interpolate) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYaw = yaw;
        lerpPitch = pitch;
        lerpSteps = Math.max(2, interpolationSteps);
    }

    // ------------------------------------------------------------------------------------------------------ passengers
    private Vec3d local(double x, double y, double z) {
        double pr = Math.toRadians(getPitch());
        double cy = Math.cos(pr);
        double sy = Math.sin(pr);
        double y1 = y * cy - z * sy;           // nose-down pitch tips the cabin forward
        double z1 = y * sy + z * cy;
        double yr = Math.toRadians(getYaw());
        double c = Math.cos(yr);
        double s = Math.sin(yr);
        return new Vec3d(x * c - z1 * s, y1, x * s + z1 * c);
    }

    @Override
    protected void updatePassengerPosition(Entity passenger, Entity.PositionUpdater positionUpdater) {
        if (!hasPassenger(passenger)) {
            return;
        }
        Vec3d o = local(spec.seatX, spec.seatFeetY, spec.seatZ);
        positionUpdater.accept(passenger, getX() + o.x, getY() + o.y, getZ() + o.z);
        if (passenger == getControllingPassenger() && !flying) {
            // the cabin turns, so does the camera: exactly what vanilla boats do (prevYaw is left alone on purpose, the
            // renderer interpolates the delta across the tick)
            passenger.setYaw(passenger.getYaw() + yawDelta);
            passenger.setHeadYaw(passenger.getHeadYaw() + yawDelta);
        }
        copyEntityData(passenger);
    }

    private void copyEntityData(Entity entity) {
        entity.setBodyYaw(getYaw());
        float f = MathHelper.wrapDegrees(entity.getYaw() - getYaw());
        float g = flying ? f : MathHelper.clamp(f, -118.0F, 118.0F);
        entity.prevYaw += g - f;
        entity.setYaw(entity.getYaw() + g - f);
        entity.setHeadYaw(entity.getYaw());
    }

    /** The collision box is tight (1.6 x 1.4) but the car is 4 to 6 blocks long: the crosshair still has to find it. */
    @Override
    public float getTargetingMargin() {
        return Math.max(0f, spec.meta.length * 0.5f - spec.hitW * 0.5f) * 0.85f;
    }

    @Override
    public void onPassengerLookAround(Entity passenger) {
        copyEntityData(passenger);
    }

    @Override
    public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        double half = spec.hitW * 0.5 + 0.9;
        for (int i = 0; i < 8; i++) {
            double side = (i % 2 == 0) ? 1 : -1;
            double dist = half + (i / 2) * 1.1;
            Vec3d off = local(side * dist, 0, 0);
            double x = getX() + off.x;
            double z = getZ() + off.z;
            BlockPos at = BlockPos.ofFloored(x, getBoundingBox().minY + 0.5, z);
            List<Vec3d> options = new ArrayList<>(2);
            double h = getWorld().getDismountHeight(at);
            if (Dismounting.canDismountInBlock(h)) {
                options.add(new Vec3d(x, at.getY() + h, z));
            }
            double h2 = getWorld().getDismountHeight(at.down());
            if (Dismounting.canDismountInBlock(h2)) {
                options.add(new Vec3d(x, at.down().getY() + h2, z));
            }
            for (EntityPose pose : passenger.getPoses()) {
                for (Vec3d v : options) {
                    if (Dismounting.canPlaceEntityAt(getWorld(), v, passenger, pose)) {
                        passenger.setPose(pose);
                        return v;
                    }
                }
            }
        }
        return new Vec3d(getX(), getBoundingBox().maxY + 0.1, getZ());
    }

    // ------------------------------------------------------------------------------------------------------ tick
    @Override
    public void tick() {
        super.tick();
        World w = getWorld();
        spawnAge++;
        if (getMode() == MODE_DISPLAY) {
            setVelocity(Vec3d.ZERO);
            return;
        }
        prevWheelSpin = wheelSpin;
        prevSteerAngle = steerAngle;
        prevVisPitch = visPitch;
        prevVisRoll = visRoll;
        prevVisualY = visualY;
        float yawBefore = getYaw();
        updateLerp();
        if (isLogicalSideForUpdatingMovement()) {
            if (w.isClient) {
                if (bridge != null && getControllingPassenger() != null) {
                    bridge.poll(this, controls);
                } else {
                    controls.clear();
                }
            } else if (testControls != null) {
                controls.throttle = testControls.throttle;
                controls.steer = testControls.steer;
                controls.jump = testControls.jump;
                controls.descend = testControls.descend;
                controls.ram = testControls.ram;
            } else if (ai != null) {
                aiControls(controls);
            } else {
                controls.clear();
            }
            stepPhysics(controls);
            if (w.isClient && bridge != null && getControllingPassenger() != null) {
                bridge.sync(this, controls);
            }
        } else {
            setVelocity(Vec3d.ZERO);
        }
        yawDelta = MathHelper.wrapDegrees(getYaw() - yawBefore);
        animate();
        if (w.isClient) {
            clientTick();
        } else {
            serverTick();
        }
    }

    private void updateLerp() {
        if (isLogicalSideForUpdatingMovement()) {
            lerpSteps = 0;
            updateTrackedPosition(getX(), getY(), getZ());
        }
        if (lerpSteps > 0) {
            double d = getX() + (lerpX - getX()) / lerpSteps;
            double e = getY() + (lerpY - getY()) / lerpSteps;
            double f = getZ() + (lerpZ - getZ()) / lerpSteps;
            double g = MathHelper.wrapDegrees(lerpYaw - getYaw());
            setYaw(getYaw() + (float) g / lerpSteps);
            setPitch(getPitch() + (lerpPitch - getPitch()) / lerpSteps);
            lerpSteps--;
            setPosition(d, e, f);
            setRotation(getYaw(), getPitch());
        }
    }

    // ------------------------------------------------------------------------------------------------------ physics
    private double groundTop() {
        double v = spec.maxSpeed;
        if (hasFlag(F_RAM)) {
            v *= 1.28;
        }
        if (hasFlag(F_PUNCTURED)) {
            v *= 0.38;
        }
        return v;
    }

    private void stepPhysics(Controls c) {
        boolean disabled = hasFlag(F_DISABLED);
        float throttle = disabled ? 0 : c.throttle;
        float steerIn = disabled ? 0 : c.steer;
        if (hasFlag(F_JAMMED)) {
            steerIn += 0.6f * MathHelper.sin(age * 0.21f) + 0.25f * MathHelper.sin(age * 0.53f);
        }
        if (spec.canFly) {
            boolean up = c.jump && !disabled;
            if (up && !flying) {
                flying = true;
                flightTicks = 0;
                vy = Math.max(vy, 0.2);
                airSpeed = Math.max(0, speed);
                getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.NEUTRAL, 1.4f, 0.6f);
            }
            if (flying) {
                stepFlight(c, throttle, steerIn, up);
                return;
            }
        }
        stepGround(c, throttle, steerIn, disabled);
    }

    private void stepGround(Controls c, float throttle, float steerIn, boolean disabled) {
        double top = groundTop();
        double v = speed;
        // longitudinal: momentum builds with a soft curve, braking is firm, coasting loses a little to rolling resistance
        if (throttle > 0.01f) {
            if (v >= -0.02) {
                v += spec.accel * (1.0 - Math.pow(Math.max(0, v) / top, 1.35)) * throttle;
            } else {
                v += spec.brake * 1.3;
            }
        } else if (throttle < -0.01f) {
            if (v > 0.02) {
                v -= spec.brake;
            } else {
                double rev = top * spec.reverseFactor;
                v -= spec.accel * 0.8 * (1.0 - Math.pow(Math.max(0, -v) / rev, 1.35));
            }
        } else {
            double drag = 0.0045 + 0.010 * (v * v) / (top * top);
            v = v > 0 ? Math.max(0, v - drag) : Math.min(0, v + drag);
        }
        if (v > top) {
            v -= (v - top) * 0.18;
        }
        if (disabled) {
            v *= 0.94;
        }

        // steering: smoothed input, and the car only turns while it moves
        float rate = (Math.signum(steerIn) == Math.signum(steer) && Math.abs(steerIn) > Math.abs(steer)) ? 0.17f : 0.27f;
        steer += MathHelper.clamp(steerIn - steer, -rate, rate);
        double av = Math.abs(v);
        double turnFactor = MathHelper.clamp(av / 0.2, 0.0, 1.0) * (1.0 - 0.52 * MathHelper.clamp(av / top, 0.0, 1.0));
        float yawRate = (float) (steer * spec.maxYawDeg * turnFactor) * (v < -0.01 ? -1f : 1f);
        setYaw(getYaw() - yawRate);                      // A (steer > 0) turns left = yaw decreasing

        // the direction of travel follows the heading with a little lag (grip), which is what gives it weight
        float diff = MathHelper.wrapDegrees(getYaw() - moveYaw);
        moveYaw += diff * (float) MathHelper.clamp(0.62 - 0.18 * (av / top), 0.35, 0.7);
        if (av < 0.03) {
            moveYaw = getYaw();
        }
        double ry = Math.toRadians(moveYaw);

        // vertical
        boolean was = isOnGround();
        hopCooldown = Math.max(0, hopCooldown - 1);
        if (was) {
            vy = -0.1;
            if (c.jump && hasFlag(F_HYDRAULICS) && hopCooldown == 0 && !disabled) {
                vy = 0.56;
                hopCooldown = 14;
                getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.NEUTRAL, 0.9f, 1.5f);
            }
        } else {
            vy = (vy - 0.08) * 0.98;
        }

        double y0 = getY();
        double x0 = getX();
        double z0 = getZ();
        move(MovementType.SELF, new Vec3d(-Math.sin(ry) * v, vy, Math.cos(ry) * v));

        // collisions bleed speed instead of stopping dead
        double moved = Math.hypot(getX() - x0, getZ() - z0);
        if (horizontalCollision && moved < av * 0.6) {
            double keep = Math.max(0.0, moved) * Math.signum(v);
            if (av > 0.5) {
                getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.NEUTRAL, 0.5f, 1.4f);
            }
            v = keep * 0.5;
        }
        if (verticalCollision && vy > 0) {
            vy = 0;
        }
        // keep the wheels on the ground when driving down a slope or off a curb of up to a block
        if (was && !isOnGround() && vy <= 0.0) {
            Vec3d p = getPos();
            move(MovementType.SELF, new Vec3d(0, -0.9, 0));
            if (!isOnGround()) {
                setPosition(p);
                setOnGround(false);
            }
        }
        double dy = getY() - y0;
        if (dy > 0.22 && dy < 1.4 && isOnGround() && was) {
            visualY -= (float) dy;                       // a step up: the camera and body rise smoothly instead of snapping
        } else if (dy < -0.22 && dy > -1.4 && isOnGround() && was) {
            visualY -= (float) dy;
        }
        speed = v;
        lastSpeed = v;
        airSpeed = 0;
        setPitch(0);
        setVelocity(Vec3d.ZERO);
    }

    private void stepFlight(Controls c, float throttle, float steerIn, boolean thrust) {
        flightTicks++;
        LivingEntity pilot = getControllingPassenger();
        float lookYaw = pilot != null ? pilot.getYaw() : getYaw();
        float lookPitch = pilot != null ? pilot.getPitch() : 0f;
        double top = spec.flySpeed;
        // forward air speed: W / S, otherwise a slow glide
        if (throttle > 0.01f) {
            airSpeed += 0.045 * (1.0 - airSpeed / top) * throttle;
        } else if (throttle < -0.01f) {
            airSpeed = Math.max(0.0, airSpeed - 0.05);
        } else {
            airSpeed *= 0.988;
        }
        if (thrust) {
            airSpeed += 0.012 * (1.0 - airSpeed / top);
        }
        airSpeed = MathHelper.clamp(airSpeed, 0.0, top);
        // yaw: the mouse points the nose (limited rate), A / D add banked turns
        float turn = MathHelper.clamp(MathHelper.wrapDegrees(lookYaw - getYaw()) * 0.13f, -4.2f, 4.2f) - steerIn * 2.4f;
        setYaw(getYaw() + turn);
        // pitch: the look direction tilts the nose, strongly only while moving
        double authority = MathHelper.clamp(airSpeed / 0.45, 0.3, 1.0);
        float wantPitch = (float) (MathHelper.clamp(lookPitch, -52f, 52f) * authority);
        setPitch(getPitch() + (wantPitch - getPitch()) * 0.15f);
        // vertical: SPACE = thrusters (climb), CTRL = descend, nothing = hover
        if (thrust) {
            vy = Math.min(0.62, vy + 0.05);
        } else if (c.descend) {
            vy = Math.max(-0.55, vy - 0.045);
        } else {
            vy *= 0.88;
            vy += Math.sin(age * 0.17) * 0.0045;       // a faint hover bob
        }
        Vec3d dir = Vec3d.fromPolar(getPitch(), getYaw()).multiply(airSpeed);
        double x0 = getX();
        double z0 = getZ();
        move(MovementType.SELF, new Vec3d(dir.x, dir.y + vy, dir.z));
        double moved = Math.hypot(getX() - x0, getZ() - z0);
        if (horizontalCollision && moved < airSpeed * 0.5) {
            airSpeed *= 0.35;
        }
        if (verticalCollision) {
            if (vy > 0) {
                vy = 0;
            }
        }
        speed = airSpeed * Math.cos(Math.toRadians(getPitch()));
        moveYaw = getYaw();
        if (isOnGround() && !thrust && flightTicks > 8 && vy <= 0.02) {
            flying = false;
            vy = 0;
            speed = Math.min(speed, groundTop());
            setPitch(0);
        }
        setVelocity(Vec3d.ZERO);
    }

    // ------------------------------------------------------------------------------------------------------ AI driver
    private void aiControls(Controls c) {
        c.clear();
        if (ai == null || ai.finished || ai.points.isEmpty() || hasFlag(F_DISABLED)) {
            return;
        }
        if (ai.reverse > 0) {
            ai.reverse--;
            c.throttle = -1f;
            return;
        }
        if (ai.hold > 0) {
            ai.hold--;
            c.throttle = speed > 0.03 ? -1f : 0f;
            return;
        }
        Vec3d target = ai.points.get(Math.min(ai.index, ai.points.size() - 1));
        double dx = target.x - getX();
        double dz = target.z - getZ();
        double dist = Math.hypot(dx, dz);
        boolean last = ai.index >= ai.points.size() - 1;
        boolean stopHere = !last && ai.stops.contains(ai.index);
        boolean corner = ai.corners.contains(ai.index);
        if (stopHere) {
            if (dist < 1.8 && Math.abs(speed) < 0.08) {
                ai.index++;
                ai.hold = 4;
                if (ai.onWaypoint != null) {
                    ai.onWaypoint.run();
                }
                return;
            }
        } else if (dist < (last ? Math.max(1.4, ai.arrive * 0.6) : (corner ? ai.arrive + 0.8 : ai.arrive))) {
            if (last) {
                if (!ai.stopAtEnd || Math.abs(speed) < 0.08) {
                    ai.finished = true;
                    if (ai.onFinished != null) {
                        ai.onFinished.run();
                    }
                    return;
                }
            } else {
                ai.index++;
                if (ai.onWaypoint != null) {
                    ai.onWaypoint.run();
                }
                return;
            }
        }
        float want = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float err = MathHelper.wrapDegrees(want - getYaw());
        c.steer = MathHelper.clamp(-err / 18f, -1f, 1f);
        double v = ai.cruise * (1.0 - 0.7 * MathHelper.clamp(Math.abs(err) / 70f, 0f, 1f));
        if ((last && ai.stopAtEnd) || stopHere) {
            v = Math.min(v, Math.max(0.08, dist * 0.09));
        }
        if (corner) {
            v = Math.min(v, 0.34);
        }
        c.throttle = speed < v - 0.02 ? 1f : (speed > v + 0.04 ? -0.6f : 0f);
        if (Math.abs(speed) < 0.04) {
            ai.stuckTicks++;
            if (ai.stuckTicks > 80) {          // wedged against something: skip the waypoint
                ai.stuckTicks = 0;
                if (!last) {
                    ai.index++;
                }
            }
        } else {
            ai.stuckTicks = 0;
        }
    }

    // ------------------------------------------------------------------------------------------------------ visuals
    private void animate() {
        double dx = getX() - prevX;
        double dz = getZ() - prevZ;
        double yr = Math.toRadians(getYaw());
        double fwd = -dx * Math.sin(yr) + dz * Math.cos(yr);
        wheelSpin += (float) (fwd / Math.max(0.2f, spec.meta.wheelRadius));
        float dyaw = MathHelper.wrapDegrees(getYaw() - prevYaw);
        float target;
        if (isLogicalSideForUpdatingMovement() && !flying) {
            target = steer;
        } else if (Math.abs(fwd) > 0.02) {
            target = MathHelper.clamp(-dyaw * (fwd > 0 ? 1 : -1) / (spec.maxYawDeg * 0.5f), -1f, 1f);
        } else {
            target = 0;
        }
        steerAngle += (target - steerAngle) * 0.45f;
        // cosmetic body motion: dive when braking, squat when accelerating, lean in corners
        double accel = fwd - (lastVisFwd);
        lastVisFwd = fwd;
        float tp = flying ? 0f : (float) MathHelper.clamp(-accel * 42.0, -2.4, 2.4);
        visPitch += (tp - visPitch) * 0.22f;
        float tr = (float) (-steerAngle * MathHelper.clamp(Math.abs(fwd) / 0.5, 0, 1) * 3.4);
        if (flying) {
            tr = -steerAngle * 14f;
        }
        visRoll += (tr - visRoll) * 0.2f;
        visualY *= 0.7f;
        if (Math.abs(visualY) < 0.003f) {
            visualY = 0f;
        }
    }

    private void clientTick() {
        World w = getWorld();
        boolean thrusting = controllingIsLocal() ? localThrust : hasFlag(F_THRUST);
        if (thrusting && spec.canFly && spec.meta.thrusters != null) {
            for (float[] t : spec.meta.thrusters) {
                Vec3d p = local(t[0], t[1], t[2]);
                Vec3d back = Vec3d.fromPolar(getPitch(), getYaw()).multiply(-1);
                for (int i = 0; i < 3; i++) {
                    double s = 0.18;
                    w.addParticle(ParticleTypes.SOUL_FIRE_FLAME, getX() + p.x, getY() + p.y, getZ() + p.z,
                            back.x * 0.42 + (random.nextDouble() - 0.5) * s, back.y * 0.42 + (random.nextDouble() - 0.5) * s,
                            back.z * 0.42 + (random.nextDouble() - 0.5) * s);
                }
                w.addParticle(new DustParticleEffect(new Vector3f(0.25f, 0.62f, 1.0f), 1.5f), getX() + p.x, getY() + p.y, getZ() + p.z,
                        back.x * 0.3, back.y * 0.3, back.z * 0.3);
                if (age % 2 == 0) {
                    w.addParticle(ParticleTypes.ELECTRIC_SPARK, getX() + p.x, getY() + p.y, getZ() + p.z,
                            back.x * 0.6 + (random.nextDouble() - 0.5) * 0.3, back.y * 0.6, back.z * 0.6 + (random.nextDouble() - 0.5) * 0.3);
                }
            }
        }
        if (hasFlag(F_DISABLED) && age % 3 == 0) {
            w.addParticle(ParticleTypes.ELECTRIC_SPARK, getX() + (random.nextDouble() - 0.5) * spec.meta.width, getY() + 1.0 + random.nextDouble() * 0.8,
                    getZ() + (random.nextDouble() - 0.5) * spec.meta.length, 0, 0.1, 0);
            if (age % 9 == 0) {
                w.addParticle(ParticleTypes.LARGE_SMOKE, getX(), getY() + spec.meta.height, getZ(), 0, 0.08, 0);
            }
        }
        if (hasFlag(F_PUNCTURED) && age % 4 == 0) {
            w.addParticle(ParticleTypes.CLOUD, getX() + (random.nextBoolean() ? 0.9 : -0.9), getY() + 0.2, getZ() + 1.0, 0, 0.02, 0);
        }
    }

    private boolean controllingIsLocal() {
        return getControllingPassenger() != null && getControllingPassenger() instanceof PlayerEntity p && p.isMainPlayer();
    }

    private void serverTick() {
        if (getMode() == MODE_DRIVEN) {
            airTicks = isOnGround() ? 0 : airTicks + 1;
            boolean pilot = getControllingPassenger() != null;
            setFlag(F_THRUST, spec.canFly && pilot && (serverControlBits & 1) != 0);
            setFlag(F_FLYING, spec.canFly && pilot && airTicks > 6);
            if (getFirstPassenger() == null) {
                if (++unoccupiedTicks > 20 * 60 * 4) {
                    discard();
                    return;
                }
            } else {
                unoccupiedTicks = 0;
            }
        }
        if (hook != null) {
            hook.tick(this);
        }
    }

    /** Called by the control packet handler on the server. */
    public void onServerControls(int bits) {
        serverControlBits = bits;
        setFlag(F_RAM, spec.heavy && (bits & 4) != 0 && ramUnlocked);
    }

    public boolean ramUnlocked;

    /** When set, the simulating side uses these instead of the keyboard / AI (game tests and scripted scenes). */
    public Controls testControls;

    public boolean flyingNow() {
        return flying;
    }

    public Controls controlsView() {
        return controls;
    }

    // ------------------------------------------------------------------------------------------------------ helpers for the renderer / game rules
    public float lerpedYaw(float t) {
        return prevYaw + MathHelper.wrapDegrees(getYaw() - prevYaw) * t;
    }

    public float lerpedPitch(float t) {
        return MathHelper.lerp(t, prevPitch, getPitch());
    }

    public Vec3d worldOffset(double x, double y, double z) {
        return local(x, y, z);
    }

    /** Horizontal speed in blocks per tick measured from real movement (works on any side). */
    public double measuredSpeed() {
        return Math.hypot(getX() - prevX, getZ() - prevZ);
    }

    public void stopHard() {
        speed = 0;
        airSpeed = 0;
    }
}
