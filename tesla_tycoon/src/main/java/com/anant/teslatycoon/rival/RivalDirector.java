package com.anant.teslatycoon.rival;

import static com.anant.teslatycoon.city.CityLayout.*;

import com.anant.teslatycoon.block.NeonColor;
import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.npc.ShowroomDirector;
import com.anant.teslatycoon.npc.SuitedNpcEntity;
import com.anant.teslatycoon.registry.ModBlocks;
import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Seth Gotiya: the dialogue, the five sabotages and the counters that beat them. One sabotage per showroom level:
 * 1 road spikes, 2 blackout, 3 concrete roadblocks, 4 GPS jam, 5 the Monster Truck attack (answered by the Roadster's EMP).
 */
public final class RivalDirector {
    public static final int EMP_COOLDOWN = 160;

    private static final class State {
        long nextTaunt = 900;
        long pendingAt = -1;
        int pendingLevel;
        UUID gotiya;
        UUID showTruck;
        final List<BlockPos> spikes = new ArrayList<>();
        final List<BlockPos> barriers = new ArrayList<>();
        long sabStarted;
        int seen;                       // the sabotage that was active at the last check
        long cleanupAt = -1;
        long cryUntil;
        // climax
        boolean climax;
        boolean defeated;
        UUID truck;
        int phase;
        int hitCooldown;
        long climaxStart;
        long victoryAt = -1;
        long nextBoast;
        int boasts;
        Vec3d lastTruckPos;
        int stuckSecs;
        long phaseSince;
    }

    private static final class Task {
        final long at;
        final Runnable run;

        Task(long at, Runnable run) {
            this.at = at;
            this.run = run;
        }
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<UUID, Long> LAST_EMP = new HashMap<>();
    private static final List<Task> TASKS = new ArrayList<>();

    private RivalDirector() {}

    private static State state(Company c) {
        return STATES.computeIfAbsent(c.owner, k -> new State());
    }

    private static void later(int ticks, Runnable r) {
        MinecraftServer s = Tycoon.server;
        if (s != null) {
            TASKS.add(new Task(s.getTicks() + ticks, r));
        }
    }

    public static void serverTick(MinecraftServer server) {
        if (TASKS.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        List<Task> due = new ArrayList<>();
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.at <= now) {
                due.add(t);
                it.remove();
            }
        }
        for (Task t : due) {
            try {
                t.run.run();
            } catch (RuntimeException e) {
                com.anant.teslatycoon.TeslaTycoon.LOGGER.error("rival task failed", e);
            }
        }
    }

    // ============================================================================================================ talking
    public static void say(String msg) {
        MinecraftServer s = Tycoon.server;
        if (s == null) {
            return;
        }
        s.getPlayerManager().broadcast(Text.literal("[Seth Gotiya]: ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(msg).formatted(Formatting.YELLOW)), false);
    }

    /** Anything a player types gets a dynamic Hinglish answer (#retryscene is handled before this is called). */
    public static void onPlayerChat(ServerPlayerEntity sender, String text) {
        Company c = Tycoon.companyOf(sender);
        if (c == null || !c.cityBuilt) {
            return;
        }
        String car = Text.translatable("entity.tesla_tycoon." + VehicleSpecs.LEVELS.get(c.level - 1).lang).getString();
        String reply = GotiyaChat.reply(text, sender.getGameProfile().getName(), c.money, c.level, car, c.activeSabotage());
        later(14 + sender.getRandom().nextInt(22), () -> say(reply));
    }

    // ============================================================================================================ NPC
    public static void ensureGotiya(ServerWorld w, Company c) {
        State s = state(c);
        Entity e = s.gotiya == null ? null : w.getEntity(s.gotiya);
        if (e == null || !e.isAlive()) {
            SuitedNpcEntity g = ShowroomDirector.spawnNpc(w, CityBuilder.vec(c, -40.5, 1.0, 20.5), 180f, SuitedNpcEntity.GOTIYA, 4, "Seth Gotiya");
            g.faceTowards(180f);
            s.gotiya = g.getUuid();
        }
        Entity t = s.showTruck == null ? null : w.getEntity(s.showTruck);
        if (t == null || !t.isAlive()) {
            VehicleEntity truck = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.GOTIYA), w);
            truck.setMode(VehicleEntity.MODE_DISPLAY);
            Vec3d pos = CityBuilder.vec(c, -26.0, 1.0, 40.0);
            truck.refreshPositionAndAngles(pos.x, pos.y, pos.z, 200f, 0f);
            w.spawnEntity(truck);
            s.showTruck = truck.getUuid();
        }
    }

    private static SuitedNpcEntity gotiyaNpc(ServerWorld w, State s) {
        Entity e = s.gotiya == null ? null : w.getEntity(s.gotiya);
        return e instanceof SuitedNpcEntity n ? n : null;
    }

    // ============================================================================================================ tick
    public static void tick(ServerWorld w, Company c, ServerPlayerEntity p) {
        State s = state(c);
        long now = c.cityTick;
        if (now % 60 == 11) {
            ensureGotiya(w, c);
        }
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (g != null) {
            g.setCrying(now < s.cryUntil);
        }
        if (now >= s.nextTaunt && !s.climax) {
            say(now % 3 == 0 ? GotiyaChat.taunt(p.getGameProfile().getName(), c.level, c.money) : GotiyaChat.idle());
            s.nextTaunt = now + 800 + w.random.nextInt(600);
        }
        if (c.level == 1 && (c.sabTriggered & 2) == 0 && now >= 25 * 20) {
            trigger(w, c, p, 1);
        }
        if (s.pendingAt >= 0 && now >= s.pendingAt) {
            s.pendingAt = -1;
            trigger(w, c, p, s.pendingLevel);
        }
        int active = c.activeSabotage();
        if (active != s.seen) {
            if (s.seen != 0 && active == 0) {
                onResolved(w, c, p, s, s.seen);
            }
            s.seen = active;
            Tycoon.sync(p, c);
        }
        if (active == 1 && now % 200 == 5 && s.spikes.stream().noneMatch(b -> w.getBlockState(b).isOf(ModBlocks.SPIKES))) {
            placeSpikes(w, c, s);
        }
        if (active == 3) {
            if (now - s.sabStarted > 20 * 190 && c.owns(Upgrade.RAM)) {
                c.ramSmashed = Math.max(c.ramSmashed, 2);              // failsafe: never trap the story behind a missed roadblock
            }
        }
        if (s.cleanupAt >= 0 && now >= s.cleanupAt) {
            s.cleanupAt = -1;
            if (c.owns(Upgrade.HYDRAULICS)) {
                removeBlocks(w, s.spikes, ModBlocks.SPIKES);
            }
            if (c.ramSmashed >= 2) {
                removeBlocks(w, s.barriers, ModBlocks.BARRIER);
            }
        }
        if (s.climax) {
            climaxTick(w, c, p, s);
        }
    }

    public static boolean climaxActive(Company c) {
        State s = STATES.get(c.owner);
        return s != null && s.climax;
    }

    // ============================================================================================================ sabotages
    public static void triggerNow(ServerWorld w, Company c, ServerPlayerEntity p) {
        trigger(w, c, p, c.level);
    }

    private static void trigger(ServerWorld w, Company c, ServerPlayerEntity p, int k) {
        State s = state(c);
        if ((c.sabTriggered & (1 << k)) != 0 && k != 5) {
            return;
        }
        c.sabTriggered |= 1 << k;
        Tycoon.log("[rival] sabotage " + k + " starts");
        s.sabStarted = c.cityTick;
        s.nextTaunt = c.cityTick + 900;
        String head = "SABOTAGE " + k + "/5";                 // titles are drawn 4x: keep them short enough for a 480 px GUI
        if (p != null) {
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 50, 15));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.translatable("hud.tesla_tycoon.counter." + k).formatted(Formatting.YELLOW)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(head).formatted(Formatting.RED, Formatting.BOLD)));
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.AMBIENT, 0.5f, 1.6f);
        }
        say(GotiyaChat.sabotageLine(k));
        switch (k) {
            case 1 -> placeSpikes(w, c, s);
            case 2 -> {
                c.powerOut = true;
                CityBuilder.setPower(w, c, false);
                lightning(w, CityBuilder.vec(c, -44, FLOOR_H * c.level + 2, -20));
            }
            case 3 -> placeBarricades(w, c, s);
            case 4 -> {
            }
            case 5 -> {
                if (p != null) {
                    startClimax(w, c, p, s, false);
                }
            }
            default -> {
            }
        }
        if (p != null) {
            Tycoon.sync(p, c);
        }
    }

    /** Test entry: runs sabotage {@code k} for a company without a player. */
    public static void triggerForTest(ServerWorld w, Company c, int k) {
        trigger(w, c, null, k);
    }

    private static void lightning(ServerWorld w, Vec3d at) {
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
        if (bolt != null) {
            bolt.refreshPositionAfterTeleport(at.x, at.y, at.z);
            bolt.setCosmetic(true);
            w.spawnEntity(bolt);
        }
    }

    private static void placeSpikes(ServerWorld w, Company c, State s) {
        s.spikes.clear();
        int[][] patches = {{-12, 9}, {16, 9}, {44, 9}, {-12, 2}, {60, -22}, {-62, -43}};
        for (int[] pt : patches) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 4; dz++) {
                    BlockPos b = CityBuilder.pos(c, pt[0] + dx, 0, pt[1] + dz);
                    if (w.getBlockState(b).isAir()) {
                        w.setBlockState(b, ModBlocks.SPIKES.getDefaultState(), Block.NOTIFY_ALL);
                        s.spikes.add(b);
                    }
                }
            }
        }
    }

    private static void placeBarricades(ServerWorld w, Company c, State s) {
        s.barriers.clear();
        int[][] walls = {{-4, 8, 2, 6}, {30, 8, 2, 6}, {59, -22, 6, 2}};     // x, z, width(x), depth(z)
        for (int[] wl : walls) {
            for (int dx = 0; dx < wl[2]; dx++) {
                for (int dz = 0; dz < wl[3]; dz++) {
                    for (int dy = 0; dy < 2; dy++) {
                        BlockPos b = CityBuilder.pos(c, wl[0] + dx, dy, wl[1] + dz);
                        if (w.getBlockState(b).isAir()) {
                            w.setBlockState(b, ModBlocks.BARRIER.getDefaultState(), Block.NOTIFY_ALL);
                            s.barriers.add(b);
                        }
                    }
                }
            }
        }
        w.playSound(null, CityBuilder.pos(c, 0, 0, 10), SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.BLOCKS, 2f, 0.6f);
    }

    private static void removeBlocks(ServerWorld w, List<BlockPos> list, Block block) {
        for (BlockPos b : list) {
            if (w.getBlockState(b).isOf(block)) {
                w.setBlockState(b, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                w.spawnParticles(ParticleTypes.POOF, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.01);
            }
        }
        list.clear();
    }

    private static void onResolved(ServerWorld w, Company c, ServerPlayerEntity p, State s, int sab) {
        Tycoon.log("[rival] sabotage " + sab + " countered");
        s.cryUntil = c.cityTick + 20 * 14;
        say(GotiyaChat.cryLine(sab));
        s.cleanupAt = c.cityTick + 20 * 30;
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 45, 15));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Gotiya's plan failed. Income is back to full!").formatted(Formatting.GREEN)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("COUNTERED!").formatted(Formatting.GREEN, Formatting.BOLD)));
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);
        if (sab == 4) {
            SuitedNpcEntity g = gotiyaNpc(w, s);
            if (g != null) {
                w.spawnParticles(ParticleTypes.SMOKE, g.getX(), g.getY() + 2, g.getZ(), 30, 0.6, 0.8, 0.6, 0.03);
            }
        }
    }

    // ============================================================================================================ purchases
    public static void onPurchase(ServerWorld w, Company c, ServerPlayerEntity p, Upgrade u) {
        State s = state(c);
        switch (u) {
            case LEVEL2, LEVEL3, LEVEL4 -> {
                s.pendingAt = c.cityTick + 20 * 6;
                s.pendingLevel = u.target;
                say("Level " + u.target + "?! Itni jaldi? Ruk ja {p}... ab dekh mera agla daav!".replace("{p}", p.getGameProfile().getName()));
            }
            case LEVEL5 -> {
                s.pendingAt = c.cityTick + 20 * 9;
                s.pendingLevel = 5;
                say("FLYING ROADSTER?! Mere engine ko takkar dega udhne wali gaadi se?! Ruk, main Monster Truck nikalta hoon!");
            }
            case HYDRAULICS -> p.sendMessage(Text.literal("Jump Hydraulics + Armor Tires fitted: press SPACE to hop, and spikes get crushed.").formatted(Formatting.AQUA), false);
            case SOLAR -> p.sendMessage(Text.literal("Tesla Solar Roof + MegaPack online: the neon is back!").formatted(Formatting.AQUA), false);
            case RAM -> p.sendMessage(Text.literal("Beast Ram Mode unlocked. Drive the Cybertruck, press V, and smash 2 roadblocks.").formatted(Formatting.AQUA), false);
            case STARLINK, TERMINAL -> p.sendMessage(Text.literal("Installed. Finish the set: Starlink + Robotaxi Terminal + " + Company.BILLBOARDS_NEEDED + " billboards.").formatted(Formatting.AQUA), false);
            case BILLBOARDS -> p.sendMessage(Text.literal("Marketing pack ready: right-click empty billboard boards around the city.").formatted(Formatting.AQUA), false);
            default -> {
            }
        }
    }

    public static void onBillboard(ServerWorld w, Company c, ServerPlayerEntity p) {
        say("Yeh kya?! Mere dukaan ke saamne apna board laga diya?! Billboard pe 'BUY TESLA'?! Hai hai!");
        Tycoon.sync(p, c);
    }

    // ============================================================================================================ vehicles
    public static void vehicleHitSpikes(World world, BlockPos pos, VehicleEntity v) {
        if (!(world instanceof ServerWorld sw)) {
            return;
        }
        if (v.hasFlag(VehicleEntity.F_ARMOR) || v.spec.heavy) {
            sw.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            sw.spawnParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 10, 0.3, 0.1, 0.3, 0.2);
            sw.playSound(null, pos, SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.NEUTRAL, 0.8f, 0.6f);
        } else if (!v.hasFlag(VehicleEntity.F_PUNCTURED)) {
            v.setFlag(VehicleEntity.F_PUNCTURED, true);
            punctureUntil.put(v.getId(), (long) v.age + 160);
            sw.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.ENTITY_PUFFER_FISH_BLOW_OUT, SoundCategory.NEUTRAL, 1.5f, 0.5f);
        }
    }

    private static final Map<Integer, Long> punctureUntil = new HashMap<>();

    /** Called every tick for every vehicle on the server. */
    public static void vehicleTick(VehicleEntity v) {
        if (!(v.getWorld() instanceof ServerWorld w)) {
            return;
        }
        if (v.hasFlag(VehicleEntity.F_PUNCTURED)) {
            Long until = punctureUntil.get(v.getId());
            if (until == null || v.age > until) {
                v.setFlag(VehicleEntity.F_PUNCTURED, false);
                punctureUntil.remove(v.getId());
            }
        }
        if (v.getMode() == VehicleEntity.MODE_DISPLAY || v.getOwner() == null) {
            return;
        }
        Company c = Tycoon.state().find(v.getOwner());
        if (c == null) {
            return;
        }
        if (v.age % 20 == 3 && v.getMode() == VehicleEntity.MODE_DRIVEN) {
            ShowroomDirector.applyUpgrades(v, c);
        }
        if (v.spec == VehicleSpecs.CYBERCAB) {
            v.setFlag(VehicleEntity.F_JAMMED, c.activeSabotage() == 4);
        }
        if (v.hasFlag(VehicleEntity.F_RAM) && v.measuredSpeed() > 0.42) {
            ramSmash(w, c, v);
        }
    }

    private static void ramSmash(ServerWorld w, Company c, VehicleEntity v) {
        Vec3d fwd = Vec3d.fromPolar(0, v.getYaw());
        int broken = 0;
        for (int step = 1; step <= 3; step++) {
            for (int side = -2; side <= 2; side++) {
                Vec3d p = v.getPos().add(fwd.multiply(1.6 + step)).add(fwd.z * side * -1.0, 0, fwd.x * side);
                for (int dy = 0; dy < 3; dy++) {
                    BlockPos b = BlockPos.ofFloored(p.x, v.getY() + dy, p.z);
                    BlockState st = w.getBlockState(b);
                    if (st.isOf(ModBlocks.BARRIER)) {
                        w.setBlockState(b, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                        w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, st), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.1);
                        broken++;
                    }
                }
            }
        }
        if (broken > 0) {
            w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.NEUTRAL, 1.2f, 0.8f);
            State s = state(c);
            if (c.cityTick - lastSmash.getOrDefault(c.owner, -100L) > 25) {
                c.ramSmashed++;
                lastSmash.put(c.owner, c.cityTick);
                ServerPlayerEntity owner = Tycoon.server.getPlayerManager().getPlayer(c.owner);
                if (owner != null) {
                    owner.sendMessage(Text.literal("ROADBLOCK SMASHED! (" + c.ramSmashed + "/2)").formatted(Formatting.GOLD, Formatting.BOLD), true);
                    Tycoon.sync(owner, c);
                }
            }
            s.cleanupAt = Math.max(s.cleanupAt, c.cityTick + 20 * 25);
        }
    }

    private static final Map<UUID, Long> lastSmash = new HashMap<>();

    // ============================================================================================================ climax
    private static void startClimax(ServerWorld w, Company c, ServerPlayerEntity p, State s, boolean fromRetry) {
        s.climax = true;
        s.defeated = false;
        s.phase = 0;
        s.hitCooldown = 40;
        s.victoryAt = -1;
        s.climaxStart = c.cityTick;
        s.nextBoast = c.cityTick + 100;
        s.boasts = 0;
        c.climaxHp = 100;
        c.climaxWon = false;
        c.sabTriggered |= 1 << 5;
        killClimaxEntities(w, c, s);
        // the player and a fresh Flying Roadster on the rooftop launchpad
        Vec3d pad = CityBuilder.vec(c, PAD_X + 0.5, FLOOR_H * 5 + 1.0, -21.5);
        for (VehicleEntity old : w.getEntitiesByClass(VehicleEntity.class, new Box(pad.x - 14, pad.y - 3, pad.z - 14, pad.x + 14, pad.y + 6, pad.z + 14),
                e -> e.spec == VehicleSpecs.ROADSTER && e.getMode() == VehicleEntity.MODE_DRIVEN)) {
            old.discard();
        }
        p.stopRiding();
        VehicleEntity car = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.ROADSTER), w);
        car.setMode(VehicleEntity.MODE_DRIVEN);
        car.setOwner(c.owner);
        ShowroomDirector.applyUpgrades(car, c);
        car.refreshPositionAndAngles(pad.x, pad.y, pad.z, 0f, 0f);
        w.spawnEntity(car);
        p.teleport(w, pad.x, pad.y + 0.2, pad.z, 0f, 15f);
        p.startRiding(car, true);
        // Seth Gotiya's Monster Truck leaves his garage and charges the showroom
        VehicleEntity truck = spawnChargeTruck(w, c);
        s.truck = truck.getUuid();
        s.phaseSince = c.cityTick;
        s.lastTruckPos = null;
        s.stuckSecs = 0;
        w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.5f);
        p.sendMessage(Text.literal("LAUNCH! Hold SPACE to ignite the thrusters, fly at the truck and press R for the EMP shockwave!").formatted(Formatting.RED, Formatting.BOLD), false);
        if (fromRetry) {
            p.sendMessage(Text.literal("Scene restarted cleanly.").formatted(Formatting.GREEN), true);
        }
    }

    /** Gotiya's garage door (local x = -39.5) is where the Monster Truck starts; the driveway to the road has no poles in the way. */
    public static VehicleEntity spawnChargeTruck(ServerWorld w, Company c) {
        VehicleEntity truck = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.GOTIYA), w);
        truck.setOwner(c.owner);
        Vec3d start = CityBuilder.vec(c, -39.5, 1.0, 38.0);
        truck.refreshPositionAndAngles(start.x, start.y, start.z, 180f, 0f);
        truck.setAi(chargeRoute(c));
        w.spawnEntity(truck);
        return truck;
    }

    /** Out of the garage and across the forecourt through the open driveway, over the road and in through the showroom entrance. */
    public static VehicleEntity.Ai chargeRoute(Company c) {
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.add(CityBuilder.vec(c, -39.5, 1.0, 26.0));
        ai.points.add(CityBuilder.vec(c, -39.5, 1.0, 15.0));
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, 5.0));
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, -12.0));
        ai.cruise = 0.92;
        ai.arrive = 3.0;
        ai.stopAtEnd = false;
        return ai;
    }

    /** The last stretch only: used after a retreat, and when the truck had to be put back on the road. */
    public static VehicleEntity.Ai attackRoute(Company c) {
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, 5.0));
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, -12.0));
        ai.cruise = 0.92;
        ai.arrive = 3.0;
        ai.stopAtEnd = false;
        return ai;
    }

    private static VehicleEntity.Ai retreatRoute(Company c) {
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, 10.0));
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, 10.5));
        ai.cruise = 0.7;
        ai.arrive = 3.0;
        ai.stopAtEnd = true;
        return ai;
    }

    private static void killClimaxEntities(ServerWorld w, Company c, State s) {
        if (s.truck != null) {
            Entity e = w.getEntity(s.truck);
            if (e != null) {
                e.discard();
            }
            s.truck = null;
        }
        Box box = new Box(c.origin.getX() + X0, c.origin.getY() - 2, c.origin.getZ() + Z0, c.origin.getX() + X1, c.origin.getY() + 60, c.origin.getZ() + Z1);
        for (VehicleEntity v : w.getEntitiesByClass(VehicleEntity.class, box, e -> e.spec == VehicleSpecs.GOTIYA && e.getMode() == VehicleEntity.MODE_AI)) {
            v.discard();
        }
    }

    private static void climaxTick(ServerWorld w, Company c, ServerPlayerEntity p, State s) {
        Entity te = s.truck == null ? null : w.getEntity(s.truck);
        long now = c.cityTick;
        if (s.victoryAt >= 0) {
            if (now >= s.victoryAt) {
                s.climax = false;
                if (te != null) {
                    w.spawnParticles(ParticleTypes.POOF, te.getX(), te.getY() + 1, te.getZ(), 40, 1.5, 1, 1.5, 0.05);
                    te.discard();
                }
                s.truck = null;
            }
            return;
        }
        if (!(te instanceof VehicleEntity truck) || !truck.isAlive()) {
            if (s.defeated) {
                return;
            }
            s.climax = false;
            return;
        }
        if (truck.hasFlag(VehicleEntity.F_DISABLED)) {
            victory(w, c, p, s, truck);
            return;
        }
        if (c.climaxHp <= 0) {
            if (!s.defeated) {
                s.defeated = true;
                truck.setAi(null);
                truck.stopHard();
                p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 80, 20));
                p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Type #retryscene to restart the finale").formatted(Formatting.YELLOW)));
                p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("SHOWROOM DOWN!").formatted(Formatting.RED, Formatting.BOLD)));
                say("HAHAHA! Mera Monster Truck jeet gaya! Tera showroom ab kabaadi hai, {p}!".replace("{p}", p.getGameProfile().getName()));
            }
            return;
        }
        if (now >= s.nextBoast) {
            say(GotiyaChat.truckBoast(p.getGameProfile().getName(), s.boasts++));
            s.nextBoast = now + 20 * 12;
        }
        // impact with the glass front
        s.hitCooldown = Math.max(0, s.hitCooldown - 1);
        double lx = truck.getX() - c.origin.getX();
        double lz = truck.getZ() - c.origin.getZ();
        boolean atFront = lz < 0.5 && lz > -16.0 && lx > SX0 - 4 && lx < SX1 + 4;
        if (s.phase == 0 && atFront && s.hitCooldown == 0) {
            impact(w, c, p, truck);
            s.phase = 1;
            s.phaseSince = now;
            s.hitCooldown = 50;
            truck.stopHard();
            truck.setAi(retreatRoute(c));
        } else if (s.phase == 1 && ((truck.ai() != null && truck.ai().finished && s.hitCooldown == 0) || now - s.phaseSince > 20 * 8)) {
            s.phase = 0;
            s.phaseSince = now;
            truck.setAi(attackRoute(c));
            w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.6f);
        }
        // failsafe: the finale must never be trapped behind something in the way (a customer's car, a block the player placed):
        // three seconds without moving a block and the truck is put back on the road in front of the showroom
        if (now % 20 == 0 && s.phase == 0 && !atFront) {
            if (s.lastTruckPos != null && truck.getPos().squaredDistanceTo(s.lastTruckPos) < 1.0) {
                s.stuckSecs++;
            } else {
                s.stuckSecs = 0;
            }
            s.lastTruckPos = truck.getPos();
            if (s.stuckSecs >= 3) {
                s.stuckSecs = 0;
                Tycoon.log("[rival] the monster truck was stuck at " + truck.getBlockPos().toShortString() + ", back on the road");
                Vec3d back = CityBuilder.vec(c, -44.2, 1.0, 8.0);
                truck.refreshPositionAndAngles(back.x, back.y, back.z, 180f, 0f);
                truck.stopHard();
                truck.setAi(attackRoute(c));
            }
        }
        if (now % 6 == 0) {
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, truck.getX(), truck.getY() + 1.2, truck.getZ(), 2, 0.4, 0.2, 0.4, 0.01);
        }
    }

    private static void impact(ServerWorld w, Company c, ServerPlayerEntity p, VehicleEntity truck) {
        Tycoon.log("[rival] monster truck hits the showroom front");
        int bx = truck.getBlockX();
        int by = truck.getBlockY();
        int bz = truck.getBlockZ();
        int broken = 0;
        for (int x = bx - 4; x <= bx + 4; x++) {
            for (int y = by; y <= by + 5; y++) {
                for (int z = bz - 9; z <= bz - 2; z++) {
                    BlockPos b = new BlockPos(x, y, z);
                    BlockState st = w.getBlockState(b);
                    if (!st.isAir() && !st.isOf(Blocks.BEDROCK) && st.getHardness(w, b) >= 0) {
                        if (broken < 140) {
                            w.setBlockState(b, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                            broken++;
                        }
                    }
                }
            }
        }
        c.climaxHp = Math.max(0, c.climaxHp - 14);
        w.createExplosion(null, truck.getX(), truck.getY() + 2, truck.getZ() - 3, 0.1f, World.ExplosionSourceType.NONE);
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, truck.getX(), truck.getY() + 2, truck.getZ() - 4, 2, 1.0, 1.0, 1.0, 0.0);
        w.spawnParticles(ParticleTypes.CLOUD, truck.getX(), truck.getY() + 2, truck.getZ() - 4, 60, 3, 1.5, 3, 0.1);
        w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4f, 0.7f);
        w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 4f, 0.7f);
        p.sendMessage(Text.literal("Showroom hit! " + c.climaxHp + "% left - EMP the truck with R!").formatted(Formatting.RED), true);
        Tycoon.sync(p, c);
    }

    private static void victory(ServerWorld w, Company c, ServerPlayerEntity p, State s, VehicleEntity truck) {
        Tycoon.log("[rival] the EMP disabled the monster truck - victory");
        s.victoryAt = c.cityTick + 20 * 12;
        c.climaxWon = true;
        s.cryUntil = c.cityTick + 20 * 20;
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 90, 30));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("The EMP killed Gotiya's Monster Truck! Anant Tesla Showroom is #1!").formatted(Formatting.GOLD)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("VICTORY!").formatted(Formatting.AQUA, Formatting.BOLD)));
        // (his crying line is said once by onResolved when the finale stops counting as an active sabotage)
        w.spawnParticles(ParticleTypes.FIREWORK, truck.getX(), truck.getY() + 6, truck.getZ(), 120, 6, 4, 6, 0.15);
        w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 80, 2, 1.5, 2, 0.4);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 2f, 1f);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, SoundCategory.PLAYERS, 2f, 1f);
        Tycoon.sync(p, c);
    }

    // ============================================================================================================ EMP
    public static int empCooldown(ServerPlayerEntity p) {
        Long last = LAST_EMP.get(p.getUuid());
        if (last == null || Tycoon.server == null) {
            return 0;
        }
        return (int) Math.max(0, EMP_COOLDOWN - (Tycoon.server.getTicks() - last));
    }

    public static void fireEmp(ServerPlayerEntity p) {
        Company c = Tycoon.companyOf(p);
        if (c == null || !c.cityBuilt) {
            return;
        }
        if (!(p.getVehicle() instanceof VehicleEntity v) || v.spec != VehicleSpecs.ROADSTER) {
            p.sendMessage(Text.literal("The EMP shockwave belongs to the Flying Roadster (Level 5).").formatted(Formatting.GRAY), true);
            return;
        }
        if (empCooldown(p) > 0) {
            p.sendMessage(Text.literal("EMP recharging... " + (empCooldown(p) / 20 + 1) + "s").formatted(Formatting.GRAY), true);
            return;
        }
        LAST_EMP.put(p.getUuid(), (long) Tycoon.server.getTicks());
        ServerWorld w = (ServerWorld) p.getWorld();
        Vec3d at = v.getPos().add(0, 0.6, 0);
        w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 3f, 0.6f);
        w.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 2f, 1.8f);
        // an expanding ring of electric particles for ~1 second
        for (int step = 0; step < 14; step++) {
            final int st = step;
            later(step, () -> ring(w, at, 3.0 + st * 4.2));
        }
        boolean hit = false;
        for (VehicleEntity t : w.getEntitiesByClass(VehicleEntity.class, new Box(at, at).expand(62), e -> e.spec == VehicleSpecs.GOTIYA && e.getMode() == VehicleEntity.MODE_AI)) {
            t.setFlag(VehicleEntity.F_DISABLED, true);
            t.stopHard();
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, t.getX(), t.getY() + 2, t.getZ(), 80, 2.5, 2, 2.5, 0.3);
            hit = true;
        }
        p.sendMessage(Text.literal(hit ? "EMP HIT! The Monster Truck is fried!" : "EMP fizzled - fly closer to Gotiya's truck (within 60 blocks).").formatted(hit ? Formatting.GREEN : Formatting.YELLOW), true);
    }

    private static void ring(ServerWorld w, Vec3d at, double r) {
        ParticleEffect e = ParticleTypes.ELECTRIC_SPARK;
        int n = (int) Math.min(120, 18 + r * 3);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            w.spawnParticles(e, at.x + Math.cos(a) * r, at.y, at.z + Math.sin(a) * r, 1, 0, 0.0, 0, 0.0);
            if (i % 3 == 0) {
                w.spawnParticles(ParticleTypes.END_ROD, at.x + Math.cos(a) * r, at.y + 0.3, at.z + Math.sin(a) * r, 1, 0, 0, 0, 0.0);
            }
        }
    }

    // ============================================================================================================ #retryscene
    public static void retryScene(ServerPlayerEntity p) {
        Company c = Tycoon.companyOf(p);
        if (c == null || !c.cityBuilt) {
            p.sendMessage(Text.literal("Build your city first: /tycoon start").formatted(Formatting.RED), false);
            return;
        }
        if (CityBuilder.busy()) {
            p.sendMessage(Text.literal("The city is busy, try #retryscene again in a moment.").formatted(Formatting.YELLOW), true);
            return;
        }
        ServerWorld w = Tycoon.worldOf(c);
        State s = state(c);
        p.sendMessage(Text.literal("#retryscene: repairing the showroom and restarting the scene...").formatted(Formatting.AQUA, Formatting.BOLD), false);
        p.stopRiding();
        s.pendingAt = -1;
        killClimaxEntities(w, c, s);
        s.climax = false;
        c.climaxHp = 100;
        c.climaxWon = false;
        boolean finale = c.level >= 5;
        CityBuilder.rebuildAll(p, c, () -> {
            if (finale) {
                startClimax(w, c, p, s, true);
            } else {
                c.sabTriggered &= ~(1 << c.level);
                s.seen = 0;
                CityBuilder.teleportHome(p, c);
                trigger(w, c, p, c.level);
            }
            Tycoon.sync(p, c);
        });
    }
}
