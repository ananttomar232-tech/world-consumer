package com.anant.teslatycoon.city;

import static com.anant.teslatycoon.city.CityLayout.*;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.block.NeonBlock;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.net.ModNetwork;
import com.anant.teslatycoon.npc.ShowroomDirector;
import com.anant.teslatycoon.npc.SuitedNpcEntity;
import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.rival.RivalDirector;
import com.anant.teslatycoon.sign.PosterEntity;
import com.anant.teslatycoon.sign.SignboardEntity;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Builds the city in time slices (about 11 ms of every tick at most) and manages its lifecycle. A job is the plan cut into
 * 16 x 16 columns; the columns are built one after the other, nearest to the showroom first, while the chunks a few columns ahead
 * are loaded in the background with temporary forced-chunk tickets (so the server never stalls on terrain generation).
 */
public final class CityBuilder {
    private static final long BUDGET_URGENT_NANOS = 14_000_000L;
    private static final long BUDGET_BACKGROUND_NANOS = 19_000_000L;
    private static final int SETS_PER_TICK = 6000;
    private static final int PRELOAD = 18;
    /** The district around the showroom (city-local x / z): this part of the city is built first and unlocks the game. */
    private static final int CORE_X0 = -112;
    private static final int CORE_X1 = 84;
    private static final int CORE_Z0 = -64;
    private static final int CORE_Z1 = 90;

    private static final class ChunkWork {
        final int cx;
        final int cz;
        final List<Plan.Op> ops = new ArrayList<>(4);
        int opIndex;
        long cursor;
        boolean forced;

