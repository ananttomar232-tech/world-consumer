package com.anant.teslatycoon.npc;

import static com.anant.teslatycoon.city.CityLayout.*;

import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.registry.ModBlocks;
import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.registry.ModItems;
import com.anant.teslatycoon.sign.PosterEntity;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpec;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Zero-effort showroom life: the manager at reception, rich customers who walk in, pay, test-drive a car around the ring road and
 * bring it back, the rotating exhibits on every floor, and the delivery truck that unloads crates in the back alley.
 */
public final class ShowroomDirector {
    private static final class Customer {
        UUID npc;
        UUID car;
        int stage;
        int t;
        boolean paid;
    }

    private static final class Truck {
        UUID entity;
        int stage;
        int t;
        int crates;
    }

    private static final class Session {
        UUID manager;
        UUID clerk;
        final List<Customer> customers = new ArrayList<>();
        final List<UUID> displays = new ArrayList<>();
        UUID playerCar;
        long nextCustomer = 200;
        long nextTruck = 700;
        Truck truck;
        long lastPayment;
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final String AUTO_TAG = "tt_auto";

    private ShowroomDirector() {}

    public static void reset(Company c) {
        Session s = SESSIONS.remove(c.owner);
        if (s != null) {
            // entities are cleaned up by CityBuilder.spawnEntities; forget all handles
            s.customers.clear();
        }
    }

    private static Session session(Company c) {
        return SESSIONS.computeIfAbsent(c.owner, k -> new Session());
    }

    // ------------------------------------------------------------------------------------------------------ gifts
    public static void giveStarterPosters(ServerPlayerEntity p, int n) {
        p.giveItemStack(new ItemStack(ModItems.WALL_POSTER, n));
        p.sendMessage(Text.literal("+" + n + " Luxury Wall Posters (right-click a wall or glass pillar to hang one)").formatted(Formatting.AQUA), true);
    }

    // ------------------------------------------------------------------------------------------------------ displays
    /** The newest model on the ground floor, every earlier model one floor higher, each on a rotating pad. */
    public static void layoutDisplays(ServerWorld w, Company c) {
        Session s = session(c);
        Box box = new Box(c.origin.getX() + SX0, c.origin.getY(), c.origin.getZ() + SZ0, c.origin.getX() + SX1 + 1, c.origin.getY() + 45, c.origin.getZ() + SZ1 + 1);
        for (VehicleEntity v : w.getEntitiesByClass(VehicleEntity.class, box, e -> e.getMode() == VehicleEntity.MODE_DISPLAY)) {
            v.discard();
        }
        s.displays.clear();
        for (int floor = 1; floor <= c.level; floor++) {
            VehicleSpec spec = VehicleSpecs.LEVELS.get(c.level - floor);
            VehicleEntity v = new VehicleEntity(ModEntities.vehicle(spec), w);
            v.setMode(VehicleEntity.MODE_DISPLAY);
            v.setOwner(c.owner);
            Vec3d pos = CityBuilder.vec(c, PAD_X + 0.5, floorSlab(floor) + 1.0, PAD_Z + 0.5);
            v.refreshPositionAndAngles(pos.x, pos.y, pos.z, floor * 70f, 0f);
            w.spawnEntity(v);
            s.displays.add(v.getUuid());
        }
        Vec3d fx = CityBuilder.vec(c, PAD_X + 0.5, 2.5, PAD_Z + 0.5);
        w.spawnParticles(ParticleTypes.END_ROD, fx.x, fx.y, fx.z, 40, 1.8, 1.2, 1.8, 0.05);
    }

