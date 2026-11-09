package com.anant.teslatycoon.npc;

import com.anant.teslatycoon.city.Buildings;
import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.city.CityRoutes;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import net.minecraft.util.math.Vec3d;

/**
 * The Robotaxi Terminal of showroom level 4: four autonomous Cybercabs drive closed loops through the whole city (two on the
 * highway ring, one on the inner ring, one on the core loop through the boulevard), stop at the sidewalk stations, let their
 * passenger out and pick up the next one. Each cab always has a virtual position on its loop, so the fleet keeps working
 * (and earning fares) wherever the player is; a real, simulated car with a real passenger exists only near the player.
 */
public final class RobotaxiDirector {
    public static final String TAG = "tt_cab";
    private static final double VIRTUAL_SPEED = 0.52;                // blocks per tick of a cab nobody can see
    private static final double NEAR = 104;
    private static final double FAR = 138;

    private static final class Cab {
        final int id;
        final CityRoutes.Route route;
        double s;                           // distance along the loop (virtual or synced from the real car)
        UUID entity;
        UUID rider;
        UUID waiting;
        UUID leaving;                       // the passenger that just got out and walks away
        long leaveAt;
        int startIdx;                       // the waypoint the current Ai list starts with
        int dwell;                          // virtual stop
        int phase;                          // 0 driving, 1 stopped at a station
        int t;
        int station = -1;

        Cab(int id, CityRoutes.Route route, double s) {
            this.id = id;
            this.route = route;
            this.s = s;
        }
    }

    private static final class Session {
        final Cab[] cabs = new Cab[4];
        long rides;
        long earned;
        long lastMessage;
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static CityRoutes.Route outer;
    private static CityRoutes.Route inner;
    private static CityRoutes.Route core;

    private RobotaxiDirector() {}

    private static void routes() {
        if (outer == null) {
            outer = CityRoutes.outer();
            inner = CityRoutes.inner();
            core = CityRoutes.core();
        }
    }

    private static Session session(Company c) {
        routes();
        return SESSIONS.computeIfAbsent(c.owner, k -> {
            Session s = new Session();
            s.cabs[0] = new Cab(0, outer, 40);
            s.cabs[1] = new Cab(1, outer, 40 + outer.length / 2);
            s.cabs[2] = new Cab(2, inner, inner.sOf(0) + 30);
            s.cabs[3] = new Cab(3, core, 4);
            return s;
        });
    }

    public static void reset(Company c) {
        SESSIONS.remove(c.owner);
    }

    public static String status(Company c) {
        Session s = SESSIONS.get(c.owner);
        long rides = s == null ? 0 : s.rides;
        long earned = s == null ? 0 : s.earned;
        return String.format(Locale.US, "Robotaxi Terminal online: 4 Cybercabs on the city loops | %,d rides | $%,d in fares", rides, earned);
    }

