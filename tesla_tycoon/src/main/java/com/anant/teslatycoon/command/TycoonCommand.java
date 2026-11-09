package com.anant.teslatycoon.command;

import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.net.ModNetwork;
import com.anant.teslatycoon.npc.ShowroomDirector;
import com.anant.teslatycoon.rival.RivalDirector;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** /tycoon start | home | shop | balance | retry | posters, and operator test commands. */
public final class TycoonCommand {
    private TycoonCommand() {}

    private static Company need(ServerCommandSource src, ServerPlayerEntity p) {
        Company c = Tycoon.companyOf(p);
        if (c == null || !c.cityBuilt) {
            src.sendError(Text.literal("Build your city first: /tycoon start"));
            return null;
        }
        return c;
    }

    private static int drive(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx, String flags) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ModNetwork.sendDebugDrive(ctx.getSource().getPlayerOrThrow(), true, FloatArgumentType.getFloat(ctx, "throttle"), FloatArgumentType.getFloat(ctx, "steer"),
                flags.contains("j"), flags.contains("d"), flags.contains("r"));
        return 1;
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("tycoon")
                .then(CommandManager.literal("start").executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    CityBuilder.start(p);
                    return 1;
                }))
                .then(CommandManager.literal("home").executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    Company c = need(ctx.getSource(), p);
                    if (c != null) {
                        CityBuilder.teleportHome(p, c);
                    }
                    return 1;
                }))
                .then(CommandManager.literal("shop").executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    if (need(ctx.getSource(), p) != null) {
                        ModNetwork.sendOpenShop(p);
                    }
                    return 1;
                }))
                .then(CommandManager.literal("balance").executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    Company c = need(ctx.getSource(), p);
                    if (c != null) {
                        ctx.getSource().sendFeedback(() -> Text.literal(String.format("$%,d  (+$%.1f/s)  showroom level %d", c.money, c.incomePerSecond(), c.level)).formatted(Formatting.GOLD), false);
                    }
                    return 1;
                }))
                .then(CommandManager.literal("posters").executes(ctx -> {
                    ShowroomDirector.giveStarterPosters(ctx.getSource().getPlayerOrThrow(), 4);
                    return 1;
                }))
                .then(CommandManager.literal("retry").executes(ctx -> {
                    RivalDirector.retryScene(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(CommandManager.literal("rebuild").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    Company c = need(ctx.getSource(), p);
                    if (c != null) {
                        CityBuilder.rebuildAll(p, c);
                    }
                    return 1;
                }))
                .then(CommandManager.literal("money").requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.argument("amount", LongArgumentType.longArg(0)).executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                            Company c = need(ctx.getSource(), p);
                            if (c != null) {
                                c.money += LongArgumentType.getLong(ctx, "amount");
                                Tycoon.sync(p, c);
                            }
                            return 1;
                        })))
                .then(CommandManager.literal("level").requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 5)).executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                            Company c = need(ctx.getSource(), p);
                            if (c != null) {
                                CityBuilder.jumpToLevel(p, c, IntegerArgumentType.getInteger(ctx, "level"));
                            }
                            return 1;
                        })))
                .then(CommandManager.literal("buy").then(CommandManager.argument("id", StringArgumentType.word())
                        .suggests((c2, b) -> {
                            for (Upgrade u : Upgrade.values()) {
                                b.suggest(u.id);
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                            Upgrade u = Upgrade.byId(StringArgumentType.getString(ctx, "id"));
                            if (u == null) {
                                ctx.getSource().sendError(Text.literal("unknown upgrade"));
                                return 0;
                            }
                            Tycoon.buy(p, u);
                            return 1;
                        })))
                .then(CommandManager.literal("car").requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.argument("model", StringArgumentType.word())
                                .suggests((c2, b) -> {
                                    for (com.anant.teslatycoon.vehicle.VehicleSpec sp : com.anant.teslatycoon.vehicle.VehicleSpecs.LEVELS) {
                                        b.suggest(sp.id);
                                    }
                                    return b.buildFuture();
                                })
                                .executes(ctx -> {
                                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                                    Company c = need(ctx.getSource(), p);
                                    if (c == null) {
                                        return 0;
                                    }
                                    String id = StringArgumentType.getString(ctx, "model");
                                    for (com.anant.teslatycoon.vehicle.VehicleSpec sp : com.anant.teslatycoon.vehicle.VehicleSpecs.ALL) {
                                        if (sp.id.equals(id)) {
                                            ShowroomDirector.spawnOwnedCar(p, c, sp);
                                            return 1;
                                        }
                                    }
                                    ctx.getSource().sendError(Text.literal("unknown vehicle id"));
                                    return 0;
                                })))
                .then(CommandManager.literal("emp").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                    Tycoon.fireEmp(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }))
                .then(CommandManager.literal("drive").requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.literal("off").executes(ctx -> {
                            ModNetwork.sendDebugDrive(ctx.getSource().getPlayerOrThrow(), false, 0, 0, false, false, false);
                            return 1;
                        }))
                        .then(CommandManager.argument("throttle", FloatArgumentType.floatArg(-1, 1))
                                .then(CommandManager.argument("steer", FloatArgumentType.floatArg(-1, 1))
                                        .executes(ctx -> drive(ctx, ""))
                                        .then(CommandManager.argument("flags", StringArgumentType.word()).executes(ctx -> drive(ctx, StringArgumentType.getString(ctx, "flags")))))))
                .then(CommandManager.literal("sabotage").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    Company c = need(ctx.getSource(), p);
                    if (c != null) {
                        RivalDirector.triggerNow(Tycoon.worldOf(c), c, p);
                    }
                    return 1;
                })));
    }
}
