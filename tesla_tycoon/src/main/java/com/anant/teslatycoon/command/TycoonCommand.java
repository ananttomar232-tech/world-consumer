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

    private static int money(ServerCommandSource src, java.util.function.BiConsumer<ServerPlayerEntity, Company> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = src.getPlayerOrThrow();
        Company c = need(src, p);
        if (c != null) {
            action.accept(p, c);
            Tycoon.sync(p, c);
        }
        return 1;
    }

    private static void note(ServerPlayerEntity p, String msg) {
        p.sendMessage(Text.literal(msg).formatted(Formatting.GREEN), true);
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("money").requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.literal("add").then(CommandManager.argument("amount", LongArgumentType.longArg()).executes(ctx ->
                        money(ctx.getSource(), (p, c) -> {
                            c.money = Math.max(0, c.money + LongArgumentType.getLong(ctx, "amount"));
                            note(p, String.format(java.util.Locale.US, "Balance: $%,d", c.money));
                        }))))
                .then(CommandManager.literal("set").then(CommandManager.argument("amount", LongArgumentType.longArg(0)).executes(ctx ->
                        money(ctx.getSource(), (p, c) -> {
                            c.money = LongArgumentType.getLong(ctx, "amount");
                            c.carry = 0;
                            note(p, String.format(java.util.Locale.US, "Balance: $%,d", c.money));
                        }))))
                .then(CommandManager.literal("speed").then(CommandManager.argument("multiplier", FloatArgumentType.floatArg(0f, 1000f)).executes(ctx ->
                        money(ctx.getSource(), (p, c) -> {
                            c.speed = FloatArgumentType.getFloat(ctx, "multiplier");
                            note(p, String.format(java.util.Locale.US, "Income speed x%.2f: +$%,d/sec", c.speed, Math.round(c.incomePerSecond())));
                        }))))
                .then(CommandManager.literal("toggle").executes(ctx ->
                        money(ctx.getSource(), (p, c) -> {
                            c.hudHidden = !c.hudHidden;
                            note(p, c.hudHidden ? "Money HUD off" : "Money HUD on");
                        }))));
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
                }).then(CommandManager.literal("full").executes(ctx -> {
                    RivalDirector.retryScene(ctx.getSource().getPlayerOrThrow(), true);
                    return 1;
                })))
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
                .then(CommandManager.literal("deploy").requires(s -> s.hasPermissionLevel(2)).then(CommandManager.argument("id", StringArgumentType.word()).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                    Upgrade u = Upgrade.byId(StringArgumentType.getString(ctx, "id"));
                    if (u != null) {
                        net.minecraft.item.Item it = com.anant.teslatycoon.registry.ModItems.UPGRADES.get(u);
                        for (int i = 0; i < p.getInventory().size(); i++) {
                            if (p.getInventory().getStack(i).isOf(it)) {
                                Tycoon.deploy(p, u, p.getInventory().getStack(i));
                                break;
                            }
                        }
                    }
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