    /** Showroom level 4 was just built: the fleet is switched on at the terminal. */
    public static void onLevel4(ServerWorld w, Company c) {
        session(c);
        Vec3d at = CityBuilder.vec(c, -38.5, 1.8, -12.0);
        w.spawnParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 50, 1.2, 0.8, 1.2, 0.08);
        w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.NEUTRAL, 1.5f, 1.3f);
    }

    // ------------------------------------------------------------------------------------------------------ tick
    public static void tick(ServerWorld w, Company c, ServerPlayerEntity owner) {
        if (c.level < 4) {
            return;
        }
        Session s = session(c);
        for (Cab cab : s.cabs) {
            Entity e = cab.entity == null ? null : w.getEntity(cab.entity);
            VehicleEntity car = e instanceof VehicleEntity v && v.isAlive() ? v : null;
            double[] p = cab.route.at(cab.s);
            Vec3d at = CityBuilder.vec(c, p[0], 0.0, p[1]);
            double dist = owner.getWorld() == w ? Math.hypot(owner.getX() - at.x, owner.getZ() - at.z) : 1e9;
            if (car == null) {
                cab.entity = null;
                if (cab.rider != null) {
                    discard(w, cab.rider);
                    cab.rider = null;
                }
                if (cab.waiting != null) {
                    discard(w, cab.waiting);
                    cab.waiting = null;
                }
                if (cab.leaving != null) {
                    discard(w, cab.leaving);
                    cab.leaving = null;
                }
                if (dist < NEAR) {
                    spawn(w, c, cab, p);
                } else {
                    virtual(w, c, owner, s, cab);
                }
                continue;
            }
            if (dist > FAR && owner.getWorld() == w) {
                sync(c, cab, car);
                discard(w, cab.rider);
                discard(w, cab.waiting);
                discard(w, cab.leaving);
                cab.rider = null;
                cab.waiting = null;
                cab.leaving = null;
                car.discard();
                cab.entity = null;
                continue;
            }
            real(w, c, owner, s, cab, car);
        }
    }

    private static void discard(ServerWorld w, UUID id) {
        Entity e = id == null ? null : w.getEntity(id);
        if (e != null) {
            e.discard();
        }
    }

    /** Nobody can see this cab: it just moves along its loop, stops at the stations and earns its fares. */
    private static void virtual(ServerWorld w, Company c, ServerPlayerEntity owner, Session s, Cab cab) {
        if (cab.dwell > 0) {
            cab.dwell--;
            return;
        }
        double before = cab.s;
        cab.s += VIRTUAL_SPEED;
        for (Map.Entry<Integer, Integer> st : cab.route.stops.entrySet()) {
            double at = cab.route.sOf(st.getKey());
            double b0 = before % cab.route.length;
            double b1 = cab.s % cab.route.length;
            boolean crossed = b0 <= b1 ? (at > b0 && at <= b1) : (at > b0 || at <= b1);
            if (crossed) {
                cab.dwell = 90;
                pay(c, owner, s, cab, false);
                break;
            }
        }
    }

    private static void pay(Company c, ServerPlayerEntity owner, Session s, Cab cab, boolean visible) {
        long fare = c.worth(Company.FARE_SECONDS);
        c.money += fare;
        c.totalEarned += fare;
        s.rides++;
        s.earned += fare;
        if (visible && c.cityTick - s.lastMessage > 60) {
            s.lastMessage = c.cityTick;
            owner.sendMessage(Text.literal("Robotaxi fare: +$" + String.format(Locale.US, "%,d", fare)).formatted(Formatting.AQUA), true);
        }
    }

    // ------------------------------------------------------------------------------------------------------ the real cab
    private static VehicleEntity.Ai makeAi(Company c, Cab cab, int startIdx, Runnable onStop) {
        CityRoutes.Route r = cab.route;
        int n = r.pts.size();
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        for (int i = 0; i < n; i++) {
            int src = (startIdx + i) % n;
            double[] p = r.pts.get(src);
            ai.points.add(CityBuilder.vec(c, p[0], 0.0, p[1]));
            if (r.corners.contains(src)) {
                ai.corners.add(i);
            }
            if (r.stops.containsKey(src)) {
                ai.stops.add(i);
            }
        }
        ai.cruise = 0.66;
        ai.arrive = 2.8;
        ai.stopAtEnd = false;
        ai.onWaypoint = onStop;
        cab.startIdx = startIdx;
        return ai;
    }

    private static void spawn(ServerWorld w, Company c, Cab cab, double[] p) {
        int next = (int) cab.route.at(cab.s)[2];
        double[] to = cab.route.pts.get(next);
        VehicleEntity car = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.CYBERCAB), w);
        car.setOwner(c.owner);
        car.addCommandTag(TAG);
        Vec3d at = CityBuilder.vec(c, p[0], 0.05, p[1]);
        float yaw = (float) Math.toDegrees(Math.atan2(-(to[0] - p[0]), to[1] - p[1]));
        car.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f);
        car.setAi(makeAi(c, cab, next, () -> arrived(w, c, cab)));
        w.spawnEntity(car);
        cab.entity = car.getUuid();
        cab.phase = 0;
        cab.station = -1;
    }

    private static void sync(Company c, Cab cab, VehicleEntity car) {
        if (car.ai() == null) {
            return;
        }
        int n = cab.route.pts.size();
        int nextOrig = (car.ai().index + cab.startIdx) % n;
        int prev = Math.floorMod(nextOrig - 1, n);
        double[] pp = cab.route.pts.get(prev);
        Vec3d at = CityBuilder.vec(c, pp[0], 0, pp[1]);
        cab.s = cab.route.sOf(prev) + Math.hypot(car.getX() - at.x, car.getZ() - at.z);
    }

    /** The Ai calls this whenever the cab passes or reaches a waypoint; a stop makes it wait for its passenger. */
    private static void arrived(ServerWorld w, Company c, Cab cab) {
        Entity e = cab.entity == null ? null : w.getEntity(cab.entity);
        if (!(e instanceof VehicleEntity car) || car.ai() == null) {
            return;
        }
        int passed = car.ai().index - 1;
        if (car.ai().stops.contains(passed) && car.ai().hold > 0) {
            int n = cab.route.pts.size();
            Integer st = cab.route.stops.get((passed + cab.startIdx) % n);
            cab.station = st == null ? -1 : st;
            cab.phase = 1;
            cab.t = 0;
            car.ai().hold = 100000;
        }
    }

    private static void real(ServerWorld w, Company c, ServerPlayerEntity owner, Session s, Cab cab, VehicleEntity car) {
        VehicleEntity.Ai ai = car.ai();
        if (ai == null || ai.finished) {
            sync(c, cab, car);
            int n = cab.route.pts.size();
            int start = ai == null ? (int) cab.route.at(cab.s)[2] : cab.startIdx;
            car.setAi(makeAi(c, cab, start % n, () -> arrived(w, c, cab)));
            return;
        }
        sync(c, cab, car);
        if (cab.leaving != null && c.cityTick >= cab.leaveAt) {
            discard(w, cab.leaving);
            cab.leaving = null;
        }
        if (cab.phase != 1 || cab.station < 0) {
            return;
        }
        cab.t++;
        int[] st = Buildings.STATIONS[cab.station];
        int side = st[2];
        double dx = side == 2 ? -1 : (side == 3 ? 1 : 0);        // from the pavement towards the road
        double dz = side == 0 ? -1 : (side == 1 ? 1 : 0);
        double[] stop = CityRoutes.STOP[cab.station];
        Vec3d door = CityBuilder.vec(c, stop[0] - dx * 2.6, 1.0, stop[1] - dz * 2.6);
        Vec3d back = CityBuilder.vec(c, stop[0] - dx * 6.5, 1.0, stop[1] - dz * 6.5);
        if (cab.t == 8 && cab.rider != null) {
            Entity r = w.getEntity(cab.rider);
            if (r instanceof SuitedNpcEntity npc) {
                npc.stopRiding();
                npc.refreshPositionAndAngles(door.x, door.y, door.z, 0f, 0f);
                npc.walk(List.of(back, CityBuilder.vec(c, stop[0] - dx * 9.0 + dz * 5.0, 1.0, stop[1] - dz * 9.0 + dx * 5.0)), 0.2, null);
                pay(c, owner, s, cab, true);
                w.playSound(null, door.x, door.y, door.z, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.NEUTRAL, 0.9f, 1.4f);
                if (cab.leaving != null) {
                    discard(w, cab.leaving);
                }
                cab.leaving = npc.getUuid();
                cab.leaveAt = c.cityTick + 100;
            }
            cab.rider = null;
        }
        if (cab.t == 18 && w.random.nextInt(100) < 85) {
            SuitedNpcEntity npc = ShowroomDirector.spawnNpc(w, back, 0f, SuitedNpcEntity.CUSTOMER, 1 + w.random.nextInt(3), null);
            npc.walk(List.of(door), 0.2, null);
            cab.waiting = npc.getUuid();
        }
        if (cab.waiting != null && cab.t > 18) {
            Entity r = w.getEntity(cab.waiting);
            if (r instanceof SuitedNpcEntity npc) {
                if (!npc.isWalking() && npc.squaredDistanceTo(door) < 9.0 && cab.t > 30) {
                    npc.startRiding(car, true);
                    cab.rider = npc.getUuid();
                    cab.waiting = null;
                    cab.t = Math.max(cab.t, 120);            // sit down, then drive on
                }
            } else {
                cab.waiting = null;
            }
        }
        if (cab.t > 150 || (cab.waiting == null && cab.t > 100)) {
            if (cab.waiting != null) {
                discard(w, cab.waiting);                    // never made it to the cab: it goes home
                cab.waiting = null;
            }
            ai.hold = 0;
            cab.phase = 0;
        }
    }
}
