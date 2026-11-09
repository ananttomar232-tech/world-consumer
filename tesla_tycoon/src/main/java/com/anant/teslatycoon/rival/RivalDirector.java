package com.anant.teslatycoon.rival;

import static com.anant.teslatycoon.city.CityLayout.*;

import com.anant.teslatycoon.block.NeonColor;
import com.anant.teslatycoon.city.Buildings;
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
        // the alarm (stage 1): the gate is rammed until the player visits the roof
        boolean alarm;
        long alarmStart;
        int ramPhase;                   // 0 charging the gate, 1 backing up, 2 pausing
        long ramSince;
        int ramCount;
        int liftCharge;
        boolean lifting;
        int liftTicks;
        long battleAt = -1;
        boolean onRoof;
        final List<Dent> dents = new ArrayList<>();
    }

    /** A bit of the gate that is blown out for a moment when the truck hits it. */
    private static final class Dent {
        final BlockPos pos;
        final BlockState state;
        final long restoreAt;

        Dent(BlockPos pos, BlockState state, long restoreAt) {
            this.pos = pos;
            this.state = state;
            this.restoreAt = restoreAt;
        }
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
            SuitedNpcEntity g = ShowroomDirector.spawnNpc(w, CityBuilder.vec(c, -40.5, 1.0, 20.5 + GOT_DZ), 180f, SuitedNpcEntity.GOTIYA, 4, "Seth Gotiya");
            g.faceTowards(180f);
            s.gotiya = g.getUuid();
        }
        Entity t = s.showTruck == null ? null : w.getEntity(s.showTruck);
        if (t == null || !t.isAlive()) {
            VehicleEntity truck = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.GOTIYA), w);
            truck.setMode(VehicleEntity.MODE_DISPLAY);
            Vec3d pos = CityBuilder.vec(c, -26.0, 1.0, 40.0 + GOT_DZ);
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
        if (s.pendingAt >= 0 && now >= s.pendingAt && !(s.pendingLevel == 5 && CityBuilder.busyUrgent())) {
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
        // after a reload the finale may have been running: pick it up again (never leave the story without its ending)
        if (c.level >= 5 && !c.climaxWon && (c.sabTriggered & (1 << 5)) != 0 && !s.alarm && !s.climax && s.pendingAt < 0 && now > 100 && !CityBuilder.busyUrgent()) {
            startAlarm(w, c, p, s, true);
        }
        if (c.level >= 5) {
            liftTick(w, c, p, s);
        }
        if (s.alarm || s.climax) {
            alarmTick(w, c, p, s);
        }
        if (s.alarm) {
            gateRamTick(w, c, p, s);
        }
        if (s.climax) {
            climaxTick(w, c, p, s);
        }
    }

    /** 0 quiet, 1 alarm + gate ramming, 2 the Monster Truck attack (drives the HUD's red alert). */
    public static int alarmStage(Company c) {
        State s = STATES.get(c.owner);
        if (s == null) {
            return 0;
        }
        return s.climax ? 2 : (s.alarm ? 1 : 0);
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
                    startAlarm(w, c, p, s, false);
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
        int[][] patches = {{-56, 0}, {-14, 5}, {20, 13}, {-3, 18}, {53, -24}, {-62, -43}};
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
        int[][] walls = {{-4, 0, 2, 9}, {30, 13, 2, 9}, {52, -22, 7, 2}};     // x, z, width(x), depth(z)
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
        w.playSound(null, CityBuilder.pos(c, 0, 0, 5), SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.BLOCKS, 2f, 0.6f);
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
                s.pendingAt = c.cityTick + 20 * 5;
                s.pendingLevel = 5;
                say("FLYING ROADSTER?! Mere engine ko takkar dega udhne wali gaadi se?! Ruk, main Monster Truck nikalta hoon!");
            }
            case HYDRAULICS -> p.sendMessage(Text.literal("Jump Hydraulics + Armor Tires fitted: press SPACE to hop, and spikes get crushed.").formatted(Formatting.AQUA), false);
            case SOLAR -> p.sendMessage(Text.literal("Tesla Solar Roof + MegaPack online: the neon is back!").formatted(Formatting.AQUA), false);
            case RAM -> p.sendMessage(Text.literal("Beast Ram Mode unlocked. Drive the Cybertruck, press V, and smash 2 roadblocks.").formatted(Formatting.AQUA), false);
            case STARLINK -> p.sendMessage(Text.literal("Starlink dish installed. Finish the GPS counter: Billboard pack + " + Company.BILLBOARDS_NEEDED + " billboards put up around the city.").formatted(Formatting.AQUA), false);
            case BILLBOARDS -> p.sendMessage(Text.literal("Marketing pack ready: drive out to the billboards on the highway turns around the city and right-click them.").formatted(Formatting.AQUA), false);
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

    // ============================================================================================================ the finale
    /** The helipad centre (world), where the red Flying Roadster waits. */
    private static Vec3d helipad(Company c) {
        return CityBuilder.vec(c, PAD_X + 0.5, FLOOR_H * 5 + 1.0, HELI_Z + 0.5);
    }

    /** Where the lobby launch pad puts you: the roof, four blocks from the Roadster. */
    private static Vec3d landing(Company c) {
        return CityBuilder.vec(c, PAD_X - 3.5, FLOOR_H * 5 + 1.0, HELI_Z + 2.5);
    }

    private static boolean onRoof(Company c, ServerPlayerEntity p) {
        double lx = p.getX() - c.origin.getX();
        double lz = p.getZ() - c.origin.getZ();
        double ly = p.getY() - c.origin.getY();
        return ly >= FLOOR_H * 5 + 0.6 && ly < FLOOR_H * 5 + 14 && lx > SX0 && lx < SX1 && lz > SZ0 && lz < SZ1;
    }

    /** The red Roadster waits on the rooftop helipad, fuelled and ready (one only). */
    private static void spawnRoadster(ServerWorld w, Company c) {
        Vec3d pad = helipad(c);
        for (VehicleEntity old : w.getEntitiesByClass(VehicleEntity.class, new Box(pad.x - 16, pad.y - 3, pad.z - 16, pad.x + 16, pad.y + 8, pad.z + 16),
                e -> e.spec == VehicleSpecs.ROADSTER)) {
            if (old.getPassengerList().isEmpty()) {
                old.discard();
            } else {
                return;
            }
        }
        VehicleEntity car = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.ROADSTER), w);
        car.setMode(VehicleEntity.MODE_DRIVEN);
        car.setOwner(c.owner);
        ShowroomDirector.applyUpgrades(car, c);
        car.refreshPositionAndAngles(pad.x, pad.y, pad.z, 180f, 0f);
        w.spawnEntity(car);
        w.spawnParticles(ParticleTypes.END_ROD, pad.x, pad.y + 1, pad.z, 40, 1.4, 0.8, 1.4, 0.04);
    }

    /** Level 5 is unlocked: the alarm sounds, the gate slams shut and Gotiya rams it with the Monster Truck. */
    private static void startAlarm(ServerWorld w, Company c, ServerPlayerEntity p, State s, boolean resume) {
        Tycoon.log("[rival] the alarm sounds" + (resume ? " (resumed)" : ""));
        s.alarm = true;
        s.climax = false;
        s.defeated = false;
        s.victoryAt = -1;
        s.alarmStart = c.cityTick;
        s.ramPhase = 0;
        s.ramSince = c.cityTick;
        s.ramCount = 0;
        s.battleAt = -1;
        s.onRoof = false;
        s.lifting = false;
        s.nextBoast = c.cityTick + 200;
        s.boasts = 0;
        c.sabTriggered |= 1 << 5;
        c.climaxHp = 100;
        c.climaxWon = false;
        killClimaxEntities(w, c, s);
        ShowroomDirector.clearCustomers(w, c);
        CityBuilder.setGate(w, c, true, () -> {
            Vec3d g = CityBuilder.vec(c, -32, 3, GATE_Z);
            w.playSound(null, g.x, g.y, g.z, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 4f, 0.5f);
            w.playSound(null, g.x, g.y, g.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 3f, 0.6f);
        });
        spawnRoadster(w, c);
        VehicleEntity truck = spawnChargeTruck(w, c);
        s.truck = truck.getUuid();
        s.lastTruckPos = null;
        s.stuckSecs = 0;
        w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.5f);
        if (p != null) {
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 70, 20));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Gotiya is ramming the gate - take the lobby launch pad up to the roof!").formatted(Formatting.YELLOW)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("SECURITY ALARM").formatted(Formatting.RED, Formatting.BOLD)));
            p.sendMessage(Text.literal("ALARM! ").formatted(Formatting.RED, Formatting.BOLD)
                    .append(Text.literal("Step on the glowing launch pad in the showroom lobby: it shoots you up to the rooftop helipad where the red Flying Roadster is fuelled and waiting.").formatted(Formatting.WHITE)), false);
            Tycoon.sync(p, c);
        }
    }

    /** The siren, the flashing beacons, the gate's dents mending. */
    private static void alarmTick(ServerWorld w, Company c, ServerPlayerEntity p, State s) {
        long now = c.cityTick;
        Vec3d centre = CityBuilder.vec(c, -35, 6, -10);
        if (now % 10 == 0) {
            boolean on = (now / 10) % 2 == 0;
            for (int[] b : Buildings.alarmLights(c.level)) {
                BlockPos bp = CityBuilder.pos(c, b[0], b[1], b[2]);
                BlockState st = w.getBlockState(bp);
                if (st.isOf(ModBlocks.NEON)) {
                    w.setBlockState(bp, st.with(com.anant.teslatycoon.block.NeonBlock.LIT, on), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
            }
            if (on) {
                w.spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(1f, 0.1f, 0.1f), 2.2f), centre.x, centre.y + 28, centre.z, 6, 14, 3, 10, 0.0);
            }
        }
        int period = now - s.alarmStart > 20 * 90 ? 80 : 40;
        if (now % period == 0) {
            w.playSound(null, centre.x, centre.y, centre.z, net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.HOSTILE, 5f, 1.5f);
        } else if (now % period == 12) {
            w.playSound(null, centre.x, centre.y, centre.z, net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.HOSTILE, 5f, 0.95f);
        }
        Iterator<Dent> it = s.dents.iterator();
        while (it.hasNext()) {
            Dent d = it.next();
            if (now >= d.restoreAt) {
                if (w.getBlockState(d.pos).isAir()) {
                    w.setBlockState(d.pos, d.state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
                it.remove();
            }
        }
    }

    /** Phase 1: the Monster Truck charges the gate, bounces off, backs up and charges again; nothing is damaged. */
    private static void gateRamTick(ServerWorld w, Company c, ServerPlayerEntity p, State s) {
        long now = c.cityTick;
        // the rooftop: the real attack starts a moment after the player gets there
        if (s.battleAt < 0 && onRoof(c, p)) {
            s.onRoof = true;
            s.battleAt = now + 70;
            say("Oye! Chhat pe chadh gaya? Ab dekh {p}, gate toota ki toota!".replace("{p}", p.getGameProfile().getName()));
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 40, 15));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Mount the Roadster: right-click it, SPACE = thrusters").formatted(Formatting.YELLOW)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("ROOFTOP HELIPAD").formatted(Formatting.AQUA, Formatting.BOLD)));
        }
        if (s.battleAt >= 0 && now >= s.battleAt) {
            startBattle(w, c, p, s, false);
            return;
        }
        Entity te = s.truck == null ? null : w.getEntity(s.truck);
        if (!(te instanceof VehicleEntity truck) || !truck.isAlive()) {
            VehicleEntity nt = spawnChargeTruck(w, c);
            s.truck = nt.getUuid();
            s.ramPhase = 0;
            s.ramSince = now;
            return;
        }
        double lz = truck.getZ() - c.origin.getZ();
        double lx = truck.getX() - c.origin.getX();
        long since = now - s.ramSince;
        switch (s.ramPhase) {
            case 0 -> {
                boolean atGate = lz < 3.6 && lz > -4 && lx > GATE_X0 - 4 && lx < GATE_X1 + 4;
                if (atGate && since > 20) {
                    ramGate(w, c, s, truck);
                    s.ramPhase = 1;
                    s.ramSince = now;
                    truck.stopHard();
                    VehicleEntity.Ai back = new VehicleEntity.Ai();
                    back.points.add(CityBuilder.vec(c, lx, 1.0, 14.0));
                    back.reverse = 48;
                    back.stopAtEnd = true;
                    back.cruise = 0.5;
                    truck.setAi(back);
                } else if (since > 20 * 45) {
                    // stuck somewhere: back to the staging point
                    Vec3d at = CityBuilder.vec(c, -39.5, 1.0, 16.0);
                    truck.refreshPositionAndAngles(at.x, at.y, at.z, 180f, 0f);
                    truck.stopHard();
                    truck.setAi(rushGate(c, -39.5));
                    s.ramSince = now;
                }
            }
            case 1 -> {
                if (since > 56) {
                    s.ramPhase = 2;
                    s.ramSince = now;
                    truck.stopHard();
                    truck.setAi(null);
                    truck.setMode(VehicleEntity.MODE_AI);
                }
            }
            default -> {
                if (since > 20 * 3) {
                    s.ramPhase = 0;
                    s.ramSince = now;
                    truck.setAi(rushGate(c, -42.0 + w.random.nextInt(9)));
                    w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.6f);
                }
            }
        }
        if (now >= s.nextBoast) {
            say(GotiyaChat.truckBoast(p.getGameProfile().getName(), s.boasts++));
            s.nextBoast = now + 20 * 14;
        }
        if (now % 6 == 0) {
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, truck.getX(), truck.getY() + 1.2, truck.getZ(), 2, 0.4, 0.2, 0.4, 0.01);
        }
    }

    /** A short run at the gate from wherever the truck stands on the boulevard. */
    private static VehicleEntity.Ai rushGate(Company c, double x) {
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.add(CityBuilder.vec(c, x, 1.0, 8.0));
        ai.points.add(CityBuilder.vec(c, x, 1.0, 1.0));
        ai.points.add(CityBuilder.vec(c, x, 1.0, -3.0));
        ai.cruise = 0.9;
        ai.arrive = 2.6;
        ai.stopAtEnd = false;
        return ai;
    }

    /** The truck hits the gate: shock, sparks, a few bars blown out and mended again (no damage to anything). */
    private static void ramGate(ServerWorld w, Company c, State s, VehicleEntity truck) {
        s.ramCount++;
        Tycoon.log("[rival] the monster truck rams the gate (" + s.ramCount + ")");
        Vec3d hit = new Vec3d(truck.getX(), truck.getY() + 2, c.origin.getZ() + 0.2);
        w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 1.4f);
        w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 3f, 0.5f);
        w.spawnParticles(ParticleTypes.EXPLOSION, hit.x, hit.y, hit.z, 4, 1.5, 1.0, 0.5, 0.0);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y, hit.z, 70, 2.5, 1.5, 0.6, 0.4);
        w.spawnParticles(ParticleTypes.LAVA, hit.x, hit.y, hit.z, 16, 2.0, 1.0, 0.5, 0.0);
        for (int i = 0; i < 7; i++) {
            BlockPos b = CityBuilder.pos(c, Math.floor(truck.getX() - c.origin.getX()) - 3 + w.random.nextInt(7), 2 + w.random.nextInt(4), GATE_Z);
            BlockState st = w.getBlockState(b);
            if (st.isOf(Blocks.IRON_BARS)) {
                s.dents.add(new Dent(b, st, c.cityTick + 25 + w.random.nextInt(15)));
                w.setBlockState(b, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
    }

    /**
     * Phase 2, started by the player's visit to the roof: the gate shatters, the Monster Truck charges the showroom, the Roadster
     * (already on the helipad) is the only way to stop it: right-click it, SPACE for the thrusters, R for the EMP.
     */
    private static void startBattle(ServerWorld w, Company c, ServerPlayerEntity p, State s, boolean fromRetry) {
        Tycoon.log("[rival] the battle starts");
        s.alarm = false;
        s.climax = true;
        s.defeated = false;
        s.phase = 0;
        s.hitCooldown = 40;
        s.victoryAt = -1;
        s.climaxStart = c.cityTick;
        s.nextBoast = c.cityTick + 120;
        s.boasts = 0;
        s.battleAt = -1;
        c.climaxHp = 100;
        c.climaxWon = false;
        c.sabTriggered |= 1 << 5;
        spawnRoadster(w, c);
        if (fromRetry) {
            p.stopRiding();
            Vec3d at = landing(c);
            p.teleport(w, at.x, at.y + 0.2, at.z, 90f, 12f);
        }
        CityBuilder.breakGate(w, c, null);
        Vec3d g = CityBuilder.vec(c, -38, 3, GATE_Z);
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, g.x, g.y, g.z, 3, 8.0, 1.5, 1.0, 0.0);
        w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.IRON_BARS.getDefaultState()), g.x, g.y, g.z, 90, 9.0, 2.0, 0.8, 0.2);
        w.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 5f, 0.6f);
        w.playSound(null, g.x, g.y, g.z, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.HOSTILE, 4f, 0.5f);
        Entity te = s.truck == null ? null : w.getEntity(s.truck);
        VehicleEntity truck;
        if (te instanceof VehicleEntity t && t.isAlive() && !fromRetry) {
            truck = t;
            truck.setFlag(VehicleEntity.F_DISABLED, false);
            truck.setAi(attackRoute(c));
        } else {
            killClimaxEntities(w, c, s);
            truck = spawnChargeTruck(w, c);
            truck.setAi(chargeRoute(c));
            s.truck = truck.getUuid();
        }
        s.phaseSince = c.cityTick;
        s.lastTruckPos = null;
        s.stuckSecs = 0;
        w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.5f);
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 60, 15));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Right-click the Roadster, hold SPACE for the thrusters, press R for the EMP").formatted(Formatting.YELLOW)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("THE GATE IS DOWN!").formatted(Formatting.RED, Formatting.BOLD)));
        if (fromRetry) {
            p.sendMessage(Text.literal("Scene restarted cleanly.").formatted(Formatting.GREEN), true);
        }
        Tycoon.sync(p, c);
    }

    /** The lobby launch pad: stand on it and a high-speed lift shoots you through the glass shaft to the rooftop helipad. */
    private static void liftTick(ServerWorld w, Company c, ServerPlayerEntity p, State s) {
        double lx = p.getX() - c.origin.getX();
        double ly = p.getY() - c.origin.getY();
        double lz = p.getZ() - c.origin.getZ();
        if (s.lifting) {
            s.liftTicks++;
            Vec3d at = CityBuilder.vec(c, LIFT_X + 0.5, 0, LIFT_Z + 0.5);
            double ddx = (at.x - p.getX()) * 0.25;
            double ddz = (at.z - p.getZ()) * 0.25;
            p.setVelocity(ddx, 1.05, ddz);
            p.velocityModified = true;
            p.fallDistance = 0f;
            w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY(), p.getZ(), 4, 0.5, 0.1, 0.5, 0.02);
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() - 0.5, p.getZ(), 2, 0.4, 0.1, 0.4, 0.0);
            if (ly >= FLOOR_H * 5 - 1.8 || s.liftTicks > 60 || p.getVehicle() != null) {
                s.lifting = false;
                Vec3d land = landing(c);
                p.setVelocity(Vec3d.ZERO);
                p.velocityModified = true;
                p.teleport(w, land.x, land.y, land.z, 90f, 12f);
                p.fallDistance = 0f;
                p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.SLOW_FALLING, 100, 0, false, false));
                w.playSound(null, land.x, land.y, land.z, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.2f, 1.6f);
                w.spawnParticles(ParticleTypes.FIREWORK, land.x, land.y, land.z, 30, 1.0, 0.6, 1.0, 0.1);
                p.sendMessage(Text.literal("Rooftop helipad. The red Flying Roadster is fuelled: right-click it.").formatted(Formatting.AQUA), true);
            }
            return;
        }
        boolean onPad = Math.abs(lx - (LIFT_X + 0.5)) < 1.45 && Math.abs(lz - (LIFT_Z + 0.5)) < 1.45 && ly >= 0.9 && ly < 2.6 && p.getVehicle() == null;
        if (!onPad) {
            s.liftCharge = 0;
            return;
        }
        s.liftCharge++;
        if (s.liftCharge == 3) {
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.5f, 1.6f);
        }
        if (s.liftCharge >= 14) {
            s.liftCharge = 0;
            s.lifting = true;
            s.liftTicks = 0;
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.PLAYERS, 2f, 0.6f);
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 2f, 0.5f);
        }
    }

    /** Gotiya's garage door: the Monster Truck starts inside, drives out over the forecourt and across the boulevard to the gate. */
    public static VehicleEntity spawnChargeTruck(ServerWorld w, Company c) {
        VehicleEntity truck = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.GOTIYA), w);
        truck.setOwner(c.owner);
        Vec3d start = CityBuilder.vec(c, -39.5, 1.0, 46.0);
        truck.refreshPositionAndAngles(start.x, start.y, start.z, 180f, 0f);
        truck.setAi(chargeRoute(c));
        w.spawnEntity(truck);
        return truck;
    }

    /** Out of the garage, over the forecourt and the south pavement, across the boulevard (through the gap in the median) to the gate. */
    public static VehicleEntity.Ai chargeRoute(Company c) {
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.add(CityBuilder.vec(c, -39.5, 1.0, 36.0));
        ai.points.add(CityBuilder.vec(c, -39.5, 1.0, 26.0));
        ai.points.add(CityBuilder.vec(c, -40.0, 1.0, 15.0));
        ai.points.add(CityBuilder.vec(c, -42.0, 1.0, 5.0));
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, -3.0));
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, -12.0));
        ai.cruise = 0.92;
        ai.arrive = 3.0;
        ai.stopAtEnd = false;
        return ai;
    }

    /** The last stretch only (through the hole in the gate): used after a retreat, and when the truck had to be put back on the road. */
    public static VehicleEntity.Ai attackRoute(Company c) {
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, 5.0));
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, -3.0));
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, -12.0));
        ai.cruise = 0.92;
        ai.arrive = 3.0;
        ai.stopAtEnd = false;
        return ai;
    }

    /** After a hit on the showroom the truck backs straight up for a few blocks, then charges again. */
    private static VehicleEntity.Ai retreatRoute(Company c) {
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.add(CityBuilder.vec(c, -44.2, 1.0, 12.0));
        ai.reverse = 55;
        ai.cruise = 0.7;
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

    /** The finale is over: the beacons go dark, the security gate opens again. */
    private static void endFinale(ServerWorld w, Company c) {
        for (int[] b : Buildings.alarmLights(c.level)) {
            BlockPos bp = CityBuilder.pos(c, b[0], b[1], b[2]);
            BlockState st = w.getBlockState(bp);
            if (st.isOf(ModBlocks.NEON)) {
                w.setBlockState(bp, st.with(com.anant.teslatycoon.block.NeonBlock.LIT, !c.powerOut), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        }
        CityBuilder.setGate(w, c, false, null);
    }

    private static void climaxTick(ServerWorld w, Company c, ServerPlayerEntity p, State s) {
        Entity te = s.truck == null ? null : w.getEntity(s.truck);
        long now = c.cityTick;
        if (s.victoryAt >= 0) {
            if (now >= s.victoryAt) {
                s.climax = false;
                s.alarm = false;
                endFinale(w, c);
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
                Vec3d back = CityBuilder.vec(c, -44.2, 1.0, 10.0);
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
        retryScene(p, false);
    }

    /** {@code full}: also replay the alarm and the lift (otherwise the battle restarts straight away on the roof). */
    public static void retryScene(ServerPlayerEntity p, boolean full) {
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
        s.alarm = false;
        s.lifting = false;
        s.battleAt = -1;
        s.dents.clear();
        c.climaxHp = 100;
        c.climaxWon = false;
        boolean finale = c.level >= 5;
        if (finale) {
            c.gateClosed = true;                    // the rebuild puts the intact gate back
            c.gateBroken = false;
        }
        CityBuilder.rebuildAll(p, c, () -> {
            if (finale && full) {
                startAlarm(w, c, p, s, false);
            } else if (finale) {
                startBattle(w, c, p, s, true);
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