        ChunkWork(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    private static final class Job {
        final ServerWorld world;
        final BlockPos origin;
        final List<ChunkWork> pending;
        final int total;
        final boolean neonOnly;
        final boolean lit;
        final boolean load;               // force-load the chunks (false: only the chunks that happen to be loaded)
        final Runnable done;
        final ServerBossBar bar;
        final float barFrom;
        final float barTo;
        long setsDone;
        long nanos;

        Job(ServerWorld world, BlockPos origin, List<ChunkWork> pending, boolean neonOnly, boolean lit, boolean load, Runnable done, ServerBossBar bar, float barFrom, float barTo) {
            this.world = world;
            this.origin = origin;
            this.pending = pending;
            this.total = Math.max(1, pending.size());
            this.neonOnly = neonOnly;
            this.lit = lit;
            this.load = load;
            this.done = done;
            this.bar = bar;
            this.barFrom = barFrom;
            this.barTo = barTo;
        }
    }

    /** Edits (floors, counters, the gate ...) go first; the metropolis around the district is built in the background. */
    private static final Deque<Job> URGENT = new ArrayDeque<>();
    private static final Deque<Job> BACKGROUND = new ArrayDeque<>();
    private static final java.util.Map<java.util.UUID, java.util.Map<Long, List<Plan.Op>>> NEON_INDEX = new java.util.HashMap<>();

    private CityBuilder() {}

    public static void init() {
        // nothing hostile spawns or loads inside the city
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if ((entity instanceof HostileEntity || entity instanceof Monster) && Tycoon.state() != null && insideAnyCity(world, entity.getBlockPos())) {
                entity.discard();
            }
        });
        // chunks that were not loaded when the power went off / on get their neon fixed when they load
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> reconcileNeon(world, chunk));
    }

    /** Test / scripting entry: builds the district around the showroom for a company without a player and calls {@code done} when finished. */
    public static void buildForTest(ServerWorld world, Company c, BlockPos origin, Runnable done) {
        c.origin = origin;
        c.dimension = world.getRegistryKey().getValue().toString();
        NEON_INDEX.remove(c.owner);
        List<ChunkWork> core = split(works(fullPlan(c).ops, origin, false), origin, true);
        URGENT.add(new Job(world, origin, core, false, true, true, () -> {
            c.cityBuilt = true;
            spawnEntities(world, c);
            if (done != null) {
                done.run();
            }
        }, null, 0f, 1f));
    }

    public static void shutdown() {
        for (Job j : URGENT) {
            release(j);
        }
        for (Job j : BACKGROUND) {
            release(j);
        }
        URGENT.clear();
        BACKGROUND.clear();
        NEON_INDEX.clear();
    }

    private static void release(Job j) {
        for (ChunkWork cw : j.pending) {
            if (cw.forced) {
                j.world.setChunkForced(cw.cx, cw.cz, false);
                cw.forced = false;
            }
        }
    }

    public static boolean busy() {
        return !URGENT.isEmpty() || !BACKGROUND.isEmpty();
    }

    /** True while the first part (the district around the showroom) is still being built. */
    public static boolean busyUrgent() {
        return !URGENT.isEmpty();
    }

    public static boolean insideAnyCity(ServerWorld world, BlockPos pos) {
        for (Company c : Tycoon.state().all()) {
            if (!c.cityBuilt || !c.dimension.equals(world.getRegistryKey().getValue().toString())) {
                continue;
            }
            int lx = pos.getX() - c.origin.getX();
            int lz = pos.getZ() - c.origin.getZ();
            if (lx >= X0 - 8 && lx <= X1 + 8 && lz >= Z0 - 8 && lz <= Z1 + 8) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------ plans
    /** Everything that exists for a showroom with {@code level} floors: terrain, street furniture, skyline, buildings. */
    public static Plan fullPlan(Company c) {
        Plan all = new Plan();
        all.ops.addAll(CityLayout.terrain().ops);
        all.ops.addAll(CityLayout.furniture().ops);
        all.ops.addAll(Skyline.build().ops);
        all.ops.addAll(buildingsPlan(c).ops);
        return all;
    }

    /** The showroom compound, the depot, Gotiya's station, the billboards and the finale's structures (no ground, no skyline). */
    public static Plan buildingsPlan(Company c) {
        Plan all = new Plan();
        for (int k = 1; k <= c.level; k++) {
            all.ops.addAll(Buildings.showroomFloor(k).ops);
        }
        all.ops.addAll(Buildings.showroomRoof(c.level, c).ops);
        if (c.level >= 5) {
            all.ops.addAll(Buildings.lift().ops);
        }
        all.ops.addAll(Buildings.shop().ops);
        all.ops.addAll(Buildings.petrol().ops);
        for (int i = 0; i < Buildings.BILLBOARDS.length; i++) {
            int[] b = Buildings.BILLBOARDS[i];
            all.ops.addAll(Buildings.billboard(b[0], b[1], Buildings.billboardFacing(i)).ops);
        }
        all.ops.addAll(Buildings.upgrades(c).ops);
        if (c.level >= 5 && c.gateClosed) {
            all.ops.addAll(Buildings.gate(true).ops);
            if (c.gateBroken) {
                all.ops.addAll(Buildings.gateHole().ops);
            }
        }
        return all;
    }

    /** What the power switch has to touch: lamps, signs, the showroom's glowing details (the skyline's windows are not on this grid). */
    private static Plan neonPlan(Company c) {
        Plan all = new Plan();
        all.ops.addAll(CityLayout.furniture().ops);
        all.ops.addAll(buildingsPlan(c).ops);
        return all;
    }

    public static BlockPos pos(Company c, double lx, double ly, double lz) {
        return Plan.world(c.origin, (int) Math.floor(lx), (int) Math.floor(ly), (int) Math.floor(lz));
    }

    public static Vec3d vec(Company c, double lx, double ly, double lz) {
        return new Vec3d(c.origin.getX() + lx, c.origin.getY() + ly, c.origin.getZ() + lz);
    }

    // ------------------------------------------------------------------------------------------------------ chunk work lists
    /** The plan cut into 16 x 16 columns (the same op order inside every column), sorted nearest to the showroom first. */
    private static List<ChunkWork> works(List<Plan.Op> ops, BlockPos o, boolean neonOnly) {
        java.util.Map<Long, ChunkWork> map = new java.util.HashMap<>();
        for (Plan.Op op : ops) {
            if (neonOnly && op.neon == null) {
                continue;
            }
            int cx1 = Math.floorDiv(o.getX() + op.x1, 16);
            int cx2 = Math.floorDiv(o.getX() + op.x2, 16);
            int cz1 = Math.floorDiv(o.getZ() + op.z1, 16);
            int cz2 = Math.floorDiv(o.getZ() + op.z2, 16);
            for (int cx = cx1; cx <= cx2; cx++) {
                for (int cz = cz1; cz <= cz2; cz++) {
                    int minX = cx * 16 - o.getX();
                    int minZ = cz * 16 - o.getZ();
                    Plan.Op piece = op.clip(minX, minX + 15, minZ, minZ + 15);
                    if (piece != null) {
                        final int fx = cx;
                        final int fz = cz;
                        map.computeIfAbsent(ChunkPos.toLong(cx, cz), k -> new ChunkWork(fx, fz)).ops.add(piece);
                    }
                }
            }
        }
        List<ChunkWork> list = new ArrayList<>(map.values());
        double hx = o.getX() - 45.0;
        double hz = o.getZ() - 20.0;
        list.sort(java.util.Comparator.comparingDouble(w -> Math.hypot(w.cx * 16 + 8 - hx, w.cz * 16 + 8 - hz)));
        return list;
    }

    private static boolean isCore(ChunkWork w, BlockPos o) {
        int minX = w.cx * 16 - o.getX();
        int minZ = w.cz * 16 - o.getZ();
        return minX + 15 >= CORE_X0 && minX <= CORE_X1 && minZ + 15 >= CORE_Z0 && minZ <= CORE_Z1;
    }

    /** The columns of the district (core = true) or of the rest of the city. */
    private static List<ChunkWork> split(List<ChunkWork> all, BlockPos o, boolean core) {
        List<ChunkWork> out = new ArrayList<>();
        for (ChunkWork w : all) {
            if (isCore(w, o) == core) {
                out.add(w);
            }
        }
        return out;
    }

    /** A small edit near the showroom: runs before the background build. */
    private static void edit(ServerWorld w, Company c, Plan plan, boolean lit, Runnable done) {
        URGENT.add(new Job(w, c.origin, works(plan.ops, c.origin, false), false, lit, true, done, null, 0f, 1f));
    }

    // ------------------------------------------------------------------------------------------------------ lifecycle
    public static void start(ServerPlayerEntity p) {
        Company existing = Tycoon.state().find(p.getUuid());
        if (existing != null && existing.cityBuilt) {
            p.sendMessage(Text.literal("Your city already exists. Teleporting you to the showroom.").formatted(Formatting.AQUA), true);
            teleportHome(p, existing);
            return;
        }
        if (busy()) {
            p.sendMessage(Text.literal("A city is being built right now, try again in a few seconds.").formatted(Formatting.YELLOW), true);
            return;
        }
        ServerWorld world = p.getServerWorld();
        Company c = existing != null ? existing : Tycoon.state().company(p.getUuid(), p.getGameProfile().getName());
        c.origin = new BlockPos(net.minecraft.util.math.MathHelper.floor(p.getX()), net.minecraft.util.math.MathHelper.floor(p.getY() - 1.0E-3), net.minecraft.util.math.MathHelper.floor(p.getZ()));
        c.dimension = world.getRegistryKey().getValue().toString();
        c.level = 1;
        c.owned.clear();
        c.money = 0;
        c.pending.clear();
        c.carry = 0;
        c.sabTriggered = 0;
        c.climaxWon = false;
        c.billboardsPlaced = 0;
        c.billboardMask = 0;
        c.ramSmashed = 0;
        c.powerOut = false;
        c.gateClosed = false;
        c.gateBroken = false;
        c.climaxHp = 100;
        c.cityBuilt = false;
        NEON_INDEX.remove(c.owner);
        // the neon city looks best after dark; the daylight cycle stays untouched, only the time is set
        world.setTimeOfDay(13400);
        p.sendMessage(Text.literal("[Tesla Tycoon] ").formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal("Building Anant Tesla City... the showroom district first, then the skyscrapers (time set to night so the neon shines)").formatted(Formatting.WHITE)), true);
        ServerBossBar bar = new ServerBossBar(Text.literal("Building Anant Tesla City"), BossBar.Color.BLUE, BossBar.Style.PROGRESS);
        bar.addPlayer(p);
        c.cityComplete = false;
        List<ChunkWork> all = works(fullPlan(c).ops, c.origin, false);
        List<ChunkWork> core = split(all, c.origin, true);
        List<ChunkWork> rest = split(all, c.origin, false);
        float share = core.size() / (float) Math.max(1, all.size());
        URGENT.add(new Job(world, c.origin, core, false, true, true, () -> {
            c.cityBuilt = true;
            c.cityTick = 0;
            ModNetwork.sendState(p, c, 0);
            spawnEntities(world, c, false);
            ShowroomDirector.reset(c);
            teleportHome(p, c);
            p.sendMessage(Text.literal("Welcome to Anant Tesla Showroom! ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal("Customers walk in by themselves. Right-click the shining car to test drive it. Buy upgrades on the pedestals of the Upgrade Depot next door. RIGHT SHIFT shows the guide.").formatted(Formatting.WHITE)), true);
            ShowroomDirector.giveStarterPosters(p, 2);
            Tycoon.sync(p, c);
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("ANANT TESLA SHOWROOM").formatted(Formatting.AQUA, Formatting.BOLD)));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Level 1 - Tesla Classic Compact").formatted(Formatting.GOLD)));
            bar.setName(Text.literal("The skyscrapers are rising around you..."));
        }, bar, 0f, share));
        BACKGROUND.add(new Job(world, c.origin, rest, false, true, true, () -> {
            bar.clearPlayers();
            c.cityComplete = true;
            // the billboards live far out: their signs are created once their chunks exist
            spawnBillboardSigns(world, c);
            p.sendMessage(Text.literal("The metropolis is complete: the highway ring, 24 intersections and the skyline are ready to drive through.").formatted(Formatting.AQUA), true);
            Tycoon.log("[city] metropolis complete");
        }, bar, share, 1f));
    }

    public static void teleportHome(ServerPlayerEntity p, Company c) {
        ServerWorld w = Tycoon.worldOf(c);
        Vec3d v = vec(c, -45.5, 1, -2.5);
        p.teleport(w, v.x, v.y, v.z, 180f, 8f);
    }

    /** Adds the next glass floor on top of the showroom, moves the roof and the banner up, re-seats the exhibits. */
    public static void upgradeShowroom(ServerWorld w, Company c, ServerPlayerEntity p) {
        Plan plan = new Plan();
        plan.ops.addAll(Buildings.showroomFloor(c.level).ops);
        plan.ops.addAll(Buildings.showroomRoof(c.level, c).ops);
        if (c.level >= 4) {
            plan.ops.addAll(Buildings.upgrades(c).ops);
        }
        if (c.level >= 5) {
            plan.ops.addAll(Buildings.lift().ops);
        }
        NEON_INDEX.remove(c.owner);
        edit(w, c, plan, !c.powerOut, () -> {
            respawnBanner(w, c);
            ShowroomDirector.layoutDisplays(w, c);
            ShowroomDirector.spawnPosters(w, c);
            w.playSound(null, vec(c, -44, 1, -20).x, vec(c, -44, 1, -20).y, vec(c, -44, 1, -20).z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.AMBIENT, 2f, 1f);
            if (c.level >= 4) {
                com.anant.teslatycoon.npc.RobotaxiDirector.onLevel4(w, c);
            }
            if (p != null) {
                p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 70, 20));
                p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("SHOWROOM LEVEL " + c.level).formatted(Formatting.AQUA, Formatting.BOLD)));
                p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.translatable("entity.tesla_tycoon." + com.anant.teslatycoon.vehicle.VehicleSpecs.LEVELS.get(c.level - 1).lang).formatted(Formatting.GOLD)));
                Tycoon.sync(p, c);
            }
        });
        if (p != null) {
            p.sendMessage(Text.literal("Adding a new glass floor...").formatted(Formatting.AQUA), true);
        }
    }

    public static void onCounterBought(ServerWorld w, Company c, ServerPlayerEntity p, Upgrade u) {
        if (u == Upgrade.SOLAR) {
            c.powerOut = false;
            setPower(w, c, true);
        }
        Plan plan = new Plan();
        plan.ops.addAll(Buildings.upgrades(c).ops);
        plan.ops.addAll(Buildings.showroomRoof(c.level, c).ops);
        if (c.level >= 5) {
            plan.ops.addAll(Buildings.lift().ops);
        }
        NEON_INDEX.remove(c.owner);
        edit(w, c, plan, !c.powerOut, () -> respawnBanner(w, c));
    }

    /** Switches every neon block of the city on or off (Gotiya's blackout / the solar roof): loaded chunks now, the rest when they load. */
    public static void setPower(ServerWorld w, Company c, boolean on) {
        java.util.Map<Long, List<Plan.Op>> index = neonIndex(c);
        List<ChunkWork> list = new ArrayList<>();
        for (java.util.Map.Entry<Long, List<Plan.Op>> e : index.entrySet()) {
            int cx = ChunkPos.getPackedX(e.getKey());
            int cz = ChunkPos.getPackedZ(e.getKey());
            if (w.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
                ChunkWork cw = new ChunkWork(cx, cz);
                cw.ops.addAll(e.getValue());
                list.add(cw);
            }
        }
        list.sort(java.util.Comparator.comparingDouble(cw -> Math.hypot(cw.cx * 16 + 8 - (c.origin.getX() - 45.0), cw.cz * 16 + 8 - (c.origin.getZ() - 20.0))));
        URGENT.add(new Job(w, c.origin, list, true, on, false, null, null, 0f, 1f));
    }

    private static java.util.Map<Long, List<Plan.Op>> neonIndex(Company c) {
        return NEON_INDEX.computeIfAbsent(c.owner, k -> {
            java.util.Map<Long, List<Plan.Op>> map = new java.util.HashMap<>();
            for (ChunkWork cw : works(neonPlan(c).ops, c.origin, true)) {
                map.put(ChunkPos.toLong(cw.cx, cw.cz), cw.ops);
            }
            return map;
        });
    }

    /**
     * A chunk of a city has just loaded: make its neon match the power state (it may have been unloaded during a blackout).
     * The chunk is read and written directly: asking the world for a block of a chunk that is still being promoted would wait for
     * that very chunk (and freeze the server thread).
     */
    private static void reconcileNeon(ServerWorld world, WorldChunk chunk) {
        if (Tycoon.state() == null) {
            return;
        }
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        for (Company c : Tycoon.state().all()) {
            if (!c.cityBuilt || !c.dimension.equals(world.getRegistryKey().getValue().toString())) {
                continue;
            }
            int lx = cx * 16 - c.origin.getX();
            int lz = cz * 16 - c.origin.getZ();
            if (lx + 15 < X0 || lx > X1 || lz + 15 < Z0 || lz > Z1) {
                continue;
            }
            List<Plan.Op> ops = neonIndex(c).get(ChunkPos.toLong(cx, cz));
            if (ops == null) {
                continue;
            }
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (Plan.Op op : ops) {
                BlockState target = op.state.with(NeonBlock.LIT, !c.powerOut);
                for (int x = op.x1; x <= op.x2; x++) {
                    for (int y = op.y1; y <= op.y2; y++) {
                        for (int z = op.z1; z <= op.z2; z++) {
                            pos.set(c.origin.getX() + x, c.origin.getY() + y, c.origin.getZ() + z);
                            if (pos.getY() < world.getBottomY() || pos.getY() >= world.getTopY()) {
                                continue;
                            }
                            BlockState now = chunk.getBlockState(pos);
                            if (now.getBlock() == target.getBlock() && now != target) {
                                chunk.setBlockState(pos, target, false);
                            }
                        }
                    }
                }
            }
        }
    }

    /** #retryscene and /tycoon rebuild: repairs every showroom block (and the gate) and resets the entities. */
    public static void rebuildAll(ServerPlayerEntity p, Company c) {
        rebuildAll(p, c, null);
    }

    public static void rebuildAll(ServerPlayerEntity p, Company c, Runnable after) {
        ServerWorld w = Tycoon.worldOf(c);
        Plan plan = new Plan();
        for (int k = 1; k <= c.level; k++) {
            plan.ops.addAll(Buildings.showroomFloor(k).ops);
        }
        plan.ops.addAll(Buildings.showroomRoof(c.level, c).ops);
        if (c.level >= 5) {
            plan.ops.addAll(Buildings.lift().ops);
        }
        plan.ops.addAll(Buildings.upgrades(c).ops);
        plan.ops.addAll(Buildings.shop().ops);
        if (c.level >= 5 && c.gateClosed) {
            plan.ops.addAll(Buildings.gate(true).ops);
            if (c.gateBroken) {
                plan.ops.addAll(Buildings.gateHole().ops);
            }
        } else {
            plan.ops.addAll(Buildings.gate(false).ops);
        }
        NEON_INDEX.remove(c.owner);
        edit(w, c, plan, !c.powerOut, () -> {
            spawnEntities(w, c);
            p.sendMessage(Text.literal("Showroom repaired.").formatted(Formatting.GREEN), true);
            if (after != null) {
                after.run();
            }
        });
    }

    /** The security gate along the boulevard closes (alarm) or is removed (reset). */
    public static void setGate(ServerWorld w, Company c, boolean closed, Runnable done) {
        c.gateClosed = closed;
        c.gateBroken = false;
        NEON_INDEX.remove(c.owner);
        edit(w, c, Buildings.gate(closed), !c.powerOut, done);
    }

    /** The Monster Truck smashes the gate: a hole in the middle. */
    public static void breakGate(ServerWorld w, Company c, Runnable done) {
        c.gateBroken = true;
        NEON_INDEX.remove(c.owner);
        edit(w, c, Buildings.gateHole(), !c.powerOut, done);
    }

    public static void jumpToLevel(ServerPlayerEntity p, Company c, int level) {
        ServerWorld w = Tycoon.worldOf(c);
        c.level = level;
        for (Upgrade u : Upgrade.values()) {
            if (u.kind == Upgrade.Kind.LEVEL && u.target <= level) {
                c.owned.add(u);
            }
            if (u.kind == Upgrade.Kind.COUNTER && u.target < level) {
                c.owned.add(u);
            }
        }
        if (level >= 4) {
            c.billboardsPlaced = Math.max(c.billboardsPlaced, Company.BILLBOARDS_NEEDED);
            c.billboardMask |= 0b111;
            c.ramSmashed = Math.max(c.ramSmashed, 2);
        }
        c.sabTriggered = 0;
        NEON_INDEX.remove(c.owner);
        Plan plan = buildingsPlan(c);
        edit(w, c, plan, !c.powerOut, () -> {
            spawnEntities(w, c);
            Tycoon.sync(p, c);
            p.sendMessage(Text.literal("Showroom level set to " + level).formatted(Formatting.GREEN), true);
        });
    }

    // ------------------------------------------------------------------------------------------------------ tick
    public static void tick(MinecraftServer server) {
        Job job = URGENT.peek();
        boolean urgent = job != null;
        if (job == null) {
            job = BACKGROUND.peek();
        }
        if (job == null) {
            return;
        }
        long started = System.nanoTime();
        long deadline = started + (urgent ? BUDGET_URGENT_NANOS : BUDGET_BACKGROUND_NANOS);
        int[] sets = {SETS_PER_TICK};
        ServerWorld world = job.world;
        List<ChunkWork> pending = job.pending;
        if (job.load) {
            int n = Math.min(PRELOAD, pending.size());
            for (int i = 0; i < n; i++) {
                ChunkWork cw = pending.get(i);
                if (!cw.forced) {
                    cw.forced = world.setChunkForced(cw.cx, cw.cz, true);
                }
            }
        }
        int considered = 0;
        for (int i = 0; i < pending.size() && considered < PRELOAD && sets[0] > 0 && System.nanoTime() < deadline; ) {
            ChunkWork cw = pending.get(i);
            considered++;
            if (world.getChunkManager().getChunk(cw.cx, cw.cz, ChunkStatus.FULL, false) == null) {
                if (!job.load) {
                    pending.remove(i);                         // an unloaded chunk is skipped (neon-only jobs): reconcileNeon fixes it on load
                    continue;
                }
                i++;
                continue;
            }
            boolean finished = work(job, cw, deadline, sets);
            if (finished) {
                pending.remove(i);
                if (cw.forced) {
                    world.setChunkForced(cw.cx, cw.cz, false);
                    cw.forced = false;
                }
            } else {
                break;                                          // out of budget
            }
        }
        job.setsDone += SETS_PER_TICK - sets[0];
        job.nanos += System.nanoTime() - started;
        if (job.bar != null) {
            float done = 1f - pending.size() / (float) job.total;
            job.bar.setPercent(Math.min(1f, job.barFrom + (job.barTo - job.barFrom) * done));
        }
        if (pending.isEmpty()) {
            (urgent ? URGENT : BACKGROUND).poll();
            if (job.total > 20) {
                Tycoon.log(String.format("[city] job finished: %d columns, %,d blocks placed, %.1f s of server time", job.total, job.setsDone, job.nanos / 1.0e9));
            }
            if (job.done != null) {
                try {
                    job.done.run();
                } catch (RuntimeException e) {
                    TeslaTycoon.LOGGER.error("city job completion failed", e);
                }
            }
        }
    }

    /** Runs the ops of one column until the budget is spent. Returns true when the column is finished. */
    private static boolean work(Job job, ChunkWork cw, long deadline, int[] sets) {
        ServerWorld world = job.world;
        BlockPos o = job.origin;
        int bottom = world.getBottomY();
        int top = world.getTopY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int counter = 0;
        while (cw.opIndex < cw.ops.size()) {
            Plan.Op op = cw.ops.get(cw.opIndex);
            long vol = op.volume();
            int dx = op.x2 - op.x1 + 1;
            int dz = op.z2 - op.z1 + 1;
            BlockState fixed = op.shape != null ? null : (op.neon != null ? op.state.with(NeonBlock.LIT, job.lit) : op.state);
            while (cw.cursor < vol) {
                if ((++counter & 255) == 0 && (sets[0] <= 0 || System.nanoTime() > deadline)) {
                    return false;
                }
                long cur = cw.cursor++;
                int ix = (int) (cur % dx);
                int iz = (int) ((cur / dx) % dz);
                int iy = (int) (cur / ((long) dx * dz));
                int lx = op.x1 + ix;
                int ly = op.y1 + iy;
                int lz = op.z1 + iz;
                int y = o.getY() + ly;
                if (y < bottom || y >= top) {
                    continue;
                }
                BlockState target = fixed != null ? fixed : op.shape.at(lx, ly, lz);
                if (target == null) {
                    continue;
                }
                pos.set(o.getX() + lx, y, o.getZ() + lz);
                BlockState existing = world.getBlockState(pos);
                if (existing == target) {
                    continue;
                }
                if (op.keepSolid) {
                    if (!existing.isAir() && existing.getFluidState().isEmpty()) {
                        continue;
                    }
                } else if (existing.isOf(Blocks.BEDROCK)) {
                    continue;
                }
                world.setBlockState(pos, target, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                sets[0]--;
            }
            cw.opIndex++;
            cw.cursor = 0;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------ entities
    /** After a restart in the middle of the build: the skyline around the district is built again (what exists is skipped). */
    public static void resumeOutskirts(ServerWorld w, Company c, ServerPlayerEntity p) {
        if (c.cityComplete || busy()) {
            return;
        }
        ServerBossBar bar = new ServerBossBar(Text.literal("The skyscrapers are rising around you..."), BossBar.Color.BLUE, BossBar.Style.PROGRESS);
        bar.addPlayer(p);
        List<ChunkWork> rest = split(works(fullPlan(c).ops, c.origin, false), c.origin, false);
        BACKGROUND.add(new Job(w, c.origin, rest, false, !c.powerOut, true, () -> {
            bar.clearPlayers();
            c.cityComplete = true;
            spawnBillboardSigns(w, c);
            Tycoon.log("[city] metropolis complete (resumed)");
        }, bar, 0f, 1f));
    }

    public static void spawnEntities(ServerWorld w, Company c) {
        spawnEntities(w, c, true);
    }

    public static void spawnEntities(ServerWorld w, Company c, boolean withBillboards) {
        Box box = new Box(c.origin.getX() + X0 - 6, c.origin.getY() - 4, c.origin.getZ() + Z0 - 6,
                c.origin.getX() + X1 + 6, c.origin.getY() + 80, c.origin.getZ() + Z1 + 6);
        List<Entity> old = new ArrayList<>(w.getEntitiesByClass(Entity.class, box,
                e -> e instanceof SignboardEntity || e instanceof com.anant.teslatycoon.shop.PedestalEntity || (e instanceof PosterEntity && e.getCommandTags().contains("tt_auto")) || e instanceof SuitedNpcEntity
                        || e instanceof VehicleEntity));
        for (Entity e : old) {
            e.discard();
        }
        respawnBanner(w, c);
        if (withBillboards) {
            spawnBillboardSigns(w, c);
        }
        sign(w, c, SignboardEntity.PETROL, -39.5, 11.0, 33.94 + GOT_DZ, Direction.NORTH);
        sign(w, c, SignboardEntity.SHOP, -13.5, 9.5, PZ1 + 0.06, Direction.SOUTH);
        ShowroomDirector.spawnPosters(w, c);
        ShowroomDirector.reset(c);
        ShowroomDirector.layoutDisplays(w, c);
        ShowroomDirector.ensureStaff(w, c);
        ShowroomDirector.ensurePedestals(w, c);
        RivalDirector.ensureGotiya(w, c);
        com.anant.teslatycoon.npc.RobotaxiDirector.reset(c);
    }

    /**
     * The billboard signs stand 130 to 200 blocks from the showroom: their chunks are loaded (and kept loaded) so that the sign
     * entities exist whenever a player drives past; the old signs are removed first.
     */
    public static void spawnBillboardSigns(ServerWorld w, Company c) {
        for (int i = 0; i < Buildings.BILLBOARDS.length; i++) {
            int[] b = Buildings.BILLBOARDS[i];
            Direction f = Buildings.billboardFacing(i);
            double[] ctr = Buildings.billboardCentre(b[0], b[1], f);
            BlockPos at = pos(c, ctr[0], 9, ctr[2]);
            ChunkPos cp = new ChunkPos(at);
            w.setChunkForced(cp.x, cp.z, true);
            w.getChunk(cp.x, cp.z);
            Vec3d v = vec(c, ctr[0], ctr[1], ctr[2]);
            for (SignboardEntity e : w.getEntitiesByClass(SignboardEntity.class, new Box(v, v).expand(4), s -> s.kind() == SignboardEntity.BILLBOARD)) {
                e.discard();
            }
            SignboardEntity s = sign(w, c, SignboardEntity.BILLBOARD, ctr[0], ctr[1], ctr[2], f);
            s.setAd((c.billboardMask & (1 << i)) != 0);
        }
    }

    public static void respawnBanner(ServerWorld w, Company c) {
        Box box = new Box(c.origin.getX() + SX0 - 2, c.origin.getY(), c.origin.getZ() + SZ1 - 4, c.origin.getX() + SX1 + 2, c.origin.getY() + 80, c.origin.getZ() + SZ1 + 3);
        for (SignboardEntity e : w.getEntitiesByClass(SignboardEntity.class, box, s -> s.kind() == SignboardEntity.SHOWROOM)) {
            e.discard();
        }
        int yr = FLOOR_H * c.level;
        sign(w, c, SignboardEntity.SHOWROOM, -43.5, yr + 6.5, SZ1 + 0.06, Direction.SOUTH);
    }

    private static SignboardEntity sign(ServerWorld w, Company c, int kind, double lx, double ly, double lz, Direction facing) {
        SignboardEntity s = new SignboardEntity(ModEntities.SIGNBOARD, w);
        s.setKind(kind);
        Vec3d v = vec(c, lx, ly, lz);
        s.refreshPositionAndAngles(v.x, v.y, v.z, facing.asRotation(), 0f);
        w.spawnEntity(s);
        return s;
    }
}
