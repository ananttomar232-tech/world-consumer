package com.anant.teslatycoon.rival;

import static com.anant.teslatycoon.city.CityLayout.*;

import com.anant.teslatycoon.city.Buildings;
import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.npc.ShowroomDirector;
import com.anant.teslatycoon.npc.SuitedNpcEntity;
import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpec;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Seth Gotiya's own dealership across the boulevard: the building grows with the player's level (lights, neon, gold, spotlights),
 * his petrol cars stand on lit spots behind the glass, and rich customers of his own walk in. When one of them is waiting at a car,
 * Gotiya walks over and shows it off (RivalDirector moves him); some of them buy.
 */
public final class RivalShowroom {
    public static final String CAR_TAG = "tt_rival_car";

    private static final class Cust {
        UUID npc;
        int spot;
        int stage;          // 0 walking in, 1 waiting at the car, 2 Gotiya on his way, 3 being shown the car, 4 leaving
        int t;
        boolean west;
    }

    private static final class Session {
        final List<UUID> cars = new ArrayList<>();
        final List<Cust> customers = new ArrayList<>();
        long nextCustomer = 400;
        boolean building;
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private RivalShowroom() {}

    private static Session session(Company c) {
        return SESSIONS.computeIfAbsent(c.owner, k -> new Session());
    }

    /** The version of his dealership that should be standing now. */
    private static int target(Company c) {
        return c.level * 10 + (c.rivalDestroyed ? 1 : (c.gotiyaPartner ? 2 : 0));
    }

    /** The petrol cars on show, by spot. Once he is the player's partner a Cybertruck joins them. */
    private static VehicleSpec carFor(Company c, int spot) {
        if (c.gotiyaPartner && spot == 3) {
            return VehicleSpecs.CYBERTRUCK;
        }
        return switch (spot) {
            case 0 -> VehicleSpecs.HATCH;
            case 1 -> VehicleSpecs.MUSTANG;
            case 2 -> VehicleSpecs.LAMBO;
            default -> VehicleSpecs.MUSTANG;
        };
    }

    private static int carCount(Company c) {
        if (c.rivalDestroyed) {
            return 0;
        }
        return c.gotiyaPartner ? Buildings.RIVAL_SPOTS.length : Buildings.rivalCars(c.level);
    }

    // ------------------------------------------------------------------------------------------------------ tick
    public static void tick(ServerWorld w, Company c, ServerPlayerEntity p) {
        Session s = session(c);
        double dx = p.getX() - c.origin.getX();
        double dz = p.getZ() - c.origin.getZ();
        if (dx * dx + dz * dz > 200 * 200 || p.getWorld() != w) {
            return;
        }
        // the building follows the player's level (his "upgrade")
        if (!s.building && c.rivalBuilt != target(c) && !CityBuilder.busyUrgent() && RivalDirector.alarmStage(c) == 0) {
            boolean upgrade = c.rivalBuilt != 0 && c.rivalBuilt / 10 < c.level && !c.rivalDestroyed && !c.gotiyaPartner;
            int want = target(c);
            s.building = true;
            CityBuilder.edit(w, c, Buildings.rivalShowroom(c.level, c.rivalDestroyed, c.gotiyaPartner), !c.powerOut, () -> {
                c.rivalBuilt = want;
                s.building = false;
                layoutCars(w, c);
            });
            if (upgrade) {
                RivalDirector.onRivalUpgrade(w, c, p);
            }
        }
        if (c.cityTick % 100 == 31 && !s.building) {
            int alive = 0;
            for (UUID id : s.cars) {
                Entity e = w.getEntity(id);
                if (e != null && e.isAlive()) {
                    alive++;
                }
            }
            if (alive != carCount(c)) {
                layoutCars(w, c);
            }
        }
        // his customers (none in a ruin, none during the finale)
        if (!c.rivalDestroyed && RivalDirector.alarmStage(c) == 0 && c.cityTick >= s.nextCustomer && s.customers.size() < 3) {
            spawnCustomer(w, c, s);
            s.nextCustomer = c.cityTick + 260 + w.random.nextInt(200);
        }
        tickCustomers(w, c, s, p);
    }

