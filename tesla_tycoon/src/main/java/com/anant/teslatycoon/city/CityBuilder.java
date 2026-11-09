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
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Builds the city in time slices (never more than a few milliseconds per tick) and manages its lifecycle. */
public final class CityBuilder {
    private static final int SETS_PER_TICK = 3200;
    private static final int CHECKS_PER_TICK = 40000;

    private static final class Job {
        final ServerWorld world;
        final BlockPos origin;
        final List<Plan.Op> ops;
        final boolean neonOnly;
        final boolean lit;
        final Runnable done;
        final ServerBossBar bar;
        int opIndex;
        long cursor;

        Job(ServerWorld world, BlockPos origin, List<Plan.Op> ops, boolean neonOnly, boolean lit, Runnable done, ServerBossBar bar) {
            this.world = world;
            this.origin = origin;
            this.ops = ops;
            this.neonOnly = neonOnly;
            this.lit = lit;
            this.done = done;
            this.bar = bar;
        }
    }

    private static final Deque<Job> QUEUE = new ArrayDeque<>();

    private CityBuilder() {}

    public static void init() {
        // nothing hostile spawns or loads inside the city
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if ((entity instanceof HostileEntity || entity instanceof Monster) && Tycoon.state() != null && insideAnyCity(world, entity.getBlockPos())) {
                entity.discard();
            }
        });
    }

    /** Test / scripting entry: builds the city for a company without a player and calls {@code done} when finished. */
    public static void buildForTest(ServerWorld world, Company c, BlockPos origin, Runnable done) {
        c.origin = origin;
        c.dimension = world.getRegistryKey().getValue().toString();
        Plan plan = fullPlan(c);
        QUEUE.add(new Job(world, origin, plan.ops, false, true, () -> {
            c.cityBuilt = true;
            spawnEntities(world, c);
            if (done != null) {
                done.run();
            }
        }, null));
    }

    public static void shutdown() {
        QUEUE.clear();
    }

    public static boolean busy() {
        return !QUEUE.isEmpty();
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
    /** Everything that exists for a showroom with {@code level} floors. */
    public static Plan fullPlan(Company c) {
        Plan all = new Plan();
        all.ops.addAll(CityLayout.base().ops);
        for (int k = 1; k <= c.level; k++) {
            all.ops.addAll(Buildings.showroomFloor(k).ops);
        }
        all.ops.addAll(Buildings.showroomRoof(c.level, c).ops);
        all.ops.addAll(Buildings.shop().ops);
        all.ops.addAll(Buildings.petrol().ops);
        for (int i = 0; i < Buildings.BILLBOARDS.length; i++) {
            int[] b = Buildings.BILLBOARDS[i];
            all.ops.addAll(Buildings.billboard(b[0], b[1], Buildings.billboardFacing(i)).ops);
        }
        all.ops.addAll(Buildings.upgrades(c).ops);
        return all;
    }

    public static BlockPos pos(Company c, double lx, double ly, double lz) {
        return Plan.world(c.origin, (int) Math.floor(lx), (int) Math.floor(ly), (int) Math.floor(lz));
    }

    public static Vec3d vec(Company c, double lx, double ly, double lz) {
        return new Vec3d(c.origin.getX() + lx, c.origin.getY() + ly, c.origin.getZ() + lz);
    }

    // ------------------------------------------------------------------------------------------------------ lifecycle
    public static void start(ServerPlayerEntity p) {
        Company existing = Tycoon.state().find(p.getUuid());
        if (existing != null && existing.cityBuilt) {
            p.sendMessage(Text.literal("Your city already exists. Teleporting you to the showroom.").formatted(Formatting.AQUA), false);
            teleportHome(p, existing);
            return;
        }
        if (busy()) {
            p.sendMessage(Text.literal("A city is being built right now, try again in a few seconds.").formatted(Formatting.YELLOW), false);
            return;
        }
        ServerWorld world = p.getServerWorld();
        Company c = existing != null ? existing : Tycoon.state().company(p.getUuid(), p.getGameProfile().getName());
        c.origin = new BlockPos(net.minecraft.util.math.MathHelper.floor(p.getX()), net.minecraft.util.math.MathHelper.floor(p.getY() - 1.0E-3), net.minecraft.util.math.MathHelper.floor(p.getZ()));
        c.dimension = world.getRegistryKey().getValue().toString();
        c.level = 1;
        c.owned.clear();
        c.money = 50;
        c.carry = 0;
        c.sabTriggered = 0;
        c.climaxWon = false;
        c.billboardsPlaced = 0;
        c.ramSmashed = 0;
        c.powerOut = false;
        c.climaxHp = 100;
        c.cityBuilt = false;
        // the neon city looks best after dark; the daylight cycle stays untouched, only the time is set
        world.setTimeOfDay(13400);
        p.sendMessage(Text.literal("[Tesla Tycoon] ").formatted(Formatting.AQUA, Formatting.BOLD)
                .append(Text.literal("Building Anant Tesla City... (time set to night so the neon shines)").formatted(Formatting.WHITE)), false);
        ServerBossBar bar = new ServerBossBar(Text.literal("Building Anant Tesla City"), BossBar.Color.BLUE, BossBar.Style.PROGRESS);
        bar.addPlayer(p);
        Plan plan = fullPlan(c);
        QUEUE.add(new Job(world, c.origin, plan.ops, false, true, () -> {
            c.cityBuilt = true;
            c.cityTick = 0;
            ModNetwork.sendState(p, c, 0);
            spawnEntities(world, c);
            ShowroomDirector.reset(c);
            bar.clearPlayers();
            teleportHome(p, c);
            p.sendMessage(Text.literal("Welcome to Anant Tesla Showroom! ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal("Customers walk in by themselves. Right-click the shining car to test drive it. Press B for the Upgrade Shop, RIGHT SHIFT to show the guide.").formatted(Formatting.WHITE)), false);
            ShowroomDirector.giveStarterPosters(p, 2);
            Tycoon.sync(p, c);
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("ANANT TESLA SHOWROOM").formatted(Formatting.AQUA, Formatting.BOLD)));
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("Level 1 - Tesla Classic Compact").formatted(Formatting.GOLD)));
        }, bar));
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
        QUEUE.add(new Job(w, c.origin, plan.ops, false, !c.powerOut, () -> {
            respawnBanner(w, c);
            ShowroomDirector.layoutDisplays(w, c);
            ShowroomDirector.spawnPosters(w, c);
            w.playSound(null, vec(c, -44, 1, -20).x, vec(c, -44, 1, -20).y, vec(c, -44, 1, -20).z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.AMBIENT, 2f, 1f);
            if (p != null) {
                p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 70, 20));
                p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("SHOWROOM LEVEL " + c.level).formatted(Formatting.AQUA, Formatting.BOLD)));
                p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.translatable("entity.tesla_tycoon." + com.anant.teslatycoon.vehicle.VehicleSpecs.LEVELS.get(c.level - 1).lang).formatted(Formatting.GOLD)));
                Tycoon.sync(p, c);
            }
        }, null));
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
        QUEUE.add(new Job(w, c.origin, plan.ops, false, !c.powerOut, () -> respawnBanner(w, c), null));
    }

    /** Switches every neon block of the city on or off (Gotiya's blackout / the solar roof). */
    public static void setPower(ServerWorld w, Company c, boolean on) {
        Plan plan = fullPlan(c);
        QUEUE.add(new Job(w, c.origin, plan.ops, true, on, null, null));
    }

    /** #retryscene and /tycoon rebuild: repairs every showroom block and resets the entities. */
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
        plan.ops.addAll(Buildings.upgrades(c).ops);
        plan.ops.addAll(Buildings.shop().ops);
        QUEUE.add(new Job(w, c.origin, plan.ops, false, !c.powerOut, () -> {
            spawnEntities(w, c);
            p.sendMessage(Text.literal("Showroom repaired.").formatted(Formatting.GREEN), true);
            if (after != null) {
                after.run();
            }
        }, null));
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
            c.ramSmashed = Math.max(c.ramSmashed, 2);
        }
        c.sabTriggered = 0;
        Plan plan = fullPlan(c);
        QUEUE.add(new Job(w, c.origin, plan.ops, false, !c.powerOut, () -> {
            spawnEntities(w, c);
            Tycoon.sync(p, c);
            p.sendMessage(Text.literal("Showroom level set to " + level).formatted(Formatting.GREEN), true);
        }, null));
    }

    // ------------------------------------------------------------------------------------------------------ tick
    public static void tick(MinecraftServer server) {
        Job job = QUEUE.peek();
        if (job == null) {
            return;
        }
        int sets = SETS_PER_TICK;
        int checks = CHECKS_PER_TICK;
        ServerWorld world = job.world;
        BlockPos o = job.origin;
        int bottom = world.getBottomY();
        int top = world.getTopY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        while (sets > 0 && checks > 0 && job.opIndex < job.ops.size()) {
            Plan.Op op = job.ops.get(job.opIndex);
            if (job.neonOnly && op.neon == null) {
                job.opIndex++;
                continue;
            }
            long vol = op.volume();
            int dx = op.x2 - op.x1 + 1;
            int dz = op.z2 - op.z1 + 1;
            BlockState target = op.neon != null ? op.state.with(NeonBlock.LIT, job.lit) : op.state;
            while (job.cursor < vol && sets > 0 && checks > 0) {
                long cur = job.cursor++;
                int ix = (int) (cur % dx);
                int iz = (int) ((cur / dx) % dz);
                int iy = (int) (cur / ((long) dx * dz));
                int y = o.getY() + op.y1 + iy;
                if (y < bottom || y >= top) {
                    continue;
                }
                pos.set(o.getX() + op.x1 + ix, y, o.getZ() + op.z1 + iz);
                checks--;
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
                sets--;
            }
            if (job.cursor >= vol) {
                job.opIndex++;
                job.cursor = 0;
            }
        }
        if (job.bar != null) {
            job.bar.setPercent(Math.min(1f, job.opIndex / (float) Math.max(1, job.ops.size())));
        }
        if (job.opIndex >= job.ops.size()) {
            QUEUE.poll();
            if (job.bar != null) {
                job.bar.clearPlayers();
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

    // ------------------------------------------------------------------------------------------------------ entities
    public static void spawnEntities(ServerWorld w, Company c) {
        Box box = new Box(c.origin.getX() + X0 - 6, c.origin.getY() - 4, c.origin.getZ() + Z0 - 6,
                c.origin.getX() + X1 + 6, c.origin.getY() + 80, c.origin.getZ() + Z1 + 6);
        List<Entity> old = new ArrayList<>(w.getEntitiesByClass(Entity.class, box,
                e -> e instanceof SignboardEntity || (e instanceof PosterEntity && e.getCommandTags().contains("tt_auto")) || e instanceof SuitedNpcEntity || e instanceof VehicleEntity));
        for (Entity e : old) {
            e.discard();
        }
        respawnBanner(w, c);
        for (int i = 0; i < Buildings.BILLBOARDS.length; i++) {
            int[] b = Buildings.BILLBOARDS[i];
            Direction f = Buildings.billboardFacing(i);
            double[] ctr = Buildings.billboardCentre(b[0], b[1], f);
            SignboardEntity s = sign(w, c, SignboardEntity.BILLBOARD, ctr[0], ctr[1], ctr[2], f);
            s.setAd(i < c.billboardsPlaced);
        }
        sign(w, c, SignboardEntity.PETROL, -39.5, 11.0, 33.94, Direction.NORTH);
        sign(w, c, SignboardEntity.SHOP, -13.5, 9.5, PZ1 + 0.06, Direction.SOUTH);
        ShowroomDirector.spawnPosters(w, c);
        ShowroomDirector.reset(c);
        ShowroomDirector.layoutDisplays(w, c);
        ShowroomDirector.ensureStaff(w, c);
        RivalDirector.ensureGotiya(w, c);
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
