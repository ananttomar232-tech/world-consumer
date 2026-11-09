package com.anant.teslatycoon.gametest;

import com.anant.teslatycoon.block.NeonBlock;
import com.anant.teslatycoon.city.Buildings;
import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.city.CityLayout;
import com.anant.teslatycoon.city.CityRoutes;
import com.anant.teslatycoon.city.Plan;
import com.anant.teslatycoon.city.Skyline;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
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
        double[] peak = {0};
        ctx.runAtTick(110, () -> peak[0] = v.getY());
        ctx.runAtTick(130, () -> {
            if (Math.abs(v.getY() - hoverY[0]) > 1.5) {
                ctx.throwGameTestException("did not hover: drifted " + (v.getY() - hoverY[0]) + " blocks in 2 s");
            }
            c.descend = true;
        });
        ctx.runAtTick(330, () -> {
            // (it may land on the roof of a neighbouring test's city: it must have come down and be standing on something)
            if (v.getY() > peak[0] - 3.0 || !v.isOnGround() || v.flyingNow()) {
                ctx.throwGameTestException("did not land: y=" + v.getY() + " peak=" + peak[0] + " onGround=" + v.isOnGround());
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
    /** Walks through the whole story with the Company rules: returns the seconds spent on each showroom level (1..4). */
    private static double[] playThrough(boolean driving, boolean automatic, double[] firstRates) {
        Company c = new Company(UUID.randomUUID(), "pacing");
        Upgrade[] order = {Upgrade.HYDRAULICS, Upgrade.LEVEL2, Upgrade.SOLAR, Upgrade.LEVEL3, Upgrade.RAM, Upgrade.LEVEL4, Upgrade.STARLINK, Upgrade.BILLBOARDS, Upgrade.LEVEL5};
        double[] customerEvery = {0, 37.5, 33.0, 28.5, 24.0, 19.5};
        double t = 0;
        double levelStart = 0;
        double[] perLevel = new double[6];
        double nextCustomer = 10;
        double nextDelivery = 35;
        double sabotageAt = 25;
        c.money = 0;
        long tick = 0;
        firstRates[1] = c.passivePerSecond();
        for (Upgrade u : order) {
            int guard = 0;
            while (c.money < u.cost && guard++ < 400000) {
                if (sabotageAt >= 0 && t >= sabotageAt) {
                    c.sabTriggered |= 1 << c.level;
                    sabotageAt = -1;
                }
                c.driving = driving && (tick % 100) < 65;                       // at the wheel 65% of the time
                c.carry += c.incomePerSecond() * 0.05;
                if (automatic) {
                    if (t >= nextCustomer) {
                        c.carry += c.worth(Company.TIP_SECONDS);
                        nextCustomer = t + customerEvery[c.level];
                    }
                    if (t >= nextDelivery) {
                        c.carry += c.worth(Company.DELIVERY_SECONDS);
                        nextDelivery = t + 95;
                    }
                    if (c.level >= 4) {
                        c.carry += c.worth(Company.FARE_SECONDS) * 12.8 / 60.0 * 0.05;     // 4 Cybercabs together: ~13 rides a minute
                    }
                }
                long whole = (long) c.carry;
                c.money += whole;
                c.carry -= whole;
                t += 0.05;
                tick++;
            }
            c.money -= u.cost;
            c.owned.add(u);
            switch (u) {
                case LEVEL2, LEVEL3, LEVEL4, LEVEL5 -> {
                    perLevel[c.level] = t - levelStart;
                    levelStart = t;
                    c.level = u.target;
                    c.sabTriggered |= 0;
                    sabotageAt = t + 7;
                    firstRates[c.level] = c.passivePerSecond();
                }
                case BILLBOARDS -> c.billboardsPlaced = 3;
                case RAM -> c.ramSmashed = 2;
                default -> { }
            }
        }
        return perLevel;
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void economy_matches_the_brief_and_each_level_takes_two_to_three_minutes(TestContext ctx) {
        // the brief's numbers
        expect(ctx, Upgrade.LEVEL2.cost == 1_500 && Upgrade.LEVEL3.cost == 10_000 && Upgrade.LEVEL4.cost == 50_000 && Upgrade.LEVEL5.cost == 250_000, "level prices");
        double[] want = {0, 5, 35, 150, 800};
        for (int lv = 1; lv <= 4; lv++) {
            Company c = new Company(UUID.randomUUID(), "rate");
            c.level = lv;
            expect(ctx, Math.abs(c.passivePerSecond() - want[lv]) < 1e-9, "level " + lv + " earns $" + want[lv] + "/s, got " + c.passivePerSecond());
        }
        double[] first = new double[6];
        double[] active = playThrough(true, true, first);
        double[] idle = playThrough(false, true, first);
        double[] afk = playThrough(false, false, first);
        for (int lv = 1; lv <= 4; lv++) {
            Tycoon.log(String.format("[pacing] level %d: active %.0f s, idle %.0f s, afk %.0f s", lv, active[lv], idle[lv], afk[lv]));
            if (active[lv] < 120 || active[lv] > 195) {
                ctx.throwGameTestException("level " + lv + " takes an active player " + Math.round(active[lv]) + " s (want 120 - 195)");
            }
            if (idle[lv] < active[lv] || afk[lv] < 270) {
                ctx.throwGameTestException("level " + lv + ": idle " + Math.round(idle[lv]) + " s, afk " + Math.round(afk[lv]) + " s - standing still must be clearly slower");
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
        c.owned.addAll(List.of(Upgrade.STARLINK, Upgrade.BILLBOARDS));
        expect(ctx, c.blockedReason(Upgrade.LEVEL5) != null, "level 5 needs 3 placed billboards");
        c.billboardsPlaced = 3;
        c.ramSmashed = 2;
        expect(ctx, c.blockedReason(Upgrade.LEVEL5) == null, "level 5 after the full GPS counter");
        c.sabTriggered = 1 << 4;
        expect(ctx, c.activeSabotage() == 0, "sabotage 4 is over once starlink + billboards are in and 3 are placed");
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
            expect(ctx, maxY <= 150, "city is taller than expected: " + maxY);
        }
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 2400, batchId = "first_city")
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
            // (the billboards stand 130+ blocks out on the highway ring, beyond the district this test builds: put one panel in place)
            CityBuilder.spawnBillboardSigns(w, c);
            int[] bb = Buildings.BILLBOARDS[3];
            BlockPos panel = Plan.world(origin, bb[0], 8, bb[1]);
            w.setBlockState(panel, ModBlocks.BILLBOARD_PANEL.getDefaultState());
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

    // ------------------------------------------------------------------------------------------------------ city geometry (no world needed)
    private static boolean onRoad(double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        boolean inNs = false;
        boolean inEw = false;
        for (int[] r : CityLayout.NS) {
            inNs |= ix >= r[0] && ix <= r[1];
        }
        for (int[] r : CityLayout.EW) {
            inEw |= iz >= r[0] && iz <= r[1];
        }
        boolean withinRing = ix >= CityLayout.RING_X0 && ix <= CityLayout.RING_X1 && iz >= CityLayout.RING_Z0 && iz <= CityLayout.RING_Z1;
        return withinRing && (inNs || inEw);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void the_city_is_a_real_metropolis(TestContext ctx) {
        int w = CityLayout.X1 - CityLayout.X0 + 1;
        int d = CityLayout.Z1 - CityLayout.Z0 + 1;
        expect(ctx, w >= 300 && d >= 300, "the plot must be at least 300 x 300, is " + w + " x " + d);
        expect(ctx, CityLayout.NS.length >= 4 && CityLayout.EW.length >= 5, "too few avenues: " + CityLayout.NS.length + " x " + CityLayout.EW.length);
        expect(ctx, CityLayout.BOULEVARD[1] - CityLayout.BOULEVARD[0] + 1 == 22, "the Grand Boulevard is 22 wide");
        // the showroom and Gotiya's station face each other over the boulevard; the Upgrade Depot is next door to the showroom
        expect(ctx, CityLayout.SZ1 < CityLayout.BOULEVARD[0] && CityLayout.GZ0 > CityLayout.BOULEVARD[1], "showroom north, petrol station south of the boulevard");
        expect(ctx, CityLayout.PX0 - CityLayout.SX1 <= 6 && CityLayout.PX0 > CityLayout.SX1, "the Upgrade Depot must stand right next to the showroom");
        List<Plan.Op> towers = new ArrayList<>();
        for (Plan.Op op : Skyline.build().ops) {
            if (op.shape != null) {
                towers.add(op);
            }
        }
        expect(ctx, towers.size() >= 80, "expected a skyline of 80+ buildings, got " + towers.size());
        int tallest = 0;
        for (Plan.Op op : towers) {
            tallest = Math.max(tallest, op.y2);
            for (int[] r : CityLayout.NS) {
                if (op.x2 >= r[0] - 2 && op.x1 <= r[1] + 2) {
                    ctx.throwGameTestException("a building sits on an avenue: x " + op.x1 + ".." + op.x2);
                }
            }
            for (int[] r : CityLayout.EW) {
                if (op.z2 >= r[0] - 2 && op.z1 <= r[1] + 2) {
                    ctx.throwGameTestException("a building sits on a street: z " + op.z1 + ".." + op.z2);
                }
            }
            // never inside the showroom compound or Gotiya's block
            boolean hitsCore = op.x2 >= -72 && op.x1 <= 50 && ((op.z2 >= -36 && op.z1 <= -2) || (op.z2 >= 23 && op.z1 <= 68));
            expect(ctx, !hitsCore, "a skyscraper stands in the showroom compound / Gotiya's block at " + op.x1 + "," + op.z1);
        }
        expect(ctx, tallest >= 100, "the skyline needs towers of 100+ blocks, tallest is " + tallest);
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void robotaxi_loops_follow_the_roads_and_stop_at_the_stations(TestContext ctx) {
        CityRoutes.Route[] routes = {CityRoutes.outer(), CityRoutes.inner(), CityRoutes.core()};
        int[] stops = {4, 5, 3};
        for (int i = 0; i < routes.length; i++) {
            CityRoutes.Route r = routes[i];
            expect(ctx, r.length > 300, "loop " + i + " is too short: " + r.length);
            expect(ctx, r.stops.size() == stops[i], "loop " + i + " should stop at " + stops[i] + " stations, has " + r.stops.size());
            for (double[] p : r.pts) {
                expect(ctx, onRoad(p[0], p[1]), "loop " + i + " leaves the road at " + p[0] + "," + p[1]);
            }
            for (int k = 0; k + 1 < r.pts.size(); k++) {
                double[] a = r.pts.get(k);
                double[] b = r.pts.get(k + 1);
                expect(ctx, Math.hypot(b[0] - a[0], b[1] - a[1]) < 42, "loop " + i + " has a gap of " + Math.hypot(b[0] - a[0], b[1] - a[1]) + " blocks after waypoint " + k);
            }
        }
        // every station: the shelter is on the pavement, the stop is 3.5 to 4.5 blocks from its centre, on the road
        for (int s = 0; s < Buildings.STATIONS.length; s++) {
            int[] st = Buildings.STATIONS[s];
            double[] stop = CityRoutes.STOP[s];
            expect(ctx, !onRoad(st[0], st[1]), "station " + s + " stands on a road");
            expect(ctx, onRoad(stop[0], stop[1]), "the stop of station " + s + " is not on a road");
            double d = Math.hypot(stop[0] - st[0], stop[1] - st[1]);
            expect(ctx, d > 3.0 && d < 5.2, "station " + s + " is " + d + " blocks from its stop");
        }
        List<double[]> td = CityRoutes.testDrive(new java.util.HashSet<>());
        for (double[] p : td) {
            boolean bay = p[0] == -32.5 && p[1] < -3;
            expect(ctx, bay || onRoad(p[0], p[1]), "the test drive leaves the road at " + p[0] + "," + p[1]);
        }
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void billboards_are_far_apart_on_the_highway_turns(TestContext ctx) {
        int n = Buildings.BILLBOARDS.length;
        expect(ctx, n >= 6, "six billboards expected");
        for (int i = 0; i < n; i++) {
            int[] a = Buildings.BILLBOARDS[i];
            expect(ctx, Math.hypot(a[0] + 45, a[1] + 20) > 110, "billboard " + i + " is too close to the showroom");
            for (int j = i + 1; j < n; j++) {
                int[] b = Buildings.BILLBOARDS[j];
                expect(ctx, Math.hypot(a[0] - b[0], a[1] - b[1]) > 120, "billboards " + i + " and " + j + " are only " + Math.hypot(a[0] - b[0], a[1] - b[1]) + " blocks apart");
            }
            // on a ring road pavement: next to the outermost avenue or street
            boolean ring = Math.abs(a[0] - CityLayout.NS[0][1]) < 12 || Math.abs(a[0] - CityLayout.NS[3][0]) < 12
                    || Math.abs(a[1] - CityLayout.EW[0][1]) < 12 || Math.abs(a[1] - CityLayout.EW[5][0]) < 12;
            expect(ctx, ring, "billboard " + i + " is not on the highway ring: " + a[0] + "," + a[1]);
        }
        for (int[] st : Buildings.STATIONS) {
            expect(ctx, !Buildings.nearBillboard(st[0], st[1], 2), "a station overlaps a billboard at " + st[0] + "," + st[1]);
        }
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void finale_structures_are_complete(TestContext ctx) {
        // the lift opens every slab and the roof; the gate leaves a pedestrian opening the Monster Truck cannot use
        Plan lift = Buildings.lift();
        for (int k = 1; k <= 5; k++) {
            final int y = CityLayout.FLOOR_H * k;
            boolean cleared = lift.ops.stream().anyMatch(op -> op.state == Plan.AIR && op.y1 == y && op.y2 == y && op.x1 <= CityLayout.LIFT_X - 1 && op.x2 >= CityLayout.LIFT_X + 1);
            expect(ctx, cleared, "the lift shaft does not open the slab at y=" + y);
        }
        int door = CityLayout.GATE_DOOR_X1 - CityLayout.GATE_DOOR_X0 + 1;
        expect(ctx, door >= 3 && door < 4.7, "the pedestrian opening must let a player but not the 4.7 wide truck through: " + door);
        expect(ctx, Buildings.alarmLights(5).size() >= 8, "alarm beacons missing");
        Plan gate = Buildings.gate(true);
        expect(ctx, gate.ops.stream().anyMatch(op -> op.state != null && op.state.isOf(Blocks.IRON_BARS)), "the gate needs iron bars");
        expect(ctx, Buildings.GATE_HOLE_X1 - Buildings.GATE_HOLE_X0 + 1 > 10, "the hole the truck makes must be wide");
        // the helipad is above the lobby and the landing spot is on it
        expect(ctx, Math.hypot(CityLayout.LIFT_X - CityLayout.PAD_X, CityLayout.LIFT_Z - CityLayout.HELI_Z) < 12, "the lift is too far from the helipad");
        ctx.complete();
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