    /** Puts his petrol cars (one per spot his level allows) on the lit spots, nose to the glass. */
    public static void layoutCars(ServerWorld w, Company c) {
        Session s = session(c);
        Box box = new Box(c.origin.getX() + GX0, c.origin.getY() - 2, c.origin.getZ() + GZ0, c.origin.getX() + GX1 + 1, c.origin.getY() + 12, c.origin.getZ() + GZ1 + 1);
        for (VehicleEntity v : w.getEntitiesByClass(VehicleEntity.class, box, e -> e.getCommandTags().contains(CAR_TAG))) {
            v.discard();
        }
        s.cars.clear();
        int n = carCount(c);
        for (int i = 0; i < n; i++) {
            double[] sp = Buildings.RIVAL_SPOTS[i];
            VehicleEntity v = new VehicleEntity(ModEntities.vehicle(carFor(c, i)), w);
            v.setMode(VehicleEntity.MODE_DISPLAY);
            v.addCommandTag(CAR_TAG);
            Vec3d at = CityBuilder.vec(c, sp[0], 1.0, sp[1]);
            v.refreshPositionAndAngles(at.x, at.y, at.z, 180f + (i % 2 == 0 ? -12f : 12f), 0f);
            w.spawnEntity(v);
            s.cars.add(v.getUuid());
        }
    }

    /** The player touched one of his cars. */
    public static void touched(ServerPlayerEntity player, VehicleEntity car) {
        Company c = Tycoon.companyOf(player);
        if (c != null && c.gotiyaPartner) {
            player.sendMessage(Text.literal("Partner's stock - Seth says: 'Le ja yaar, test drive free hai!'").formatted(Formatting.GOLD), true);
            ShowroomDirector.testDrive(player, car, c);
            return;
        }
        RivalDirector.sayVoiced(GotiyaLines.pick("touch_car"));
    }

    // ------------------------------------------------------------------------------------------------------ customers
    private static Vec3d stand(Company c, int spot) {
        double[] sp = Buildings.RIVAL_SPOTS[spot];
        double side = sp[0] < -40 ? 2.2 : -2.2;                         // on the lane side of the car
        return CityBuilder.vec(c, sp[0] + side, 1.0, sp[1] - 1.2);
    }

    private static void spawnCustomer(ServerWorld w, Company c, Session s) {
        int cars = carCount(c);
        if (cars == 0) {
            return;
        }
        Cust cu = new Cust();
        cu.spot = w.random.nextInt(cars);
        for (Cust o : s.customers) {
            if (o.spot == cu.spot) {
                return;                                                   // one customer per car
            }
        }
        cu.west = w.random.nextBoolean();
        Vec3d from = CityBuilder.vec(c, cu.west ? -80.5 : -4.5, 1.0, 25.5);
        SuitedNpcEntity n = ShowroomDirector.spawnNpc(w, from, cu.west ? -90f : 90f, SuitedNpcEntity.CUSTOMER, 1 + w.random.nextInt(3), "Gotiya's Customer");
        Vec3d st = stand(c, cu.spot);
        n.walk(List.of(CityBuilder.vec(c, -40.0, 1.0, 25.5), CityBuilder.vec(c, -40.0, 1.0, 31.5), CityBuilder.vec(c, -40.0, 1.0, 40.0),
                CityBuilder.vec(c, -40.0, 1.0, 44.5), new Vec3d(st.x, st.y, c.origin.getZ() + 44.5), new Vec3d(st.x, st.y, st.z)), 0.15, null);
        cu.npc = n.getUuid();
        s.customers.add(cu);
    }

