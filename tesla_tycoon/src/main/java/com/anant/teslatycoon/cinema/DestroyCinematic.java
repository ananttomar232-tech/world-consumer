package com.anant.teslatycoon.cinema;

import static com.anant.teslatycoon.city.CityLayout.*;

import com.anant.teslatycoon.city.Buildings;
import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.npc.SuitedNpcEntity;
import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.rival.RivalDirector;
import com.anant.teslatycoon.rival.RivalShowroom;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * /destroy: the last task, fully automated as a 20 second cutscene. The camera leaves the player: the red Roadster lifts off the
 * showroom roof and flies across the boulevard, its EMP kills Gotiya's Monster Truck, Gotiya jumps out, and a chain of explosions
 * sweeps through his dealership and his petrol cars. The camera ends on him crying in the rubble; then the player is back where they
 * stood. (Say something kind to him afterwards...)
 */
public final class DestroyCinematic {
    private static final int LENGTH = 400;

    private static final class Scene {
        final UUID player;
        final Company c;
        final Vec3d returnPos;
        final float returnYaw;
        final float returnPitch;
        CameraEntity cam;
        VehicleEntity roadster;
        VehicleEntity truck;
        final List<VehicleEntity> cars = new ArrayList<>();
        int t;

        Scene(ServerPlayerEntity p, Company c) {
            this.player = p.getUuid();
            this.c = c;
            this.returnPos = p.getPos();
            this.returnYaw = p.getYaw();
            this.returnPitch = p.getPitch();
        }
    }

    private static final Map<UUID, Scene> RUNNING = new HashMap<>();

    private DestroyCinematic() {}

    public static boolean running(UUID player) {
        return RUNNING.containsKey(player);
    }