    // ------------------------------------------------------------------------------------------------------ posters
    public static void spawnPosters(ServerWorld w, Company c) {
        Box box = new Box(c.origin.getX() + SX0 - 1, c.origin.getY(), c.origin.getZ() + SZ0, c.origin.getX() + SX1 + 1, c.origin.getY() + 45, c.origin.getZ() + SZ0 + 4);
        for (PosterEntity e : w.getEntitiesByClass(PosterEntity.class, box, p -> p.getCommandTags().contains(AUTO_TAG))) {
            e.discard();
        }
        for (int floor = 1; floor <= c.level; floor++) {
            int ys = floorSlab(floor);
            for (int x : new int[]{-55, -51}) {
                BlockPos at = CityBuilder.pos(c, x, ys + 2, SZ0 + 2);
                PosterEntity poster = new PosterEntity(w, at, Direction.SOUTH);
                poster.addCommandTag(AUTO_TAG);
                if (poster.canStayAttached()) {
                    w.spawnEntity(poster);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ staff
    private static boolean alive(ServerWorld w, UUID id) {
        Entity e = id == null ? null : w.getEntity(id);
        return e != null && e.isAlive();
    }

    public static SuitedNpcEntity spawnNpc(ServerWorld w, Vec3d pos, float yaw, int role, int look, String name) {
        SuitedNpcEntity n = new SuitedNpcEntity(ModEntities.SUITED, w);
        n.setRole(role);
        n.setLook(look);
        n.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0f);
        n.setHeadYaw(yaw);
        n.setBodyYaw(yaw);
        if (name != null) {
            n.setCustomName(Text.literal(name).formatted(role == SuitedNpcEntity.GOTIYA ? Formatting.GOLD : Formatting.AQUA));
            n.setCustomNameVisible(true);
        }
        w.spawnEntity(n);
        return n;
    }

    public static void ensureStaff(ServerWorld w, Company c) {
        Session s = session(c);
        if (!alive(w, s.manager)) {
            SuitedNpcEntity m = spawnNpc(w, CityBuilder.vec(c, -53.5, 2.0, -14.1), 0f, SuitedNpcEntity.MANAGER, 0, "Manager Rohan");
            m.setSitting(true);
            m.faceTowards(0f);
            s.manager = m.getUuid();
        }
        if (!alive(w, s.clerk)) {
            SuitedNpcEntity m = spawnNpc(w, CityBuilder.vec(c, -14.0, 1.0, -16.3), 0f, SuitedNpcEntity.CLERK, 5, "Upgrade Shop Clerk");
            m.faceTowards(0f);
            s.clerk = m.getUuid();
        }
    }

    // ------------------------------------------------------------------------------------------------------ test drives
    /** Right-click on an exhibit: the drivable copy appears in the test-drive bay and the player is seated. */
    public static void testDrive(ServerPlayerEntity player, VehicleEntity display, Company c) {
        ServerWorld w = (ServerWorld) player.getWorld();
        Session s = session(c);
        if (s.playerCar != null) {
            Entity old = w.getEntity(s.playerCar);
            if (old != null) {
                old.discard();
            }
        }
        VehicleEntity car = new VehicleEntity(ModEntities.vehicle(display.spec), w);
        car.setMode(VehicleEntity.MODE_DRIVEN);
        car.setOwner(c.owner);
        applyUpgrades(car, c);
        Vec3d bay = CityBuilder.vec(c, (BAY_X0 + BAY_X1 + 1) / 2.0, 1.0, BAY_START_Z + 0.5);
        car.refreshPositionAndAngles(bay.x, bay.y, bay.z, 0f, 0f);
        w.spawnEntity(car);
        player.startRiding(car, true);
        s.playerCar = car.getUuid();
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, bay.x, bay.y + 0.8, bay.z, 30, 1.4, 0.6, 2.0, 0.1);
        w.playSound(null, bay.x, bay.y, bay.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.NEUTRAL, 1.0f, 1.4f);
        player.sendMessage(Text.literal("W/S drive, A/D steer, Shift to get out. Drive out of the glass bay door onto the city road!").formatted(Formatting.AQUA), true);
    }

    /** Operator helper (and scripted scenes): parks an owned, upgraded car at the player and seats them in it. */
    public static void spawnOwnedCar(ServerPlayerEntity player, Company c, VehicleSpec spec) {
        ServerWorld w = (ServerWorld) player.getWorld();
        VehicleEntity car = new VehicleEntity(ModEntities.vehicle(spec), w);
        car.setMode(VehicleEntity.MODE_DRIVEN);
        car.setOwner(c.owner);
        applyUpgrades(car, c);
        car.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), 0f);
        w.spawnEntity(car);
        player.startRiding(car, true);
        session(c).playerCar = car.getUuid();
    }

    public static void applyUpgrades(VehicleEntity car, Company c) {
        car.setFlag(VehicleEntity.F_HYDRAULICS, c.owns(Upgrade.HYDRAULICS));
        car.setFlag(VehicleEntity.F_ARMOR, c.owns(Upgrade.HYDRAULICS));
        car.ramUnlocked = c.owns(Upgrade.RAM);
    }