    private static void tickCustomers(ServerWorld w, Company c, Session s, ServerPlayerEntity p) {
        Iterator<Cust> it = s.customers.iterator();
        while (it.hasNext()) {
            Cust cu = it.next();
            Entity e = w.getEntity(cu.npc);
            if (!(e instanceof SuitedNpcEntity npc) || !npc.isAlive()) {
                it.remove();
                continue;
            }
            cu.t++;
            if (c.rivalDestroyed || RivalDirector.alarmStage(c) != 0) {
                if (cu.stage < 4) {
                    leave(c, cu, npc);
                }
            }
            switch (cu.stage) {
                case 0 -> {
                    if (!npc.isWalking()) {
                        cu.stage = 1;
                        cu.t = 0;
                        double[] sp = Buildings.RIVAL_SPOTS[cu.spot];
                        Vec3d car = CityBuilder.vec(c, sp[0], 1.0, sp[1]);
                        npc.faceTowards((float) (MathHelper.atan2(-(car.x - npc.getX()), car.z - npc.getZ()) * 180.0 / Math.PI));
                    }
                }
                case 1 -> {
                    // ask the boss to come over; he comes when he is free
                    Vec3d st = stand(c, cu.spot);
                    Vec3d at = new Vec3d(st.x + (st.x < c.origin.getX() - 40 ? 1.3 : -1.3), st.y, st.z + 0.6);
                    if (RivalDirector.requestShow(w, c, at, npc.getPos())) {
                        cu.stage = 2;
                        cu.t = 0;
                    } else if (cu.t > 20 * 30) {
                        leave(c, cu, npc);
                    }
                }
                case 2 -> {
                    SuitedNpcEntity g = RivalDirector.gotiya(w, c);
                    if (g == null || cu.t > 20 * 40) {
                        RivalDirector.releaseShow(w, c);
                        leave(c, cu, npc);
                    } else if (g.squaredDistanceTo(npc) < 3.2 * 3.2 && !g.isWalking()) {
                        cu.stage = 3;
                        cu.t = 0;
                        g.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
                    }
                }
                case 3 -> {
                    if (cu.t % 30 == 0) {
                        SuitedNpcEntity g = RivalDirector.gotiya(w, c);
                        if (g != null) {
                            g.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
                            w.spawnParticles(ParticleTypes.NOTE, g.getX(), g.getY() + 2.3, g.getZ(), 1, 0.2, 0.1, 0.2, 0.5);
                        }
                    }
                    if (cu.t >= 20 * 7) {
                        boolean sold = w.random.nextInt(100) < 45;
                        if (sold) {
                            c.gotiyaSales++;
                            w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, npc.getX(), npc.getY() + 1.6, npc.getZ(), 16, 0.4, 0.4, 0.4, 0.02);
                            w.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1f);
                            w.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.NEUTRAL, 1f, 1.2f);
                            RivalDirector.onRivalSale(w, c, p, carFor(c, cu.spot));
                        } else {
                            w.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 1f);
                        }
                        RivalDirector.releaseShow(w, c);
                        leave(c, cu, npc);
                    }
                }
                default -> {
                    if (!npc.isWalking()) {
                        npc.discard();
                        it.remove();
                    }
                }
            }
        }
    }

    private static void leave(Company c, Cust cu, SuitedNpcEntity npc) {
        cu.stage = 4;
        npc.walk(List.of(new Vec3d(npc.getX(), npc.getY(), c.origin.getZ() + 44.5), CityBuilder.vec(c, -39.0, 1.0, 44.5), CityBuilder.vec(c, -39.0, 1.0, 40.0), CityBuilder.vec(c, -39.0, 1.0, 31.5),
                CityBuilder.vec(c, -39.0, 1.0, 25.5), CityBuilder.vec(c, cu.west ? -4.5 : -80.5, 1.0, 25.5)), 0.16, null);
    }

    /** Everything of his showroom that is an entity (the cars, his customers): gone, e.g. for the destruction. */
    public static void clearEntities(ServerWorld w, Company c) {
        Session s = session(c);
        for (UUID id : s.cars) {
            Entity e = w.getEntity(id);
            if (e != null) {
                e.discard();
            }
        }
        s.cars.clear();
        for (Cust cu : s.customers) {
            Entity e = w.getEntity(cu.npc);
            if (e != null) {
                e.discard();
            }
        }
        s.customers.clear();
    }

    public static List<VehicleEntity> cars(ServerWorld w, Company c) {
        List<VehicleEntity> out = new ArrayList<>();
        for (UUID id : session(c).cars) {
            if (w.getEntity(id) instanceof VehicleEntity v && v.isAlive()) {
                out.add(v);
            }
        }
        return out;
    }

    public static void reset(Company c) {
        SESSIONS.remove(c.owner);
    }
}
