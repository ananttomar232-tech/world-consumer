package com.anant.teslatycoon.gametest;

import com.anant.teslatycoon.block.NeonBlock;
import com.anant.teslatycoon.city.Buildings;
import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.city.CityLayout;
import com.anant.teslatycoon.city.Plan;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.npc.ShowroomDirector;
import com.anant.teslatycoon.registry.ModBlocks;
import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.rival.GotiyaChat;
import com.anant.teslatycoon.rival.RivalDirector;
import com.anant.teslatycoon.sign.Billboards;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpec;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Server-side tests: vehicle physics (smoothness, steering, curbs, flight), economy pacing, upgrade rules, chat, the city itself. */
public final class TycoonGameTests implements FabricGameTest {
    private static VehicleEntity spawn(TestContext ctx, VehicleSpec spec, double x, double y, double z, float yaw) {
        ServerWorld w = ctx.getWorld();
        VehicleEntity v = new VehicleEntity(ModEntities.vehicle(spec), w);
        BlockPos abs = ctx.getAbsolutePos(new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        v.refreshPositionAndAngles(abs.getX() + (x - Math.floor(x)), abs.getY() + (y - Math.floor(y)), abs.getZ() + (z - Math.floor(z)), yaw, 0f);
        v.setMode(VehicleEntity.MODE_DRIVEN);
        w.spawnEntity(v);
        return v;
    }

    private static void floor(TestContext ctx, int x0, int x1, int z0, int z1) {
        ServerWorld w = ctx.getWorld();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                w.setBlockState(ctx.getAbsolutePos(new BlockPos(x, 0, z)), Blocks.STONE.getDefaultState());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ physics
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 400)
    public void acceleration_is_smooth_and_steering_needs_motion(TestContext ctx) {
        floor(ctx, -10, 120, -20, 20);
        VehicleEntity v = spawn(ctx, VehicleSpecs.MODEL3, 0.5, 1, 0.5, 0f);
        VehicleEntity.Controls c = new VehicleEntity.Controls();
        v.testControls = c;
        c.steer = 1f;                                  // full lock while standing still: the car must not rotate
        double[] last = {0};
        double[] maxJump = {0};
        float[] yaw0 = {0};
        ctx.runAtTick(5, () -> yaw0[0] = v.getYaw());
        ctx.runAtTick(25, () -> {
            if (Math.abs(v.getYaw() - yaw0[0]) > 0.01f) {
                ctx.throwGameTestException("the car turned while standing still");
            }
            c.steer = 0f;
            c.throttle = 1f;
        });
        for (int t = 26; t < 130; t++) {
            ctx.runAtTick(t, () -> {
                double s = v.speed;
                maxJump[0] = Math.max(maxJump[0], Math.abs(s - last[0]));
                if (s + 1.0e-9 < last[0] - 1.0e-9) {
                    ctx.throwGameTestException("speed dropped while accelerating: " + last[0] + " -> " + s);
                }
                last[0] = s;
            });
        }
        ctx.runAtTick(130, () -> {
            if (v.speed < VehicleSpecs.MODEL3.maxSpeed * 0.8) {
                ctx.throwGameTestException("too slow after 4 s: " + v.speed);
            }
            if (maxJump[0] > VehicleSpecs.MODEL3.accel * 1.05) {
                ctx.throwGameTestException("acceleration step too big (jerky): " + maxJump[0]);
            }
            c.steer = 1f;                              // now steer while moving: yaw must change smoothly, never by more than the spec rate
        });
        float[] prevYaw = {0};
        float[] maxTurn = {0};
        ctx.runAtTick(131, () -> prevYaw[0] = v.getYaw());
        for (int t = 132; t < 160; t++) {
            ctx.runAtTick(t, () -> {
                maxTurn[0] = Math.max(maxTurn[0], Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(v.getYaw() - prevYaw[0])));
                prevYaw[0] = v.getYaw();
            });
        }
        ctx.runAtTick(160, () -> {
            if (maxTurn[0] <= 0.2f) {
                ctx.throwGameTestException("the car did not steer while moving");
            }
            if (maxTurn[0] > VehicleSpecs.MODEL3.maxYawDeg + 0.01f) {
                ctx.throwGameTestException("steering faster than the spec: " + maxTurn[0]);
            }
            v.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 300)
    public void brakes_and_reverse(TestContext ctx) {
        floor(ctx, -10, 120, -20, 20);
        VehicleEntity v = spawn(ctx, VehicleSpecs.CLASSIC, 0.5, 1, 0.5, 0f);
        VehicleEntity.Controls c = new VehicleEntity.Controls();
        v.testControls = c;
        c.throttle = 1f;
        ctx.runAtTick(60, () -> c.throttle = -1f);
        ctx.runAtTick(80, () -> {
            if (v.speed >= VehicleSpecs.CLASSIC.maxSpeed * 0.6) {
                ctx.throwGameTestException("braking too weak: " + v.speed);
            }
        });
        ctx.runAtTick(200, () -> {
            if (v.speed >= 0 || v.speed < -VehicleSpecs.CLASSIC.maxSpeed * VehicleSpecs.CLASSIC.reverseFactor - 0.001) {
                ctx.throwGameTestException("reverse speed out of range: " + v.speed);
            }
            v.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 300)
    public void climbs_a_one_block_curb_with_step_height_1_2(TestContext ctx) {
        floor(ctx, -10, 80, -10, 10);
        ServerWorld w = ctx.getWorld();
        for (int x = 25; x <= 80; x++) {
            for (int z = -10; z <= 10; z++) {
                w.setBlockState(ctx.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());   // a full-block curb
            }
        }
        VehicleEntity v = spawn(ctx, VehicleSpecs.CYBERTRUCK, 0.5, 1, 0.5, -90f);       // yaw -90 = facing +X
        if (Math.abs(v.getStepHeight() - 1.2f) > 1.0e-4) {
            ctx.throwGameTestException("stepHeight is " + v.getStepHeight());
        }
        VehicleEntity.Controls c = new VehicleEntity.Controls();
        c.throttle = 1f;
        v.testControls = c;
        double[] maxStep = {0};
        double[] maxY = {-1000};
        float[] minVisual = {0};
        double[] lastY = {v.getY()};
        for (int t = 1; t < 150; t++) {
            ctx.runAtTick(t, () -> {
                maxStep[0] = Math.max(maxStep[0], v.getY() - lastY[0]);
                maxY[0] = Math.max(maxY[0], v.getY());
                minVisual[0] = Math.min(minVisual[0], v.visualY);
                lastY[0] = v.getY();
            });
        }
        ctx.runAtTick(150, () -> {
            double top = ctx.getAbsolutePos(new BlockPos(0, 2, 0)).getY();
            if (maxY[0] < top - 0.05) {
                ctx.throwGameTestException("never got on top of the 1-block curb: peak y=" + maxY[0] + " (curb top " + top + "), maxStep=" + maxStep[0]);
            }
            if (maxStep[0] > 1.25) {
                ctx.throwGameTestException("climbed more than stepHeight allows in one tick: " + maxStep[0]);
            }
            if (minVisual[0] > -0.5f || minVisual[0] < -1.4f) {
                ctx.throwGameTestException("the camera smoothing offset did not engage on the step: " + minVisual[0]);
            }
            if (Math.abs(v.visualY) > 0.2f && v.getX() > ctx.getAbsolutePos(new BlockPos(35, 0, 0)).getX()) {
                ctx.throwGameTestException("camera offset did not decay: " + v.visualY);
            }
            v.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 500)
    public void roadster_thrusters_hover_and_land(TestContext ctx) {
        floor(ctx, -10, 40, -10, 10);
        VehicleEntity v = spawn(ctx, VehicleSpecs.ROADSTER, 0.5, 1, 0.5, 0f);
        VehicleEntity.Controls c = new VehicleEntity.Controls();
        v.testControls = c;
        double[] y0 = {0};
        ctx.runAtTick(5, () -> {
            y0[0] = v.getY();
            c.jump = true;                                  // hold SPACE: twin thrusters
        });
        ctx.runAtTick(60, () -> {
            if (v.getY() - y0[0] < 6.0) {
                ctx.throwGameTestException("thrusters did not lift the roadster: climbed " + (v.getY() - y0[0]));
            }
            if (!v.flyingNow()) {
                ctx.throwGameTestException("not in flight mode");
            }
            c.jump = false;                                 // release: hover
        });
        double[] hoverY = {0};
        ctx.runAtTick(90, () -> hoverY[0] = v.getY());
        ctx.runAtTick(130, () -> {
            if (Math.abs(v.getY() - hoverY[0]) > 1.5) {
                ctx.throwGameTestException("did not hover: drifted " + (v.getY() - hoverY[0]) + " blocks in 2 s");
            }
            c.descend = true;
        });
        ctx.runAtTick(330, () -> {
            if (v.getY() > y0[0] + 0.6 || !v.isOnGround()) {
                ctx.throwGameTestException("did not land: y=" + v.getY() + " onGround=" + v.isOnGround());
            }
            v.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 700)
    public void ai_car_drives_its_route_and_parks(TestContext ctx) {
        floor(ctx, -10, 100, -20, 40);
        VehicleEntity v = spawn(ctx, VehicleSpecs.MODEL3, 0.5, 1, 0.5, 0f);
        BlockPos o = ctx.getAbsolutePos(BlockPos.ORIGIN);
        VehicleEntity.Ai ai = new VehicleEntity.Ai();
        ai.points.add(new Vec3d(o.getX() + 0.5, o.getY() + 1, o.getZ() + 25.5));
        ai.points.add(new Vec3d(o.getX() + 40.5, o.getY() + 1, o.getZ() + 25.5));
        ai.cruise = 0.7;
        v.setAi(ai);
        ctx.runAtTick(600, () -> {
            if (!ai.finished) {
                ctx.throwGameTestException("AI car never finished its route; at " + v.getPos());
            }
            if (Math.abs(v.getX() - (o.getX() + 40.5)) > 4 || Math.abs(v.getZ() - (o.getZ() + 25.5)) > 4) {
                ctx.throwGameTestException("AI car parked in the wrong place: " + v.getPos());
            }
            v.discard();
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------------------------------------------ economy
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void purchases_arrive_every_1_5_to_2_minutes(TestContext ctx) {
        Company c = new Company(UUID.randomUUID(), "pacing");
        Upgrade[] order = {Upgrade.HYDRAULICS, Upgrade.LEVEL2, Upgrade.SOLAR, Upgrade.LEVEL3, Upgrade.RAM, Upgrade.LEVEL4, Upgrade.STARLINK, Upgrade.TERMINAL, Upgrade.BILLBOARDS, Upgrade.LEVEL5};
        double t = 0;
        double last = 0;
        int sabStart = 25;
        c.money = 0;
        for (Upgrade u : order) {
            int guard = 0;
            while (c.money < u.cost && guard++ < 100000) {
                if (c.level == 1 && t >= sabStart) {
                    c.sabTriggered |= 2;
                }
                c.carry += c.incomePerSecond() * 0.05;
                long whole = (long) c.carry;
                c.money += whole;
                c.carry -= whole;
                t += 0.05;
            }
            c.money -= u.cost;
            c.owned.add(u);
            switch (u) {
                case LEVEL2 -> { c.level = 2; c.sabTriggered |= 4; }
                case LEVEL3 -> { c.level = 3; c.sabTriggered |= 8; }
                case LEVEL4 -> { c.level = 4; c.sabTriggered |= 16; }
                case LEVEL5 -> c.level = 5;
                case BILLBOARDS -> c.billboardsPlaced = 3;
                case RAM -> c.ramSmashed = 2;
                default -> { }
            }
            double wait = t - last;
            last = t;
            if (wait < 80 || wait > 130) {
                ctx.throwGameTestException(u.id + " took " + Math.round(wait) + " s of income (want 1.5 - 2 minutes)");
            }
        }
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void upgrade_rules_follow_the_story(TestContext ctx) {
        Company c = new Company(UUID.randomUUID(), "rules");
        c.money = 1_000_000;
        expect(ctx, c.blockedReason(Upgrade.LEVEL2) != null, "level 2 needs the hydraulics first");
        expect(ctx, c.blockedReason(Upgrade.SOLAR) != null, "solar needs level 2");
        expect(ctx, c.blockedReason(Upgrade.HYDRAULICS) == null, "hydraulics available from the start");
        c.owned.add(Upgrade.HYDRAULICS);
        expect(ctx, "owned".equals(c.blockedReason(Upgrade.HYDRAULICS)), "cannot buy twice");
        expect(ctx, c.blockedReason(Upgrade.LEVEL2) == null, "level 2 after hydraulics");
        c.level = 4;
        c.owned.addAll(List.of(Upgrade.STARLINK, Upgrade.TERMINAL, Upgrade.BILLBOARDS));
        expect(ctx, c.blockedReason(Upgrade.LEVEL5) != null, "level 5 needs 3 placed billboards");
        c.billboardsPlaced = 3;
        c.ramSmashed = 2;
        expect(ctx, c.blockedReason(Upgrade.LEVEL5) == null, "level 5 after the full GPS counter");
        c.sabTriggered = 1 << 4;
        expect(ctx, c.activeSabotage() == 0, "sabotage 4 is over once starlink + terminal + billboards are in");
        ctx.complete();
    }

    private static void expect(TestContext ctx, boolean ok, String what) {
        if (!ok) {
            ctx.throwGameTestException(what);
        }
    }

    // ------------------------------------------------------------------------------------------------------ chat
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void gotiya_answers_everything_in_hinglish(TestContext ctx) {
        String[] inputs = {"hello", "tesla is the best", "petrol is dead", "bro you are a noob", "kitna paisa hai?", "lol", "gg", "please stop", "how do I win", "race me", "ok"};
        for (String in : inputs) {
            for (int i = 0; i < 6; i++) {
                String r = GotiyaChat.reply(in, "Anant", 1234, 3, "Armored Tesla Cybertruck", i % 6);
                expect(ctx, r != null && r.length() > 12, "empty reply for '" + in + "'");
                expect(ctx, !r.contains("{") && !r.contains("}"), "placeholder left in: " + r);
            }
        }
        expect(ctx, GotiyaChat.reply("tesla", "Anant", 5, 1, "x", 0).toLowerCase().matches(".*(tesla|bijli|battery|charg|cybertruck|engine|autopilot|gaadi).*"), "tesla keyword answer is not about electric cars");
        expect(ctx, !GotiyaChat.idle().isBlank(), "idle taunt");
        for (int k = 1; k <= 5; k++) {
            expect(ctx, !GotiyaChat.sabotageLine(k).isBlank() && !GotiyaChat.cryLine(k).isBlank(), "lines for sabotage " + k);
        }
        ctx.complete();
    }

    // ------------------------------------------------------------------------------------------------------ the city
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void city_plan_is_sane_for_every_level(TestContext ctx) {
        for (int level = 1; level <= 5; level++) {
            Company c = new Company(UUID.randomUUID(), "plan");
            c.level = level;
            Plan plan = CityBuilder.fullPlan(c);
            int neon = 0;
            int maxY = 0;
            for (Plan.Op op : plan.ops) {
                maxY = Math.max(maxY, op.y2);
                if (op.neon != null) {
                    neon++;
                }
                if (op.x1 < CityLayout.X0 - 12 || op.x2 > CityLayout.X1 + 12 || op.z1 < CityLayout.Z0 - 12 || op.z2 > CityLayout.Z1 + 12) {
                    ctx.throwGameTestException("operation outside the city plot: " + op.x1 + "," + op.z1);
                }
            }
            expect(ctx, neon > 150, "too few neon blocks for the blackout: " + neon);
            expect(ctx, maxY >= CityLayout.FLOOR_H * level, "roof of level " + level + " is missing");
            expect(ctx, maxY <= 60, "city is taller than expected: " + maxY);
        }
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 2400)
    public void city_builds_stacks_floors_and_switches_power(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        BlockPos origin = ctx.getAbsolutePos(new BlockPos(0, 4, 0)).add(0, 20, 0);
        Company c = new Company(UUID.randomUUID(), "builder");
        CityBuilder.buildForTest(w, c, origin, null);
        ctx.runAtTick(1500, () -> {
            expect(ctx, c.cityBuilt, "the city build did not finish in 75 s");
            BlockPos glass1 = Plan.world(origin, CityLayout.SX0 + 3, 3, CityLayout.SZ0);
            expect(ctx, w.getBlockState(glass1).isOf(Blocks.GLASS) || w.getBlockState(glass1).isOf(Blocks.BLACK_CONCRETE), "ground floor glass wall missing");
            BlockPos neonPos = Plan.world(origin, CityLayout.SX0 + 4, 6, CityLayout.SZ0 + 4);
            expect(ctx, w.getBlockState(neonPos).isOf(ModBlocks.NEON) && w.getBlockState(neonPos).get(NeonBlock.LIT), "ceiling neon should be lit");
            // exhibits: level 1 -> one display, the Classic Compact
            expect(ctx, displays(w, c).size() == 1, "expected 1 exhibit, got " + displays(w, c).size());
            expect(ctx, displays(w, c).get(0).spec == VehicleSpecs.CLASSIC, "level 1 exhibit must be the Classic Compact");
            // the blackout turns every neon block off, the solar roof turns them on again
            CityBuilder.setPower(w, c, false);
        });
        ctx.runAtTick(1700, () -> {
            BlockPos neonPos = Plan.world(origin, CityLayout.SX0 + 4, 6, CityLayout.SZ0 + 4);
            expect(ctx, !w.getBlockState(neonPos).get(NeonBlock.LIT), "blackout should switch the neon off");
            CityBuilder.setPower(w, c, true);
        });
        ctx.runAtTick(1900, () -> {
            BlockPos neonPos = Plan.world(origin, CityLayout.SX0 + 4, 6, CityLayout.SZ0 + 4);
            expect(ctx, w.getBlockState(neonPos).get(NeonBlock.LIT), "power should be back");
            // buy up to level 3: three floors, newest model on the ground floor, older ones higher
            c.level = 3;
            c.owned.add(Upgrade.LEVEL3);
            CityBuilder.upgradeShowroom(w, c, null);
        });
        ctx.runAtTick(2250, () -> {
            BlockPos glass3 = Plan.world(origin, CityLayout.SX0 + 3, CityLayout.FLOOR_H * 2 + 3, CityLayout.SZ0);
            expect(ctx, w.getBlockState(glass3).isOf(Blocks.GLASS) || w.getBlockState(glass3).isOf(Blocks.BLACK_CONCRETE), "third floor glass wall missing");
            List<VehicleEntity> shows = displays(w, c);
            expect(ctx, shows.size() == 3, "expected 3 exhibits, got " + shows.size());
            for (VehicleEntity e : shows) {
                int floor = (int) Math.round((e.getY() - origin.getY() - 1.0) / CityLayout.FLOOR_H) + 1;
                VehicleSpec expect = VehicleSpecs.LEVELS.get(c.level - floor);
                expect(ctx, e.spec == expect, "floor " + floor + " should show " + expect.id + " but shows " + e.spec.id);
            }
            // highway billboards: an empty frame refuses the ad without the marketing pack, then every right-click installs one (+6% income)
            int[] bb = Buildings.BILLBOARDS[3];
            BlockPos panel = Plan.world(origin, bb[0], 8, bb[1]);
            expect(ctx, w.getBlockState(panel).isOf(ModBlocks.BILLBOARD_PANEL), "billboard back board missing");
            expect(ctx, Billboards.install(w, c, panel) == Billboards.Result.NEEDS_PACK, "an empty frame must need the Billboard Marketing Pack");
            c.owned.add(Upgrade.BILLBOARDS);
            double before = c.incomePerSecond();
            expect(ctx, Billboards.install(w, c, panel) == Billboards.Result.INSTALLED, "the billboard should go live");
            expect(ctx, c.billboardsPlaced == 1, "one billboard placed, got " + c.billboardsPlaced);
            expect(ctx, Billboards.install(w, c, panel) == Billboards.Result.ALREADY_LIVE, "a live billboard must not be counted twice");
            expect(ctx, c.billboardsPlaced == 1, "a second click must not count again");
            double after = c.incomePerSecond();
            expect(ctx, Math.abs(after / before - 1.06) < 0.005, "a billboard should add 6% income: " + before + " -> " + after);
            RivalDirector.triggerForTest(w, c, 1);
            expect(ctx, spikesNear(w, origin), "sabotage 1 should leave road spikes on the road");
            ctx.complete();
        });
    }

    /**
     * The finale's Monster Truck (4.7 blocks wide, 4.9 tall) must get out of Gotiya's garage, over his forecourt and the road and up to
     * the showroom entrance. (It used to jam on a 4-high fence pole: the climax then never started hitting the showroom.)
     * It builds its own city, so it runs in its own batch: two cities must never be built on top of each other at the same time.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 2900, batchId = "second_city")
    public void monster_truck_drives_out_of_the_garage_to_the_showroom_front(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        BlockPos origin = ctx.getAbsolutePos(new BlockPos(0, 4, 0)).add(0, 20, 0);
        Company c = new Company(UUID.randomUUID(), "truck");
        CityBuilder.buildForTest(w, c, origin, null);
        VehicleEntity[] truck = new VehicleEntity[1];
        ctx.runAtTick(1500, () -> {
            expect(ctx, c.cityBuilt, "the city build did not finish in 75 s");
            // the whole route must tick: keep its chunks loaded
            for (int x = origin.getX() - 70; x <= origin.getX() - 10; x += 16) {
                for (int z = origin.getZ() - 20; z <= origin.getZ() + 50; z += 16) {
                    w.setChunkForced(x >> 4, z >> 4, true);
                }
            }
            truck[0] = RivalDirector.spawnChargeTruck(w, c);
        });
        for (int t = 1540; t <= 2800; t += 20) {
            ctx.runAtTick(t, () -> {
                double lz = truck[0].getZ() - origin.getZ();
                if (lz < 0.5) {
                    ctx.complete();
                }
            });
        }
        ctx.runAtTick(2850, () -> ctx.throwGameTestException("the Monster Truck never reached the showroom front, it is at "
                + (truck[0].getBlockPos().getX() - origin.getX()) + "," + (truck[0].getBlockPos().getZ() - origin.getZ()) + " (local)"));
    }

    /** The back-alley delivery truck must roll in and stop at the loading dock (it used to start 110 blocks away, outside small simulation distances). */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 2600, batchId = "third_city")
    public void delivery_truck_rolls_down_the_alley_to_the_dock(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        BlockPos origin = ctx.getAbsolutePos(new BlockPos(0, 4, 0)).add(0, 20, 0);
        Company c = new Company(UUID.randomUUID(), "delivery");
        CityBuilder.buildForTest(w, c, origin, null);
        VehicleEntity[] truck = new VehicleEntity[1];
        ctx.runAtTick(1500, () -> {
            expect(ctx, c.cityBuilt, "the city build did not finish in 75 s");
            for (int x = origin.getX() - 70; x <= origin.getX() + 40; x += 16) {
                for (int z = origin.getZ() - 60; z <= origin.getZ() - 20; z += 16) {
                    w.setChunkForced(x >> 4, z >> 4, true);
                }
            }
            truck[0] = ShowroomDirector.spawnDeliveryTruck(w, c);
        });
        for (int t = 1540; t <= 2400; t += 20) {
            ctx.runAtTick(t, () -> {
                double lx = truck[0].getX() - origin.getX();
                if (lx < -42 && truck[0].ai() != null && truck[0].ai().finished) {
                    ctx.complete();
                }
            });
        }
        ctx.runAtTick(2450, () -> ctx.throwGameTestException("the delivery truck never reached the dock, it is at "
                + (truck[0].getBlockPos().getX() - origin.getX()) + "," + (truck[0].getBlockPos().getZ() - origin.getZ()) + " (local)"));
    }

    private static boolean spikesNear(ServerWorld w, BlockPos origin) {
        for (int x = -14; x < 50; x++) {
            for (int z = 0; z < 14; z++) {
                if (w.getBlockState(Plan.world(origin, x, 0, z)).isOf(ModBlocks.SPIKES)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<VehicleEntity> displays(ServerWorld w, Company c) {
        Box box = new Box(c.origin.getX() + CityLayout.SX0, c.origin.getY(), c.origin.getZ() + CityLayout.SZ0, c.origin.getX() + CityLayout.SX1 + 1,
                c.origin.getY() + 45, c.origin.getZ() + CityLayout.SZ1 + 1);
        return new ArrayList<>(w.getEntitiesByClass(VehicleEntity.class, box, e -> e.getMode() == VehicleEntity.MODE_DISPLAY));
    }
}