    private static List<Vec3d> loopRoute(Company c) {
        double[][] pts = {
                {-33, -12}, {-33, -3}, {-33, 4}, {-33, 10.5}, {0, 10.5}, {40, 10.5}, {58, 10.5}, {61.5, 6}, {61.5, -10}, {61.5, -38}, {58, -42},
                {20, -42}, {-40, -42}, {-82, -42}, {-85.5, -38}, {-85.5, -10}, {-85.5, 6}, {-82, 10.5}, {-60, 10.5}, {-40, 10.5}, {-33, 7}, {-33, 2},
                {-33, -4}, {-33, -10}, {-33, -22}};
        List<Vec3d> out = new ArrayList<>();
        for (double[] p : pts) {
            out.add(CityBuilder.vec(c, p[0], 1.0, p[1]));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------ tick
    public static void tick(ServerWorld w, Company c, ServerPlayerEntity owner) {
        Session s = session(c);
        double dx = owner.getX() - c.origin.getX();
        double dz = owner.getZ() - c.origin.getZ();
        boolean near = dx * dx + dz * dz < 190 * 190 && owner.getWorld() == w;
        if (c.cityTick % 100 == 17 && near) {
            ensureStaff(w, c);
            ensureDisplays(w, c, s);
        }
        if (!near) {
            return;
        }
        // new customers
        long interval = Math.max(300, 780 - 90L * c.level);
        int maxCustomers = c.level >= 3 ? 3 : 2;
        if (c.cityTick >= s.nextCustomer && s.customers.size() < maxCustomers && !c.powerOut) {
            spawnCustomer(w, c, s);
            s.nextCustomer = c.cityTick + interval + w.random.nextInt(120);
        }
        tickCustomers(w, c, s, owner);
        tickTruck(w, c, s, owner);
    }

    private static void ensureDisplays(ServerWorld w, Company c, Session s) {
        int alive = 0;
        for (UUID id : s.displays) {
            if (alive(w, id)) {
                alive++;
            }
        }
        if (alive != c.level) {
            layoutDisplays(w, c);
        }
    }

    // ------------------------------------------------------------------------------------------------------ customers
    private static void spawnCustomer(ServerWorld w, Company c, Session s) {
        Customer cu = new Customer();
        SuitedNpcEntity n = spawnNpc(w, CityBuilder.vec(c, -72.5, 1.0, -3.0), -90f, SuitedNpcEntity.CUSTOMER, 1 + w.random.nextInt(3), "Rich Customer");
        n.walk(List.of(CityBuilder.vec(c, -45.5, 1.0, -3.0), CityBuilder.vec(c, -45.5, 1.0, -8.5), CityBuilder.vec(c, -53.5, 1.0, -9.2)), 0.16, null);
        cu.npc = n.getUuid();
        s.customers.add(cu);
    }

    private static void tickCustomers(ServerWorld w, Company c, Session s, ServerPlayerEntity owner) {
        Iterator<Customer> it = s.customers.iterator();
        while (it.hasNext()) {
            Customer cu = it.next();
            Entity e = w.getEntity(cu.npc);
            if (!(e instanceof SuitedNpcEntity npc) || !npc.isAlive()) {
                discardCar(w, cu);
                it.remove();
                continue;
            }
            cu.t++;
            switch (cu.stage) {
                case 0 -> {
                    if (!npc.isWalking()) {
                        cu.stage = 1;
                        cu.t = 0;
                        npc.faceTowards(180f);               // look at the manager behind the desk
                    }
                }
                case 1 -> {
                    if (cu.t == 50) {
                        pay(w, c, npc, owner);
                        Tycoon.log("[showroom] customer paid at reception, walking to the test-drive bay");
                        npc.walk(List.of(CityBuilder.vec(c, -45.5, 1.0, -9.0), CityBuilder.vec(c, -33.5, 1.0, -23.0)), 0.17, null);
                        cu.stage = 2;
                        cu.t = 0;
                    }
                }
                case 2 -> {
                    if (!npc.isWalking()) {
                        VehicleSpec spec = VehicleSpecs.LEVELS.get(w.random.nextInt(c.level));
                        VehicleEntity car = new VehicleEntity(ModEntities.vehicle(spec), w);
                        car.setOwner(c.owner);
                        applyUpgrades(car, c);
                        Vec3d bay = CityBuilder.vec(c, (BAY_X0 + BAY_X1 + 1) / 2.0, 1.0, BAY_START_Z + 0.5);
                        car.refreshPositionAndAngles(bay.x, bay.y, bay.z, 0f, 0f);
                        VehicleEntity.Ai ai = new VehicleEntity.Ai();
                        ai.points.addAll(loopRoute(c));
                        ai.cruise = Math.min(0.72, spec.maxSpeed * 0.62);
                        car.setAi(ai);
                        w.spawnEntity(car);
                        npc.startRiding(car, true);
                        Tycoon.log("[showroom] customer drives out in the " + spec.id);
                        cu.car = car.getUuid();
                        cu.stage = 3;
                        cu.t = 0;
                    }
                }
                case 3 -> {
                    Entity ce = cu.car == null ? null : w.getEntity(cu.car);
                    boolean timeout = cu.t > 20 * 100;
                    if (!(ce instanceof VehicleEntity car) || !car.isAlive()) {
                        npc.stopRiding();
                        cu.stage = 4;
                        npc.walk(List.of(CityBuilder.vec(c, -45.5, 1.0, -8.0), CityBuilder.vec(c, -45.5, 1.0, -3.0), CityBuilder.vec(c, -75.5, 1.0, -3.0)), 0.18, null);
                    } else if ((car.ai() != null && car.ai().finished) || timeout) {
                        npc.stopRiding();
                        Tycoon.log("[showroom] customer returned the " + car.spec.id + (timeout ? " (timeout)" : " after the loop"));
                        car.discard();
                        cu.car = null;
                        cu.stage = 4;
                        cu.t = 0;
                        npc.refreshPositionAndAngles(CityBuilder.vec(c, -33.5, 1.0, -23.0).x, CityBuilder.vec(c, -33.5, 1.0, -23.0).y, CityBuilder.vec(c, -33.5, 1.0, -23.0).z, 0f, 0f);
                        npc.walk(List.of(CityBuilder.vec(c, -33.5, 1.0, -15.0), CityBuilder.vec(c, -45.5, 1.0, -8.0), CityBuilder.vec(c, -45.5, 1.0, -3.0), CityBuilder.vec(c, -75.5, 1.0, -3.0)), 0.18, null);
                        w.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1f);
                    }
                }
                case 4 -> {
                    if (!npc.isWalking() || cu.t > 20 * 60) {
                        npc.discard();
                        discardCar(w, cu);
                        it.remove();
                    }
                }
                default -> it.remove();
            }
        }
    }

    private static void discardCar(ServerWorld w, Customer cu) {
        if (cu.car != null) {
            Entity e = w.getEntity(cu.car);
            if (e != null) {
                e.discard();
            }
        }
    }

    private static void pay(ServerWorld w, Company c, SuitedNpcEntity npc, ServerPlayerEntity owner) {
        long tip = Math.round(c.incomePerSecond() * 2.0);
        c.money += tip;
        c.totalEarned += tip;
        c.customersServed++;
        w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, npc.getX(), npc.getY() + 1.6, npc.getZ(), 14, 0.4, 0.4, 0.4, 0.02);
        w.spawnParticles(ParticleTypes.GLOW, npc.getX(), npc.getY() + 1.2, npc.getZ(), 10, 0.5, 0.3, 0.5, 0.1);
        w.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.NEUTRAL, 1.0f, 1.3f);
        owner.sendMessage(Text.literal("Customer paid at reception: +$" + tip).formatted(Formatting.GREEN), true);
    }