    /** Starts the cutscene. Returns a reason if it cannot run now, else null. */
    public static String start(ServerPlayerEntity p) {
        Company c = Tycoon.companyOf(p);
        if (c == null || !c.cityBuilt) {
            return "Start your business first: /tycoon start";
        }
        if (running(p.getUuid())) {
            return "The cutscene is already playing.";
        }
        if (c.rivalDestroyed) {
            return "Gotiya Petrol Motors is already a ruin. Maybe say something kind to him...";
        }
        if (c.gotiyaPartner) {
            return "Seth Gotiya is your partner now - you would be blowing up your own showroom!";
        }
        if (CityBuilder.busyUrgent()) {
            return "The city builders are busy, try /destroy again in a moment.";
        }
        ServerWorld w = Tycoon.worldOf(c);
        Scene s = new Scene(p, c);
        p.stopRiding();
        RivalDirector.stopStoryForCutscene(w, c);
        // the actors
        s.cam = new CameraEntity(ModEntities.CAMERA, w);
        Vec3d c0 = shotPos(c, 0);
        s.cam.refreshPositionAndAngles(c0.x, c0.y, c0.z, 0f, 0f);
        w.spawnEntity(s.cam);
        s.roadster = prop(w, VehicleSpecs.ROADSTER, CityBuilder.vec(c, PAD_X + 0.5, FLOOR_H * 5 + 1.0, HELI_Z + 0.5), 180f);
        s.truck = prop(w, VehicleSpecs.GOTIYA, CityBuilder.vec(c, -39.5, 1.0, 36.5), 180f);
        SuitedNpcEntity g = RivalDirector.gotiya(w, c);
        if (g != null) {
            g.walk(List.of(), 0.2, null);
            g.startRiding(s.truck, true);
        }
        s.cars.addAll(RivalShowroom.cars(w, c));
        // the player watches, unhurt and unseen
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, LENGTH + 40, 0, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, LENGTH + 40, 4, false, false));
        p.getAbilities().invulnerable = true;
        p.sendAbilitiesUpdate();
        title(p, "OPERATION: DIESEL DOWN", "The last task, on autopilot", Formatting.RED, Formatting.YELLOW, 60);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.AMBIENT, 0.8f, 1.4f);
        RUNNING.put(p.getUuid(), s);
        Tycoon.log("[cinema] /destroy cutscene starts");
        return null;
    }

    private static VehicleEntity prop(ServerWorld w, com.anant.teslatycoon.vehicle.VehicleSpec spec, Vec3d at, float yaw) {
        VehicleEntity v = new VehicleEntity(ModEntities.vehicle(spec), w);
        v.setMode(VehicleEntity.MODE_DISPLAY);
        v.setFlag(VehicleEntity.F_STILL, true);
        v.addCommandTag("tt_cinema");
        v.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f);
        w.spawnEntity(v);
        return v;
    }

    public static void tick(MinecraftServer server) {
        if (RUNNING.isEmpty()) {
            return;
        }
        Iterator<Scene> it = RUNNING.values().iterator();
        while (it.hasNext()) {
            Scene s = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(s.player);
            if (p == null) {
                cleanup(s, null);
                it.remove();
                continue;
            }
            try {
                step(p, s);
            } catch (RuntimeException e) {
                com.anant.teslatycoon.TeslaTycoon.LOGGER.error("cutscene failed", e);
                s.t = LENGTH;
            }
            if (s.t++ >= LENGTH) {
                finish(p, s);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ the script
    /** Where the camera is at tick t (and where it looks: see target()). */
    private static Vec3d shotPos(Company c, int t) {
        if (t < 90) {                                              // 1: behind the showroom, rising: the Roadster lifts off the roof
            double k = t / 90.0;
            return CityBuilder.vec(c, -30 + 6 * k, 44 - 8 * k, -52 + 14 * k);
        }
        if (t < 170) {                                             // 2: low on the boulevard: the Roadster flies over the truck
            double k = (t - 90) / 80.0;
            return CityBuilder.vec(c, -12 - 6 * k, 3.5 + 2 * k, 12 + 2 * k);
        }
        if (t < 320) {                                             // 3: a slow orbit round the dealership while it goes up
            double k = (t - 170) / 150.0;
            double a = Math.toRadians(-140 + 80 * k);
            return CityBuilder.vec(c, -40 + Math.cos(a) * 36, 13 - 4 * k, 42 + Math.sin(a) * 36);
        }
        double k = (t - 320) / 80.0;                               // 4: down to Gotiya in the rubble
        return CityBuilder.vec(c, -40.5 + 2 * k, 3.4 - 0.8 * k, 18.0 + 4.5 * k);
    }

    private static Vec3d target(Scene s, int t) {
        Company c = s.c;
        if (t < 170) {
            return s.roadster.getPos().add(0, 0.6, 0);
        }
        if (t < 320) {
            return CityBuilder.vec(c, -40, 3, 44);
        }
        SuitedNpcEntity g = RivalDirector.gotiya((ServerWorld) s.cam.getWorld(), c);
        return g != null ? g.getPos().add(0, 1.6, 0) : CityBuilder.vec(c, -40.5, 2.6, 28.5);
    }

    /** The Roadster's flight: up from the helipad, then south over the boulevard to hover above Gotiya's forecourt. */
    private static Vec3d roadsterPos(Company c, int t) {
        Vec3d pad = CityBuilder.vec(c, PAD_X + 0.5, FLOOR_H * 5 + 1.0, HELI_Z + 0.5);
        if (t < 40) {
            return pad.add(0, ease(t / 40.0) * 7, 0);
        }
        Vec3d top = pad.add(0, 7, 0);
        Vec3d hover = CityBuilder.vec(c, -40.5, 13, 24);
        double k = ease(Math.min(1.0, (t - 40) / 95.0));
        return top.lerp(hover, k);
    }

    private static double ease(double x) {
        return x * x * (3 - 2 * x);
    }

    private static void step(ServerPlayerEntity p, Scene s) {
        ServerWorld w = (ServerWorld) s.cam.getWorld();
        Company c = s.c;
        int t = s.t;
        if (t == 3) {
            p.setCameraEntity(s.cam);
        }
        // camera
        Vec3d at = shotPos(c, t);
        Vec3d look = target(s, t).subtract(at);
        float yaw = (float) (MathHelper.atan2(-look.x, look.z) * 180.0 / Math.PI);
        float pitch = (float) (-MathHelper.atan2(look.y, Math.hypot(look.x, look.z)) * 180.0 / Math.PI);
        s.cam.refreshPositionAndAngles(at.x, at.y, at.z, yaw, pitch);
        s.cam.setHeadYaw(yaw);
        // the Roadster
        if (t < 170) {
            Vec3d r = roadsterPos(c, t);
            Vec3d r2 = roadsterPos(c, t + 1);
            float ry = t < 40 ? 180f : (float) (MathHelper.atan2(-(r2.x - r.x), r2.z - r.z) * 180.0 / Math.PI);
            s.roadster.refreshPositionAndAngles(r.x, r.y, r.z, Float.isNaN(ry) ? 0f : ry, 0f);
            s.roadster.setFlag(VehicleEntity.F_THRUST, true);
            if (t % 3 == 0) {
                w.spawnParticles(ParticleTypes.FLAME, r.x, r.y + 0.4, r.z, 6, 0.4, 0.1, 0.4, 0.02);
            }
            if (t == 2 || t == 60) {
                w.playSound(null, r.x, r.y, r.z, SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.NEUTRAL, 3f, 0.5f);
            }
        }
        if (t == 15) {
            RivalDirector.sayKey(w, c, p, "cinematic");
        }
        // the EMP
        if (t == 140) {
            Vec3d r = s.roadster.getPos();
            w.playSound(null, r.x, r.y, r.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 4f, 0.5f);
            w.playSound(null, r.x, r.y, r.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 3f, 1.6f);
            for (int k = 0; k < 14; k++) {
                final int step = k;
                RivalDirector.later(k, () -> ring(w, r, 2.0 + step * 2.4));
            }
            bolt(w, s.truck.getPos());
            s.truck.setFlag(VehicleEntity.F_DISABLED, true);
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, s.truck.getX(), s.truck.getY() + 2, s.truck.getZ(), 120, 2.5, 2, 2.5, 0.3);
        }
        if (t == 155) {
            SuitedNpcEntity g = RivalDirector.gotiya(w, c);
            if (g != null) {
                g.stopRiding();
                Vec3d home = CityBuilder.vec(c, -40.5, 1.0, 28.5);
                g.refreshPositionAndAngles(home.x, home.y, home.z, 0f, 0f);
                g.faceTowards(0f);
                RivalDirector.setCrying(c, 20 * 60);
            }
        }
        // the destruction: a chain of explosions sweeping west to east through the dealership and the cars
        if (t >= 175 && t <= 290 && (t - 175) % 9 == 0) {
            int i = (t - 175) / 9;                                  // 0..12
            double x = GX0 + 2 + i * ((GX1 - GX0 - 4) / 12.0);
            double z = 36 + 9 * Math.sin(i * 1.7);
            Vec3d e = CityBuilder.vec(c, x, 3, z);
            boom(w, e, i % 3 == 0);
            for (Iterator<VehicleEntity> it = s.cars.iterator(); it.hasNext(); ) {
                VehicleEntity car = it.next();
                if (car.isAlive() && car.getX() < e.x + 3) {
                    boom(w, car.getPos().add(0, 1, 0), true);
                    debris(w, car.getPos().add(0, 1, 0), 6);
                    car.discard();
                    it.remove();
                }
            }
            if (s.truck.isAlive() && s.truck.getX() < e.x + 2) {
                boom(w, s.truck.getPos().add(0, 2, 0), true);
                debris(w, s.truck.getPos().add(0, 2, 0), 10);
                s.truck.discard();
            }
            if (i == 6) {
                // half way through, the building itself goes: the ruin replaces it
                c.rivalDestroyed = true;
                RivalShowroom.clearEntities(w, c);
                CityBuilder.edit(w, c, Buildings.rivalShowroom(c.level, true, false), !c.powerOut, () -> c.rivalBuilt = c.level * 10 + 1);
                debris(w, CityBuilder.vec(c, -40, 6, 40), 18);
            }
        }
        if (t == 300) {
            title(p, "GOTIYA MOTORS DESTROYED", "His Monster Truck, his petrol cars, his showroom: gone.", Formatting.GOLD, Formatting.WHITE, 70);
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 2f, 1f);
        }
        if (t == 335) {
            RivalDirector.sayKey(w, c, p, "destroyed");
        }
        if (t >= 300 && t % 25 == 0) {
            w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, s.cam.getX() - 2, s.cam.getY() + 6, s.cam.getZ() + 20, 6, 8, 2, 8, 0.01);
        }
    }

    private static void ring(ServerWorld w, Vec3d at, double r) {
        for (int i = 0; i < 40; i++) {
            double a = Math.PI * 2 * i / 40;
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x + Math.cos(a) * r, at.y, at.z + Math.sin(a) * r, 1, 0, 0.1, 0, 0);
        }
    }

    private static void bolt(ServerWorld w, Vec3d at) {
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
        if (bolt != null) {
            bolt.refreshPositionAfterTeleport(at.x, at.y, at.z);
            bolt.setCosmetic(true);
            w.spawnEntity(bolt);
        }
    }

    private static void boom(ServerWorld w, Vec3d at, boolean big) {
        w.spawnParticles(big ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, at.x, at.y, at.z, big ? 2 : 6, 1.5, 1.0, 1.5, 0.0);
        w.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 30, 2, 1.5, 2, 0.05);
        w.spawnParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 25, 1.5, 1, 1.5, 0.08);
        w.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, big ? 5f : 3f, 0.6f + w.random.nextFloat() * 0.3f);
    }

    /** Pieces flying out of the explosion (they land as blocks of rubble). */
    private static void debris(ServerWorld w, Vec3d at, int n) {
        BlockState[] kinds = {Blocks.RED_CONCRETE.getDefaultState(), Blocks.ORANGE_CONCRETE.getDefaultState(), Blocks.COAL_BLOCK.getDefaultState(),
                Blocks.GRAVEL.getDefaultState(), Blocks.BLACK_CONCRETE.getDefaultState()};
        for (int i = 0; i < n; i++) {
            FallingBlockEntity f = FallingBlockEntity.spawnFromBlock(w, BlockPos.ofFloored(at.x, at.y + 8, at.z), kinds[i % kinds.length]);
            f.dropItem = false;
            f.setVelocity((w.random.nextDouble() - 0.5) * 1.1, 0.5 + w.random.nextDouble() * 0.6, (w.random.nextDouble() - 0.5) * 1.1);
            f.setPosition(at.x, at.y, at.z);
        }
    }

    private static void title(ServerPlayerEntity p, String head, String sub, Formatting hf, Formatting sf, int stay) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, stay, 20));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(sub).formatted(sf)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(head).formatted(hf, Formatting.BOLD)));
    }

    // ------------------------------------------------------------------------------------------------------ the end
    private static void finish(ServerPlayerEntity p, Scene s) {
        Company c = s.c;
        ServerWorld w = (ServerWorld) s.cam.getWorld();
        p.setCameraEntity(p);
        p.teleport(w, s.returnPos.x, s.returnPos.y, s.returnPos.z, s.returnYaw, s.returnPitch);
        p.removeStatusEffect(StatusEffects.INVISIBILITY);
        p.removeStatusEffect(StatusEffects.RESISTANCE);
        if (!p.isCreative() && !p.isSpectator()) {
            p.getAbilities().invulnerable = false;
        }
        p.sendAbilitiesUpdate();
        cleanup(s, w);
        if (!c.rivalDestroyed) {
            c.rivalDestroyed = true;
            CityBuilder.edit(w, c, Buildings.rivalShowroom(c.level, true, false), !c.powerOut, () -> c.rivalBuilt = c.level * 10 + 1);
        }
        c.climaxWon = true;                                          // (and with his dealership gone no sabotage comes back)
        p.sendMessage(Text.literal("Seth Gotiya sits in the rubble of his dealership... Maybe say something kind to him in chat?").formatted(Formatting.GOLD), false);
        Tycoon.log("[cinema] /destroy cutscene finished");
        Tycoon.sync(p, c);
    }

    private static void cleanup(Scene s, ServerWorld w) {
        for (VehicleEntity v : new VehicleEntity[]{s.roadster, s.truck}) {
            if (v != null && v.isAlive()) {
                v.removeAllPassengers();
                v.discard();
            }
        }
        if (s.cam != null) {
            s.cam.discard();
        }
    }
}
