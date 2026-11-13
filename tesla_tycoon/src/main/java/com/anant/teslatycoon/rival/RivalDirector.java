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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Seth Gotiya: the dialogue, the five sabotages and the counters that beat them. One sabotage per showroom level:
 * 1 road spikes, 2 blackout, 3 concrete roadblocks, 4 GPS jam, 5 the Monster Truck attack (answered by the Roadster's EMP).
 */
public final class RivalDirector {
    public static final int EMP_COOLDOWN = 160;

    private static final class State {
        long nextTaunt = 1300;
        // the man himself: errands (placing spikes / roadblocks by hand), spying behind the showroom pillars
        Errand errand;
        int spy;                        // 0 at home, 1 sneaking over, 2 watching, 3 running away
        long spySince;
        long nextSpy = Long.MAX_VALUE;  // set to "now" by the activity chooser
        int act;                        // 0 at home, 2 visiting the player's showroom, 3 walking round his own, 4 showing a car
        long nextAct = 700;
        long lastVisitLine;
        long voiceUntil;                // server tick until which his last clip is still playing
        long lastComplaintReply;
        long lastSabotage = -100000;
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

    /** A row of blocks Gotiya puts down by hand once he has walked past its trigger point. */
    private static final class Row {
        final double tx;
        final double tz;
        final List<BlockPos> cells = new ArrayList<>();
        int state;                      // 0 not reached yet, 1 he is standing at it, 2 placed

        Row(double tx, double tz) {
            this.tx = tx;
            this.tz = tz;
        }
    }

    private static final class Errand {
        final int sabotage;
        final Block block;
        final List<Row> rows = new ArrayList<>();
        long started;

        Errand(int sabotage, Block block) {
            this.sabotage = sabotage;
            this.block = block;
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

    public static void later(int ticks, Runnable r) {
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
        // exactly what a player's line looks like in vanilla chat: <name> message, white
        s.getPlayerManager().broadcast(Text.literal("<Seth_Gotiya> " + msg), false);
    }

    private static GotiyaChat.Ctx ctx(Company c, ServerPlayerEntity p) {
        String car = Text.translatable("entity.tesla_tycoon." + VehicleSpecs.LEVELS.get(c.level - 1).lang).getString();
        return new GotiyaChat.Ctx(p == null ? (c.ownerName == null ? "Anant" : c.ownerName) : p.getGameProfile().getName(), c.money, c.level, car,
                c.activeSabotage(), c.gotiyaPartner, c.climaxWon || c.rivalDestroyed, c.gotiyaTrust);
    }

    /** He says one of his lines of this key out loud: the chat line (placeholders filled) and the voice clip from where he stands. */
    public static void sayKey(ServerWorld w, Company c, ServerPlayerEntity p, String key) {
        GotiyaLines.Line line = GotiyaLines.pick(key);
        speak(w, c, p, line, GotiyaChat.fill(line.text(), ctx(c, p), "yeh"));
    }

    /** A line without a company context (touching his cars). */
    public static void sayVoiced(GotiyaLines.Line line) {
        MinecraftServer srv = Tycoon.server;
        if (srv == null) {
            return;
        }
        ServerPlayerEntity p = srv.getPlayerManager().getPlayerList().isEmpty() ? null : srv.getPlayerManager().getPlayerList().get(0);
        Company c = p == null ? null : Tycoon.companyOf(p);
        if (c == null) {
            say(line.text().replace("{p}", p == null ? "" : p.getGameProfile().getName()));
            return;
        }
        speak(Tycoon.worldOf(c), c, p, line, GotiyaChat.fill(line.text(), ctx(c, p), "yeh"));
    }

    /** Chat + voice. The clip plays at his position (heard like a voice nearby); a far-away player hears it quietly "on the phone". */
    public static void speak(ServerWorld w, Company c, ServerPlayerEntity p, GotiyaLines.Line line, String text) {
        say(text);
        State s = state(c);
        long now = w.getServer().getTicks();
        net.minecraft.sound.SoundEvent clip = com.anant.teslatycoon.registry.ModSounds.voice(line.id());
        if (clip == null || now < s.voiceUntil) {
            return;                                                    // never two of his clips over each other
        }
        s.voiceUntil = now + 20L * Math.max(2, text.length() / 14);
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (g != null) {
            w.playSound(null, g.getX(), g.getY() + 1.6, g.getZ(), clip, SoundCategory.VOICE, 2.4f, 1.0f);
            g.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
            if (p != null && p.squaredDistanceTo(g) < 30 * 30 && !g.isWalking()) {
                double dx = p.getX() - g.getX();
                double dz = p.getZ() - g.getZ();
                g.setHeadYaw((float) (MathHelper.atan2(-dx, dz) * 180.0 / Math.PI));
            }
        }
        if (p != null && (g == null || p.squaredDistanceTo(g) > 34 * 34)) {
            p.playSound(clip, SoundCategory.VOICE, 0.75f, 1.0f);
        }
    }

    /** One of the player's customers complains (chat + her voice), and Gotiya sometimes rubs it in. */
    public static void customerSays(ServerWorld w, Company c, ServerPlayerEntity p, SuitedNpcEntity npc, String name, String key) {
        GotiyaLines.Line line = GotiyaLines.pick(key);
        MinecraftServer srv = w.getServer();
        srv.getPlayerManager().broadcast(Text.literal("<" + name + "> " + line.text()), false);
        net.minecraft.sound.SoundEvent clip = com.anant.teslatycoon.registry.ModSounds.voice(line.id());
        if (clip != null) {
            w.playSound(null, npc.getX(), npc.getY() + 1.6, npc.getZ(), clip, SoundCategory.VOICE, 2.0f, 1.0f);
            if (p != null && p.squaredDistanceTo(npc) > 30 * 30) {
                p.playSound(clip, SoundCategory.VOICE, 0.6f, 1.0f);
            }
        }
        State s = state(c);
        if (!c.gotiyaPartner && key.startsWith("c_") && !key.equals("c_happy") && c.cityTick - s.lastComplaintReply > 20 * 60 && w.random.nextInt(2) == 0) {
            s.lastComplaintReply = c.cityTick;
            later(20 * 7, () -> sayKey(w, c, p, "complaint_reply"));
        }
    }

    /** Anything a player types gets an answer (#retryscene is handled before this is called): what he says depends on what was said,
     * on the story so far and on how the player has treated him. Kind words build trust; enough of it and he joins the business. */
    public static void onPlayerChat(ServerPlayerEntity sender, String text) {
        Company c = Tycoon.companyOf(sender);
        if (c == null || !c.cityBuilt) {
            return;
        }
        ServerWorld w = Tycoon.worldOf(c);
        GotiyaChat.Reply r = GotiyaChat.respond(text, ctx(c, sender));
        c.gotiyaTrust = Math.max(0, Math.min(10, c.gotiyaTrust + r.trustDelta()));
        later(14 + sender.getRandom().nextInt(22), () -> {
            speak(w, c, sender, r.line(), r.text());
            if (r.join() && !c.gotiyaPartner) {
                later(20 * 6, () -> makePartner(w, c, sender));
            }
        });
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
        // no Monster Truck exists before the finale: the only one is the truck he drives out of his garage when the alarm sounds
        Entity t = s.showTruck == null ? null : w.getEntity(s.showTruck);
        if (t != null) {
            t.discard();
        }
        s.showTruck = null;
    }

    /** Singleton: whatever happened (reloads, commands), only the one Monster Truck the story is using may exist. */
    private static void oneTruck(ServerWorld w, Company c, State s) {
        UUID keep = (s.alarm || s.climax) ? s.truck : s.showTruck;
        Box box = new Box(c.origin.getX() + X0, c.origin.getY() - 4, c.origin.getZ() + Z0, c.origin.getX() + X1, c.origin.getY() + 160, c.origin.getZ() + Z1);
        for (VehicleEntity v : w.getEntitiesByClass(VehicleEntity.class, box, e -> e.spec == VehicleSpecs.GOTIYA && e.getCommandTags().contains(STORY_TAG))) {
            if (!v.getUuid().equals(keep)) {
                v.discard();
            }
        }
    }

    /** The Monster Truck the story drives; any other one (summoned, from the item) belongs to the player and is left alone. */
    public static final String STORY_TAG = "tt_story_truck";

    private static Vec3d home(Company c) {
        return CityBuilder.vec(c, -40.5, 1.0, 20.5 + GOT_DZ);
    }

    /** Walks him home along the given points, then he stands in front of his dealership again. */
    private static void walkHome(Company c, State s, SuitedNpcEntity g, List<Vec3d> via, double speed) {
        List<Vec3d> path = new ArrayList<>(via);
        path.add(home(c));
        g.walk(path, speed, () -> g.faceTowards(180f));
    }

    // ------------------------------------------------------------------------------------------------------ sabotage by hand
    private static void addRows(Company c, Errand e, int x0, int x1, int zFrom, int zTo, double walkX) {
        int step = zFrom <= zTo ? 1 : -1;
        for (int z = zFrom; z != zTo + step; z += step) {
            Row r = new Row(c.origin.getX() + walkX, c.origin.getZ() + z + 0.5);
            for (int x = x0; x <= x1; x++) {
                for (int y = 0; y < (e.block == ModBlocks.BARRIER ? 2 : 1); y++) {
                    r.cells.add(CityBuilder.pos(c, x, y, z));
                }
            }
            e.rows.add(r);
        }
    }

    // the belts / walls he puts down: [x0, x1] across the whole boulevard (z 0..21), one WEST and one EAST of the showroom + depot
    static final int[][] SPIKE_BELTS = {{-62, -60}, {-3, -1}};
    static final int[][] WALLS = {{-70, -69}, {-12, -11}};

    /** Sabotage 1: he crosses the whole boulevard west of the showroom laying a full-width belt of spikes behind him, walks along
     * the north pavement past the showroom and lays the second belt east of the depot on his way back: the showroom is cut off from
     * both directions. */
    private static void spikesErrand(ServerWorld w, Company c, State s) {
        Errand e = new Errand(1, ModBlocks.SPIKES);
        addRows(c, e, SPIKE_BELTS[0][0], SPIKE_BELTS[0][1], 21, 0, -58.5);
        addRows(c, e, SPIKE_BELTS[1][0], SPIKE_BELTS[1][1], 0, 21, 0.5);
        s.errand = e;
        e.started = c.cityTick;
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (g == null) {
            return;
        }
        List<Vec3d> out = List.of(CityBuilder.vec(c, -58.5, 1, 24), CityBuilder.vec(c, -58.5, 0, 21.4), CityBuilder.vec(c, -58.5, 0, 0.3), CityBuilder.vec(c, -58.5, 1, -1.6),
                CityBuilder.vec(c, 0.5, 1, -1.6), CityBuilder.vec(c, 0.5, 0, 0.3), CityBuilder.vec(c, 0.5, 0, 21.4), CityBuilder.vec(c, 0.5, 1, 24));
        g.walk(out, 0.17, () -> walkHome(c, s, g, List.of(CityBuilder.vec(c, -20, 1, 24.5)), 0.2));
    }

    /** Sabotage 3: two concrete walls right across the boulevard (both carriageways), one west of the showroom and one east of the
     * depot, stacked block by block as he walks. */
    private static void barricadeErrand(ServerWorld w, Company c, State s) {
        Errand e = new Errand(3, ModBlocks.BARRIER);
        addRows(c, e, WALLS[0][0], WALLS[0][1], 21, 0, -67.5);
        addRows(c, e, WALLS[1][0], WALLS[1][1], 0, 21, -9.5);
        s.errand = e;
        e.started = c.cityTick;
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (g == null) {
            return;
        }
        List<Vec3d> out = List.of(CityBuilder.vec(c, -67.5, 1, 24), CityBuilder.vec(c, -67.5, 0, 21.4), CityBuilder.vec(c, -67.5, 0, 0.3), CityBuilder.vec(c, -67.5, 1, -1.6),
                CityBuilder.vec(c, -9.5, 1, -1.6), CityBuilder.vec(c, -9.5, 0, 0.3), CityBuilder.vec(c, -9.5, 0, 21.4), CityBuilder.vec(c, -9.5, 1, 24));
        g.walk(out, 0.17, () -> walkHome(c, s, g, List.of(CityBuilder.vec(c, -25, 1, 24.5)), 0.2));
    }

    private static void placeRow(ServerWorld w, State s, Errand e, Row r, SuitedNpcEntity g) {
        r.state = 2;
        boolean any = false;
        for (BlockPos b : r.cells) {
            if (w.getBlockState(b).isAir()) {
                w.setBlockState(b, e.block.getDefaultState(), Block.NOTIFY_ALL);
                (e.block == ModBlocks.SPIKES ? s.spikes : s.barriers).add(b);
                any = true;
            }
        }
        if (any && g != null) {
            g.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
            w.playSound(null, g.getX(), g.getY(), g.getZ(), e.block == ModBlocks.SPIKES ? SoundEvents.BLOCK_CHAIN_PLACE : SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.2f, 0.8f);
        }
    }

    private static void errandTick(ServerWorld w, Company c, State s) {
        Errand e = s.errand;
        SuitedNpcEntity g = gotiyaNpc(w, s);
        boolean lost = g == null || c.cityTick - e.started > 20 * 120;
        boolean open = false;
        for (Row r : e.rows) {
            if (r.state == 2) {
                continue;
            }
            if (lost) {
                placeRow(w, s, e, r, null);
                continue;
            }
            double d = Math.hypot(g.getX() - r.tx, g.getZ() - r.tz);
            if (r.state == 0 && d < 0.9) {
                r.state = 1;
            } else if (r.state == 1 && d > 1.6) {
                placeRow(w, s, e, r, g);            // behind him, so he never walls himself in
            }
            open |= r.state != 2;
        }
        if (!lost && !g.isWalking()) {
            for (Row r : e.rows) {
                if (r.state != 2) {
                    placeRow(w, s, e, r, g);
                }
            }
            open = false;
        }
        if (lost && g != null) {
            Vec3d h = home(c);
            g.walk(List.of(), 0.2, null);
            g.refreshPositionAndAngles(h.x, h.y, h.z, 180f, 0f);
        }
        if (!open && (lost || !g.isWalking())) {
            s.errand = null;
            s.nextAct = c.cityTick + 400 + w.random.nextInt(300);
        }
    }

    // ------------------------------------------------------------------------------------------------------ spying
    private static final double[][] SPY_SPOTS = {{-61.6, -4.4}, {-26.4, -4.4}};

    private static void spyTick(ServerWorld w, Company c, ServerPlayerEntity p, State s) {
        SuitedNpcEntity g = gotiyaNpc(w, s);
        long now = c.cityTick;
        if (g == null || g.hasVehicle()) {
            s.spy = 0;
            return;
        }
        List<Vec3d> cross = List.of(CityBuilder.vec(c, -40.5, 1, 22.5), CityBuilder.vec(c, -40.5, 0, 21.2), CityBuilder.vec(c, -40.5, 0, 0.4), CityBuilder.vec(c, -40.5, 1, -1.6));
        switch (s.spy) {
            case 0 -> {
                if (now >= s.nextSpy && s.act == 0 && c.activeSabotage() != 5 && !c.gateClosed) {
                    double[] spot = SPY_SPOTS[w.random.nextInt(SPY_SPOTS.length)];
                    List<Vec3d> path = new ArrayList<>(cross);
                    path.add(CityBuilder.vec(c, spot[0], 1, -1.6));
                    path.add(CityBuilder.vec(c, spot[0], 1, spot[1]));
                    g.walk(path, 0.14, null);
                    Tycoon.log("[rival] Gotiya sneaks over to spy");
                    s.spy = 1;
                    s.spySince = now;
                }
            }
            case 1 -> {
                if (!g.isWalking()) {
                    s.spy = 2;
                    s.spySince = now;
                }
                if (p.squaredDistanceTo(g) < 36) {
                    flee(c, s, g, cross, true);
                }
            }
            case 2 -> {
                double dx = p.getX() - g.getX();
                double dz = p.getZ() - g.getZ();
                g.faceTowards((float) (MathHelper.atan2(-dx, dz) * 180.0 / Math.PI));
                if (dx * dx + dz * dz < 64) {
                    flee(c, s, g, cross, true);
                } else if (now - s.spySince > 20 * 35) {
                    flee(c, s, g, cross, false);
                }
            }
            default -> {
                if (!g.isWalking()) {
                    s.spy = 0;
                    s.nextSpy = Long.MAX_VALUE;
                    s.nextAct = now + 300 + w.random.nextInt(400);
                }
            }
        }
    }

    private static void flee(Company c, State s, SuitedNpcEntity g, List<Vec3d> cross, boolean caught) {
        List<Vec3d> back = new ArrayList<>();
        back.add(new Vec3d(g.getX(), g.getY(), c.origin.getZ() - 1.6));
        List<Vec3d> rev = new ArrayList<>(cross);
        java.util.Collections.reverse(rev);
        back.addAll(rev);
        walkHome(c, s, g, back, caught ? 0.36 : 0.16);
        s.spy = 3;
        if (caught) {
            ServerPlayerEntity owner = Tycoon.server == null ? null : Tycoon.server.getPlayerManager().getPlayer(c.owner);
            sayKey(Tycoon.worldOf(c), c, owner, "caught");
            s.nextTaunt = Math.max(s.nextTaunt, c.cityTick + 600);
        }
    }

    // ------------------------------------------------------------------------------------------------------ what he does all day
    /** Every 15 to 35 seconds at home he picks something to do: spy on the player, walk through the player's showroom like a
     * customer, or walk round his own showroom checking his cars. (Showing a car to a customer of his comes first: RivalShowroom.) */
    private static void chooseActivity(ServerWorld w, Company c, ServerPlayerEntity p, State s, SuitedNpcEntity g) {
        long now = c.cityTick;
        if (s.act != 0 || s.spy != 0 || s.errand != null || now < s.nextAct || g.isWalking() || g.hasVehicle() || now < s.cryUntil
                || com.anant.teslatycoon.cinema.DestroyCinematic.running(c.owner)) {
            return;
        }
        int r = w.random.nextInt(100);
        boolean canCross = !c.gateClosed && c.activeSabotage() != 5;
        if (c.gotiyaPartner) {
            r = 40;                                                    // a partner works in the player's showroom
        }
        if (r < 35 && canCross && !c.gotiyaPartner && !c.rivalDestroyed) {
            s.nextSpy = now;                                           // spyTick takes over
        } else if (r < 70 && canCross) {
            startVisit(c, s, g);
        } else if (!c.rivalDestroyed) {
            startPatrol(c, s, g);
        } else {
            s.nextAct = now + 400;
        }
    }

    private static List<Vec3d> crossing(Company c) {
        return List.of(CityBuilder.vec(c, -40.5, 1, 22.5), CityBuilder.vec(c, -40.5, 0, 21.2), CityBuilder.vec(c, -40.5, 0, 0.4), CityBuilder.vec(c, -40.5, 1, -1.6));
    }

    private static void activityDone(ServerWorld w, Company c, State s, SuitedNpcEntity g) {
        s.act = 0;
        s.nextAct = c.cityTick + 300 + w.random.nextInt(400);
        g.faceTowards(180f);
    }

    /** Through the player's front door, past the cars, the reception and the test-drive bay, and back home. */
    private static void startVisit(Company c, State s, SuitedNpcEntity g) {
        List<Vec3d> path = new ArrayList<>(crossing(c));
        path.add(CityBuilder.vec(c, -45.5, 1, -3.0));
        path.add(CityBuilder.vec(c, -45.5, 1, -8.5));
        path.add(CityBuilder.vec(c, -50.0, 1, -11.0));
        path.add(CityBuilder.vec(c, -44.0, 1, -12.5));
        path.add(CityBuilder.vec(c, -37.5, 1, -10.5));
        path.add(CityBuilder.vec(c, -45.5, 1, -7.0));
        path.add(CityBuilder.vec(c, -45.5, 1, -3.0));
        List<Vec3d> back = new ArrayList<>(crossing(c));
        java.util.Collections.reverse(back);
        path.addAll(back);
        path.add(home(c));
        s.act = 2;
        Tycoon.log("[rival] Gotiya walks over to look round the Tesla showroom");
        ServerWorld w = (ServerWorld) g.getWorld();
        g.walk(path, 0.12, () -> activityDone(w, c, s, g));
    }

    /** Round his own showroom: along the cars, behind them, and back to the forecourt. */
    private static void startPatrol(Company c, State s, SuitedNpcEntity g) {
        List<Vec3d> path = List.of(CityBuilder.vec(c, -40.5, 1, 31.5), CityBuilder.vec(c, -40.5, 1, 40.0), CityBuilder.vec(c, -40.5, 1, 44.5),
                CityBuilder.vec(c, -52.5, 1, 44.5), CityBuilder.vec(c, -52.5, 1, 52.0), CityBuilder.vec(c, -27.5, 1, 52.0), CityBuilder.vec(c, -27.5, 1, 44.5),
                CityBuilder.vec(c, -40.5, 1, 44.5), CityBuilder.vec(c, -40.5, 1, 40.0), CityBuilder.vec(c, -40.5, 1, 31.5), home(c));
        s.act = 3;
        Tycoon.log("[rival] Gotiya walks round his own showroom");
        ServerWorld w = (ServerWorld) g.getWorld();
        g.walk(path, 0.12, () -> activityDone(w, c, s, g));
    }

    private static void activityTick(ServerWorld w, Company c, ServerPlayerEntity p, State s, SuitedNpcEntity g) {
        if (s.act == 2 && p.squaredDistanceTo(g) < 6 * 6 && c.cityTick - s.lastVisitLine > 20 * 30) {
            s.lastVisitLine = c.cityTick;
            sayKey(w, c, p, c.gotiyaPartner ? "partner" : "visit");
        }
    }

    /** A customer of his is waiting at a car: if he is free he walks over (true). */
    public static boolean requestShow(ServerWorld w, Company c, Vec3d at, Vec3d customer) {
        State s = state(c);
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (g == null || s.act != 0 || s.spy != 0 || s.errand != null || s.alarm || s.climax || g.isWalking() || g.hasVehicle()) {
            return false;
        }
        s.act = 4;
        Tycoon.log("[rival] Gotiya goes to show a car to his customer");
        float yaw = (float) (MathHelper.atan2(-(customer.x - at.x), customer.z - at.z) * 180.0 / Math.PI);
        g.walk(List.of(CityBuilder.vec(c, -40.5, 1, 31.5), CityBuilder.vec(c, -40.5, 1, 40.0), CityBuilder.vec(c, -40.5, 1, 44.5),
                new Vec3d(at.x, at.y, c.origin.getZ() + 44.5), at), 0.15, () -> g.faceTowards(yaw));
        return true;
    }

    /** The sales talk is over: back to the forecourt. */
    public static void releaseShow(ServerWorld w, Company c) {
        State s = state(c);
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (s.act != 4 || g == null) {
            return;
        }
        g.walk(List.of(new Vec3d(g.getX(), g.getY(), c.origin.getZ() + 44.5), CityBuilder.vec(c, -40.5, 1, 44.5), CityBuilder.vec(c, -40.5, 1, 40.0),
                CityBuilder.vec(c, -40.5, 1, 31.5), home(c)), 0.15, () -> activityDone(w, c, s, g));
    }

    public static SuitedNpcEntity gotiya(ServerWorld w, Company c) {
        return gotiyaNpc(w, state(c));
    }

    /** He upgrades his dealership whenever the player levels up. */
    public static void onRivalUpgrade(ServerWorld w, Company c, ServerPlayerEntity p) {
        later(20 * 4, () -> sayKey(w, c, p, "rival_up"));
    }

    private static final Map<UUID, Long> LAST_SALE_LINE = new HashMap<>();

    public static void onRivalSale(ServerWorld w, Company c, ServerPlayerEntity p, com.anant.teslatycoon.vehicle.VehicleSpec car) {
        long last = LAST_SALE_LINE.getOrDefault(c.owner, -100000L);
        if (c.cityTick - last > 20 * 75) {
            LAST_SALE_LINE.put(c.owner, c.cityTick);
            sayKey(w, c, p, "rival_sale");
        }
    }

    // ------------------------------------------------------------------------------------------------------ the /destroy cutscene
    /** Everything of the running story stops (alarm, battle, errands, obstacles, blackout) before the cutscene takes over. */
    public static void stopStoryForCutscene(ServerWorld w, Company c) {
        State s = state(c);
        killClimaxEntities(w, c, s);
        s.alarm = false;
        s.climax = false;
        s.defeated = false;
        s.victoryAt = -1;
        s.errand = null;
        s.spy = 0;
        s.act = 0;
        s.pendingAt = -1;
        if (c.gateClosed) {
            CityBuilder.setGate(w, c, false, null);
        }
        clearObstacles(w, c, s);
        if (c.powerOut) {
            c.powerOut = false;
            CityBuilder.setPower(w, c, true);
        }
        Entity t = s.showTruck == null ? null : w.getEntity(s.showTruck);
        if (t != null) {
            t.discard();
        }
    }

    public static void setCrying(Company c, int ticks) {
        state(c).cryUntil = c.cityTick + ticks;
    }

    // ------------------------------------------------------------------------------------------------------ partnership
    /** Empathy won: Seth Gotiya joins the business. No more sabotage, his dealership becomes the joint EV + petrol showroom
     * (rebuilt if it was destroyed), his customers' money flows to the player (+30% income) and he works in the player's showroom. */
    public static void makePartner(ServerWorld w, Company c, ServerPlayerEntity p) {
        if (c.gotiyaPartner) {
            return;
        }
        State s = state(c);
        Tycoon.log("[rival] Seth Gotiya joins the business");
        c.gotiyaPartner = true;
        c.rivalDestroyed = false;
        c.gotiyaTrust = 10;
        // stop whatever he had going
        if (s.alarm || s.climax) {
            killClimaxEntities(w, c, s);
            s.alarm = false;
            s.climax = false;
            c.climaxWon = true;
            CityBuilder.setGate(w, c, false, null);
        }
        s.errand = null;
        s.pendingAt = -1;
        clearObstacles(w, c, s);
        if (c.powerOut) {
            c.powerOut = false;
            CityBuilder.setPower(w, c, true);
        }
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (g != null) {
            g.stopRiding();
            g.setCrying(false);
            s.cryUntil = 0;
            g.setCustomName(Text.literal("Seth Gotiya (Partner)").formatted(Formatting.GOLD));
            w.spawnParticles(ParticleTypes.HEART, g.getX(), g.getY() + 2.2, g.getZ(), 12, 0.5, 0.4, 0.5, 0.1);
        }
        w.spawnParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 2, p.getZ(), 80, 2, 1.5, 2, 0.2);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 2f, 1f);
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 90, 30));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Seth Gotiya joined your business: no more sabotage, +30% income").formatted(Formatting.GOLD)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("NEW PARTNER!").formatted(Formatting.AQUA, Formatting.BOLD)));
        Tycoon.sync(p, c);
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
        if (now >= s.nextTaunt && !s.climax && !s.alarm) {
            sayKey(w, c, p, c.gotiyaPartner ? "partner" : (c.rivalDestroyed ? "destroyed" : (now % 3 == 0 ? "taunt" : "idle")));
            s.nextTaunt = now + 1500 + w.random.nextInt(900);
        }
        if (now % 40 == 3) {
            oneTruck(w, c, s);
        }
        // a sabotage only every 1.5 to 2 minutes, and only when he is at home (not spying, not already out on the road)
        if (c.level == 1 && (c.sabTriggered & 2) == 0 && s.pendingAt < 0 && now >= 20 * 95) {
            s.pendingAt = now;
            s.pendingLevel = 1;
        }
        if (c.gotiyaPartner || c.rivalDestroyed) {
            s.pendingAt = -1;
        }
        boolean free = s.errand == null && s.spy == 0 && (s.act == 0 || s.act == 3) && (g == null || !g.isWalking() || s.act == 3);
        if (s.pendingAt >= 0 && now >= s.pendingAt && (now - s.lastSabotage >= 20 * 90 || s.pendingLevel == 5) && free && s.pendingLevel == c.level) {
            s.pendingAt = -1;
            s.lastSabotage = now;
            trigger(w, c, p, s.pendingLevel);
        } else if (s.pendingAt >= 0 && s.pendingLevel != c.level) {
            s.pendingAt = -1;
        }
        if (s.errand != null) {
            errandTick(w, c, s);
        } else if (!s.alarm && !s.climax) {
            spyTick(w, c, p, s);
            if (g != null) {
                chooseActivity(w, c, p, s, g);
                activityTick(w, c, p, s, g);
            }
        }
        // the last task: once Level 5 stands he comes for the showroom by himself (the launch pad can start it sooner)
        if (c.level >= 5 && !c.climaxWon && !c.gotiyaPartner && !c.rivalDestroyed && (c.sabTriggered & (1 << 5)) == 0 && s.pendingAt < 0 && !s.alarm && !s.climax) {
            s.pendingAt = now + 20 * 45;
            s.pendingLevel = 5;
            sayKey(w, c, p, "taunt");
        }
        int active = c.activeSabotage();
        if (active != s.seen) {
            if (s.seen != 0 && active == 0 && !c.rivalDestroyed && !c.gotiyaPartner) {
                onResolved(w, c, p, s, s.seen);
            }
            s.seen = active;
            Tycoon.sync(p, c);
        }
        if (active == 1 && s.errand == null && now % 200 == 5 && s.spikes.stream().noneMatch(b -> w.getBlockState(b).isOf(ModBlocks.SPIKES))) {
            placeSpikes(w, c, s);
        }
        if (active == 3) {
            if (now - s.sabStarted > 20 * 190 && c.owns(Upgrade.RAM)) {
                c.ramSmashed = Math.max(c.ramSmashed, 2);              // failsafe: never trap the story behind a missed roadblock
            }
        }
        // nothing of his may stay on the road once its sabotage is beaten (also old leftovers after a reload)
        if (now % 200 == 77 && active != 1 && active != 3 && s.errand == null) {
            clearObstacles(w, c, s);
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
        if (p != null) {
            sayKey(w, c, p, "sab_" + k);
            insurance(w, c, p, k);
            if (k < 5 && w.random.nextInt(100) < 85) {
                later(20 * (6 + w.random.nextInt(6)), () -> ShowroomDirector.complain(w, c, p, k));
            }
        } else {
            say(GotiyaChat.sabotageLine(k));
        }
        switch (k) {
            case 1 -> {
                if (p != null && gotiyaNpc(w, s) != null) {
                    spikesErrand(w, c, s);
                } else {
                    placeSpikes(w, c, s);
                }
            }
            case 2 -> {
                c.powerOut = true;
                CityBuilder.setPower(w, c, false);
                lightning(w, CityBuilder.vec(c, -44, FLOOR_H * c.level + 2, -20));
            }
            case 3 -> {
                if (p != null && gotiyaNpc(w, s) != null) {
                    barricadeErrand(w, c, s);
                } else {
                    placeBarricades(w, c, s);
                }
            }
            case 4 -> {
            }
            case 5 -> startAlarm(w, c, p, s, false);
            default -> {
            }
        }
        if (p != null) {
            Tycoon.sync(p, c);
        }
    }

    /** The counters of sabotage k cost this much: if the player cannot pay it, Tesla Insurance covers the rest - every problem
     * Gotiya makes can be fixed straight away. */
    private static void insurance(ServerWorld w, Company c, ServerPlayerEntity p, int k) {
        long need = 0;
        for (Upgrade u : switch (k) {
            case 1 -> new Upgrade[]{Upgrade.HYDRAULICS};
            case 2 -> new Upgrade[]{Upgrade.SOLAR};
            case 3 -> new Upgrade[]{Upgrade.RAM};
            case 4 -> new Upgrade[]{Upgrade.STARLINK, Upgrade.BILLBOARDS};
            default -> new Upgrade[0];
        }) {
            if (!c.owns(u) && !c.pending.contains(u)) {
                need += u.cost;
            }
        }
        if (need > c.money) {
            long pay = need - c.money + Math.max(100, need / 20);
            c.money += pay;
            later(20 * 3, () -> {
                p.sendMessage(Text.literal("Tesla Insurance paid out $" + String.format(java.util.Locale.US, "%,d", pay) + " - buy the counter at the Upgrade Depot!").formatted(Formatting.GREEN, Formatting.BOLD), true);
                w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1f, 0.8f);
            });
            Tycoon.sync(p, c);
        }
    }

    /** Test entry: whatever he put on the road is removed (what a beaten sabotage does). */
    public static void clearObstaclesForTest(ServerWorld w, Company c) {
        clearObstacles(w, c, state(c));
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
        for (int[] belt : SPIKE_BELTS) {                     // (the same two full-width belts he lays by hand)
            for (int x = belt[0]; x <= belt[1]; x++) {
                for (int dz = 0; dz < 22; dz++) {
                    BlockPos b = CityBuilder.pos(c, x, 0, dz);
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
        for (int[] wl : WALLS) {                             // the same two full-width walls he stacks by hand
            for (int x = wl[0]; x <= wl[1]; x++) {
                for (int dz = 0; dz < 22; dz++) {
                    for (int dy = 0; dy < 2; dy++) {
                        BlockPos b = CityBuilder.pos(c, x, dy, dz);
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

    /** Removes everything he put on the road (by scanning the belts and walls, so it works after a reload as well). */
    static void clearObstacles(ServerWorld w, Company c, State s) {
        int n = 0;
        for (int[] belt : SPIKE_BELTS) {
            n += clearArea(w, c, belt[0], belt[1], 0, 0, ModBlocks.SPIKES);
        }
        for (int[] wl : WALLS) {
            n += clearArea(w, c, wl[0], wl[1], 0, 1, ModBlocks.BARRIER);
        }
        n += clearArea(w, c, 20, 21, 0, 1, ModBlocks.BARRIER);                       // (where v3 used to put the second one)
        s.spikes.clear();
        s.barriers.clear();
        if (n > 0) {
            Tycoon.log("[rival] cleared " + n + " obstacle blocks");
        }
    }

    private static int clearArea(ServerWorld w, Company c, int x0, int x1, int y0, int y1, Block block) {
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = -1; z <= 22; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockPos b = CityBuilder.pos(c, x, y, z);
                    if (w.isChunkLoaded(b) && w.getBlockState(b).isOf(block)) {
                        w.setBlockState(b, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                        if (n % 3 == 0) {
                            w.spawnParticles(ParticleTypes.POOF, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.01);
                        }
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static void onResolved(ServerWorld w, Company c, ServerPlayerEntity p, State s, int sab) {
        Tycoon.log("[rival] sabotage " + sab + " countered");
        s.cryUntil = c.cityTick + 20 * 14;
        sayKey(w, c, p, "cry_" + Math.max(1, Math.min(5, sab)));
        s.cleanupAt = -1;
        later(30, () -> clearObstacles(w, c, s));                   // task done: his spikes / walls vanish right away
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
                s.pendingAt = Math.max(c.cityTick + 20 * 20, s.lastSabotage + 20 * (90 + w.random.nextInt(31)));
                s.pendingLevel = u.target;
                say("Level " + u.target + "?! Itni jaldi? Ruk ja {p}... ab dekh mera agla daav!".replace("{p}", p.getGameProfile().getName()));
            }
            case LEVEL5 -> {
                // the finale starts 45 s later by itself (or sooner, from the launch pad)
                s.pendingAt = c.cityTick + 20 * 45;
                s.pendingLevel = 5;
                spawnRoadster(w, c);
                say("FLYING ROADSTER?! Chhat pe chadh ke dikha {p}... mera Monster Truck garam ho raha hai!".replace("{p}", p.getGameProfile().getName()));
                p.sendMessage(Text.literal("The red Flying Roadster is on the roof. Step on the glowing launch pad in the lobby when you are ready.").formatted(Formatting.AQUA), true);
            }
            case HYDRAULICS -> p.sendMessage(Text.literal("Jump Hydraulics + Armor Tires fitted: press SPACE to hop, and spikes get crushed.").formatted(Formatting.AQUA), true);
            case SOLAR -> p.sendMessage(Text.literal("Tesla Solar Roof + MegaPack online: the neon is back!").formatted(Formatting.AQUA), true);
            case RAM -> p.sendMessage(Text.literal("Beast Ram Mode unlocked and armed (B toggles it). Drive the Cybertruck into 2 roadblocks.").formatted(Formatting.AQUA), true);
            case STARLINK -> p.sendMessage(Text.literal("Starlink dish installed. Finish the GPS counter: Billboard pack + " + Company.BILLBOARDS_NEEDED + " billboards put up around the city.").formatted(Formatting.AQUA), true);
            case BILLBOARDS -> p.sendMessage(Text.literal("Marketing pack ready: drive out to the billboards on the highway turns around the city and right-click them.").formatted(Formatting.AQUA), true);
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
        // Beast Ram: bought but still in the bag? Sitting in the Cybertruck fits it.
        if (v.age % 20 == 7 && v.spec.heavy && v.getMode() == VehicleEntity.MODE_DRIVEN && c.pending.contains(Upgrade.RAM)
                && v.getControllingPassenger() instanceof ServerPlayerEntity driver) {
            net.minecraft.item.Item ramItem = com.anant.teslatycoon.registry.ModItems.UPGRADES.get(Upgrade.RAM);
            net.minecraft.item.ItemStack st = new net.minecraft.item.ItemStack(ramItem);
            for (int i = 0; i < driver.getInventory().size(); i++) {
                if (driver.getInventory().getStack(i).isOf(ramItem)) {
                    st = driver.getInventory().getStack(i);
                    break;
                }
            }
            Tycoon.deploy(driver, Upgrade.RAM, st);
        }
        if (v.hasFlag(VehicleEntity.F_RAM) && v.measuredSpeed() > 0.12) {
            ramSmash(w, c, v);
        }
        if (v.getMode() == VehicleEntity.MODE_AI && !v.getCommandTags().contains(STORY_TAG)) {
            squeezePast(w, v);
        }
    }

    private static final Map<Integer, Integer> AI_STUCK = new HashMap<>();

    /** Customers' test drives, robotaxis and the delivery truck must not wait forever behind Gotiya's wall: after 4 s wedged in front
     * of one, the driver finds the gap at the curb (the car is put on the other side). */
    private static void squeezePast(ServerWorld w, VehicleEntity v) {
        if (v.measuredSpeed() > 0.03) {
            AI_STUCK.remove(v.getId());
            return;
        }
        int t = AI_STUCK.merge(v.getId(), 1, Integer::sum);
        if (t < 80) {
            return;
        }
        AI_STUCK.remove(v.getId());
        Vec3d fwd = Vec3d.fromPolar(0, v.getYaw());
        boolean wall = false;
        for (int step = 1; step <= 4 && !wall; step++) {
            Vec3d q = v.getPos().add(fwd.multiply(step));
            wall = w.getBlockState(BlockPos.ofFloored(q.x, v.getY() + 0.5, q.z)).isOf(ModBlocks.BARRIER);
        }
        if (!wall) {
            return;
        }
        for (int d = 5; d <= 8; d++) {
            Vec3d q = v.getPos().add(fwd.multiply(d));
            BlockPos b = BlockPos.ofFloored(q.x, v.getY() + 0.5, q.z);
            if (w.getBlockState(b).isAir() && w.getBlockState(b.up()).isAir()) {
                w.spawnParticles(ParticleTypes.CLOUD, v.getX(), v.getY() + 0.6, v.getZ(), 10, 0.6, 0.3, 0.6, 0.02);
                v.refreshPositionAndAngles(q.x, v.getY(), q.z, v.getYaw(), 0f);
                w.playSound(null, q.x, q.y, q.z, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), SoundCategory.NEUTRAL, 1f, 0.6f);
                return;
            }
        }
    }

    private static void ramSmash(ServerWorld w, Company c, VehicleEntity v) {
        Vec3d fwd = Vec3d.fromPolar(0, v.getYaw());
        int broken = 0;
        for (int step = 0; step <= 4; step++) {
            for (int side = -3; side <= 3; side++) {
                Vec3d p = v.getPos().add(fwd.multiply(1.2 + step)).add(fwd.z * side * -1.0, 0, fwd.x * side);
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
        s.errand = null;
        s.spy = 0;
        VehicleEntity truck = spawnChargeTruck(w, c);
        s.truck = truck.getUuid();
        ensureGotiya(w, c);                         // (removes the parked show truck: only one Monster Truck exists)
        seatGotiya(w, c, s, truck);
        s.lastTruckPos = null;
        s.stuckSecs = 0;
        w.playSound(null, truck.getX(), truck.getY(), truck.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.5f);
        if (p != null) {
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 70, 20));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Gotiya is ramming the gate - get to the Roadster on the roof!").formatted(Formatting.YELLOW)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("SECURITY ALARM").formatted(Formatting.RED, Formatting.BOLD)));
            p.sendMessage(Text.literal("ALARM! ").formatted(Formatting.RED, Formatting.BOLD)
                    .append(Text.literal("Step on the glowing launch pad in the showroom lobby: it shoots you up to the rooftop helipad where the red Flying Roadster is fuelled and waiting.").formatted(Formatting.WHITE)), true);
            Tycoon.sync(p, c);
        }
    }

    /** Seth Gotiya himself sits at the wheel of the Monster Truck for the whole finale. */
    private static void seatGotiya(ServerWorld w, Company c, State s, VehicleEntity truck) {
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (g == null) {
            ensureGotiya(w, c);
            g = gotiyaNpc(w, s);
        }
        if (g != null && g.getVehicle() != truck) {
            g.walk(List.of(), 0.2, null);
            g.stopRiding();
            g.startRiding(truck, true);
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
        if (now % 20 == 9 && s.truck != null && w.getEntity(s.truck) instanceof VehicleEntity tr && tr.isAlive() && !tr.hasFlag(VehicleEntity.F_DISABLED)) {
            seatGotiya(w, c, s, tr);
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
            s.battleAt = now + 20 * 15;
            say("Oye! Chhat pe chadh gaya? Ab dekh {p}, gate toota ki toota!".replace("{p}", p.getGameProfile().getName()));
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 40, 15));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Mount the Roadster: right-click it, SPACE = thrusters").formatted(Formatting.YELLOW)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("ROOFTOP HELIPAD").formatted(Formatting.AQUA, Formatting.BOLD)));
        }
        boolean inRoadster = p.getVehicle() instanceof VehicleEntity pv && pv.spec == VehicleSpecs.ROADSTER;
        if ((s.battleAt >= 0 && now >= s.battleAt) || (inRoadster && now - s.alarmStart > 20 * 6)) {
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
            sayKey(w, c, p, "truck");
            s.boasts++;
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
        ensureGotiya(w, c);
        seatGotiya(w, c, s, truck);
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
            if (!s.alarm && !s.climax && !c.climaxWon && s.victoryAt < 0) {
                trigger(w, c, p, 5);                // the launch: this, and nothing earlier, calls Gotiya's Monster Truck
            }
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.PLAYERS, 2f, 0.6f);
            w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 2f, 0.5f);
        }
    }

    /** Gotiya's garage door: the Monster Truck starts inside, drives out over the forecourt and across the boulevard to the gate. */
    public static VehicleEntity spawnChargeTruck(ServerWorld w, Company c) {
        VehicleEntity truck = new VehicleEntity(ModEntities.vehicle(VehicleSpecs.GOTIYA), w);
        truck.setOwner(c.owner);
        truck.addCommandTag(STORY_TAG);
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
        for (VehicleEntity v : w.getEntitiesByClass(VehicleEntity.class, box, e -> e.spec == VehicleSpecs.GOTIYA && e.getCommandTags().contains(STORY_TAG))) {
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
                SuitedNpcEntity gg = gotiyaNpc(w, s);
                if (gg != null) {
                    walkHome(c, s, gg, List.of(CityBuilder.vec(c, -40.5, 1, 22.5)), 0.16);
                }
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
            Tycoon.log("[rival] the monster truck is gone (" + te + ")");
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
                sayKey(w, c, p, "truck_win");
            }
            return;
        }
        if (now >= s.nextBoast) {
            sayKey(w, c, p, "truck");
            s.boasts++;
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
        SuitedNpcEntity g = gotiyaNpc(w, s);
        if (g != null) {
            g.stopRiding();
            g.refreshPositionAndAngles(truck.getX() + 3.2, truck.getY(), truck.getZ(), 0f, 0f);
        }
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
        for (VehicleEntity t : w.getEntitiesByClass(VehicleEntity.class, new Box(at, at).expand(120, 400, 120), e -> e.spec == VehicleSpecs.GOTIYA && e.getMode() == VehicleEntity.MODE_AI)) {
            t.setFlag(VehicleEntity.F_DISABLED, true);
            t.stopHard();
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, t.getX(), t.getY() + 2, t.getZ(), 80, 2.5, 2, 2.5, 0.3);
            hit = true;
        }
        p.sendMessage(Text.literal(hit ? "EMP HIT! The Monster Truck is fried!" : "EMP fizzled - fly back over the showroom, Gotiya's truck is too far away.").formatted(hit ? Formatting.GREEN : Formatting.YELLOW), true);
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
            p.sendMessage(Text.literal("Build your city first: /tycoon start").formatted(Formatting.RED), true);
            return;
        }
        if (CityBuilder.busy()) {
            p.sendMessage(Text.literal("The city is busy, try #retryscene again in a moment.").formatted(Formatting.YELLOW), true);
            return;
        }
        ServerWorld w = Tycoon.worldOf(c);
        State s = state(c);
        p.sendMessage(Text.literal("#retryscene: repairing the showroom and restarting the scene...").formatted(Formatting.AQUA, Formatting.BOLD), true);
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