    // ------------------------------------------------------------------------------------------------------ delivery truck
    private static List<Vec3d> truckIn(Company c) {
        // (it appears in the middle of the alley, ~70 blocks from the showroom: close enough to be simulated with any simulation distance)
        return List.of(CityBuilder.vec(c, 24, 0.0, -41.5), CityBuilder.vec(c, -20, 0.0, -41.5), CityBuilder.vec(c, -47, 0.0, -41.5));
    }

    private static List<Vec3d> truckOut(Company c) {
        return List.of(CityBuilder.vec(c, -62, 0.0, -41.5), CityBuilder.vec(c, -84, 0.0, -41.5));
    }

    /** The Tesla delivery truck rolls into the back alley towards the loading dock (public so that a game test can run the same drive). */
    public static VehicleEntity spawnDeliveryTruck(ServerWorld w, Company c) {
        VehicleEntity truck = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.TRUCK), w);
        Vec3d start = truckIn(c).get(0);
        truck.refreshPositionAndAngles(start.x, start.y, start.z, 90f, 0f);
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.addAll(truckIn(c));
        ai.cruise = 0.62;
        ai.arrive = 3.5;
        ai.stopAtEnd = true;
        truck.setAi(ai);
        w.spawnEntity(truck);
        return truck;
    }

    private static void tickTruck(ServerWorld w, Company c, Session s, ServerPlayerEntity owner) {
        Truck t = s.truck;
        if (t == null) {
            if (c.cityTick >= s.nextTruck) {
                VehicleEntity truck = spawnDeliveryTruck(w, c);
                Vec3d start = truck.getPos();
                w.playSound(null, start.x, start.y, start.z, SoundEvents.ENTITY_IRON_GOLEM_REPAIR, SoundCategory.NEUTRAL, 1.5f, 0.5f);
                t = new Truck();
                t.entity = truck.getUuid();
                s.truck = t;
                owner.sendMessage(Text.literal("A Tesla delivery truck is arriving in the back alley...").formatted(Formatting.GRAY), true);
            }
            return;
        }
        Entity e = w.getEntity(t.entity);
        if (!(e instanceof VehicleEntity truck) || !truck.isAlive()) {
            s.truck = null;
            s.nextTruck = c.cityTick + 1200;
            return;
        }
        t.t++;
        switch (t.stage) {
            case 0 -> {
                if (t.t > 20 * 40 && truck.ai() != null && !truck.ai().finished) {
                    Vec3d dock = truckIn(c).get(truckIn(c).size() - 1);        // failsafe: something blocked the alley, so the truck is put at the dock
                    truck.refreshPositionAndAngles(dock.x, dock.y, dock.z, 90f, 0f);
                    truck.stopHard();
                    truck.ai().finished = true;
                    Tycoon.log("[showroom] delivery truck was held up in the alley and was put at the dock");
                }
                if (truck.ai() != null && truck.ai().finished) {
                    t.stage = 1;
                    t.t = 0;
                    Tycoon.log("[showroom] delivery truck reached the dock");
                    w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.NEUTRAL, 1.2f, 0.7f);
                }
            }
            case 1 -> {
                if (t.t % 18 == 0 && t.crates < 3) {
                    int x = -49 + t.crates * 2;
                    BlockPos at = CityBuilder.pos(c, x, 1, -37);
                    w.setBlockState(at, ModBlocks.DELIVERY_CRATE.getDefaultState(), Block.NOTIFY_ALL);
                    w.playSound(null, at, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.2f, 0.8f);
                    w.spawnParticles(ParticleTypes.POOF, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.02);
                    t.crates++;
                }
                if (t.crates >= 3 && t.t > 110) {
                    for (int i = 0; i < 3; i++) {
                        BlockPos at = CityBuilder.pos(c, -49 + i * 2, 1, -37);
                        w.setBlockState(at, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                        w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 0.8, at.getZ() + 0.5, 10, 0.4, 0.4, 0.4, 0.02);
                    }
                    long bonus = Math.round(c.incomePerSecond() * 6.0);
                    c.money += bonus;
                    c.totalEarned += bonus;
                    c.deliveries++;
                    Tycoon.log("[showroom] delivery unloaded, bonus $" + bonus);
                    owner.sendMessage(Text.literal("Delivery unloaded! New Tesla stock: +$" + bonus).formatted(Formatting.GOLD), true);
                    w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.NEUTRAL, 1.0f, 1.1f);
                    VehicleEntity.Ai out = new VehicleEntity.Ai();
                    out.points.addAll(truckOut(c));
                    out.cruise = 0.6;
                    out.arrive = 4.0;
                    out.stopAtEnd = false;
                    truck.setAi(out);
                    t.stage = 2;
                    t.t = 0;
                }
            }
            case 2 -> {
                if ((truck.ai() != null && truck.ai().finished) || t.t > 20 * 40) {
                    Tycoon.log("[showroom] delivery truck left the alley");
                    truck.discard();
                    s.truck = null;
                    s.nextTruck = c.cityTick + 1500 + w.random.nextInt(400);
                }
            }
            default -> {
            }
        }
    }
}
