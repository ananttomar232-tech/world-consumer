package com.anant.teslatycoon.game;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.city.CityBuilder;
import com.anant.teslatycoon.command.TycoonCommand;
import com.anant.teslatycoon.net.ModNetwork;
import com.anant.teslatycoon.npc.ShowroomDirector;
import com.anant.teslatycoon.rival.RivalDirector;
import java.util.Locale;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The server-side heart: income, purchases, sync to the HUD, chat hooks. The big subsystems live in the directors. */
public final class Tycoon {
    public static MinecraftServer server;
    private static TycoonState state;

    private Tycoon() {}

    public static TycoonState state() {
        if (state == null && server != null) {
            state = TycoonState.get(server);
        }
        return state;
    }

    public static void init() {
        CityBuilder.init();
        com.anant.teslatycoon.vehicle.VehicleEntity.hook = new com.anant.teslatycoon.rival.GameVehicleHook();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            state = TycoonState.get(s);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            CityBuilder.shutdown();
            if (state != null) {
                state.markDirty();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            server = null;
            state = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(Tycoon::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(handler.player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> TycoonCommand.register(dispatcher));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getSignedContent().trim();
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.equals("#retryscene") || lower.equals("#retryscene full")) {
                boolean full = lower.endsWith("full");
                sender.server.execute(() -> RivalDirector.retryScene(sender, full));
                return false;
            }
            return true;
        });
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> RivalDirector.onPlayerChat(sender, message.getSignedContent()));
    }

    private static void onJoin(ServerPlayerEntity p) {
        TycoonState st = state();
        if (st == null) {
            return;
        }
        Company c = st.find(p.getUuid());
        if (c == null || !c.cityBuilt) {
            p.sendMessage(Text.literal("[Tesla Tycoon] ").formatted(Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal("Type /tycoon start to build the Anant Tesla Showroom city around you.").formatted(Formatting.WHITE)), true);
        } else {
            ModNetwork.sendState(p, c, RivalDirector.empCooldown(p));
        }
    }

    public static Company companyOf(ServerPlayerEntity p) {
        TycoonState st = state();
        return st == null ? null : st.find(p.getUuid());
    }

    public static ServerWorld worldOf(Company c) {
        if (server == null) {
            return null;
        }
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(c.dimension)) {
                return w;
            }
        }
        return server.getOverworld();
    }

    private static void tick(MinecraftServer s) {
        TycoonState st = state();
        if (st == null) {
            return;
        }
        CityBuilder.tick(s);
        RivalDirector.serverTick(s);
        int t = s.getTicks();
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            Company c = st.find(p.getUuid());
            if (c == null || !c.cityBuilt) {
                continue;
            }
            ServerWorld w = worldOf(c);
            if (w == null) {
                continue;
            }
            updateDriving(c, p);
            if (!c.cityComplete && t % 100 == 7 && !CityBuilder.busy()) {
                CityBuilder.resumeOutskirts(w, c, p);
            }
            c.carry += c.incomePerSecond() / 20.0;
            long whole = (long) c.carry;
            if (whole > 0) {
                c.money += whole;
                c.totalEarned += whole;
                c.carry -= whole;
            }
            c.cityTick++;
            ShowroomDirector.tick(w, c, p);
            com.anant.teslatycoon.npc.RobotaxiDirector.tick(w, c, p);
            RivalDirector.tick(w, c, p);
            if (t % 10 == (p.getId() & 7)) {
                ModNetwork.sendState(p, c, RivalDirector.empCooldown(p));
            }
        }
        if (t % 200 == 0) {
            st.markDirty();
        }
    }

    /** The driving bonus: the owner is at the wheel of a company car and it is moving (held for a second so the HUD does not flicker). */
    private static void updateDriving(Company c, ServerPlayerEntity p) {
        if (p.getVehicle() instanceof com.anant.teslatycoon.vehicle.VehicleEntity v && v.getControllingPassenger() == p) {
            double d = Math.hypot(v.getX() - c.lastDriveX, v.getZ() - c.lastDriveZ);
            c.lastDriveX = v.getX();
            c.lastDriveZ = v.getZ();
            if (d > 0.04 && d < 8.0) {
                c.drivingUntil = c.cityTick + 30;
            }
        } else {
            c.lastDriveX = Double.NaN;
            c.lastDriveZ = Double.NaN;
        }
        c.driving = c.cityTick < c.drivingUntil;
    }

    /** Pushes a fresh snapshot to the owner right now (after a purchase or an event). */
    public static void sync(ServerPlayerEntity p, Company c) {
        ModNetwork.sendState(p, c, RivalDirector.empCooldown(p));
        state().markDirty();
    }

    private static void bar(ServerPlayerEntity p, String msg, Formatting f) {
        p.sendMessage(Text.literal(msg).formatted(f), true);
    }

    private static boolean has(ServerPlayerEntity p, Upgrade u) {
        return p.getInventory().containsAny(st -> st.isOf(com.anant.teslatycoon.registry.ModItems.UPGRADES.get(u)));
    }

    /** A pedestal was right-clicked: take the money and hand over the physical item (nothing is built yet). */
    public static void buy(ServerPlayerEntity p, Upgrade u) {
        Company c = companyOf(p);
        if (c == null || !c.cityBuilt) {
            return;
        }
        String name = Text.translatable(u.nameKey()).getString();
        if (c.pending.contains(u)) {
            if (!has(p, u)) {
                p.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(com.anant.teslatycoon.registry.ModItems.UPGRADES.get(u)));
                bar(p, "Here it is again: " + name + ". Right-click to place it.", Formatting.AQUA);
            } else {
                bar(p, "You already carry " + name + ": right-click with it " + where(u) + ".", Formatting.AQUA);
            }
            return;
        }
        String why = c.blockedReason(u);
        if (why != null) {
            bar(p, name + ": " + why, Formatting.RED);
            p.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0f, 1.0f);
            return;
        }
        c.money -= u.cost;
        if (u.kind == Upgrade.Kind.EXTRA) {
            ShowroomDirector.giveStarterPosters(p, 4);
        } else {
            c.pending.add(u);
            net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(com.anant.teslatycoon.registry.ModItems.UPGRADES.get(u));
            if (p.getMainHandStack().isEmpty()) {
                p.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, stack);
            } else {
                p.getInventory().offerOrDrop(stack);
            }
            bar(p, name + " is in your hand: right-click with it " + where(u) + ".", Formatting.GREEN);
        }
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1.0f, 0.9f);
        sync(p, c);
    }

    private static String where(Upgrade u) {
        return u.kind == Upgrade.Kind.LEVEL ? "at the showroom" : "at the showroom or on the street";
    }

    /** The physical item is used: the floor is built / the countermeasure deployed, and the item is consumed. */
    public static void deploy(ServerPlayerEntity p, Upgrade u, net.minecraft.item.ItemStack stack) {
        Company c = companyOf(p);
        if (c == null || !c.cityBuilt || u.kind == Upgrade.Kind.EXTRA) {
            return;
        }
        if (c.owns(u)) {
            stack.decrement(1);
            return;
        }
        boolean free = p.getAbilities().creativeMode && !c.pending.contains(u);
        if (!c.pending.contains(u) && !free) {
            bar(p, "Buy it on its pedestal in the Upgrade Depot first.", Formatting.RED);
            return;
        }
        ServerWorld w = worldOf(c);
        double lx = p.getX() - c.origin.getX();
        double lz = p.getZ() - c.origin.getZ();
        if (u.kind == Upgrade.Kind.LEVEL) {
            boolean atShowroom = lx > com.anant.teslatycoon.city.CityLayout.SX0 - 14 && lx < com.anant.teslatycoon.city.CityLayout.SX1 + 6
                    && lz > com.anant.teslatycoon.city.CityLayout.SZ0 - 8 && lz < 22;
            if (!atShowroom) {
                bar(p, "Carry it to the Anant Tesla Showroom and right-click there.", Formatting.YELLOW);
                return;
            }
            if (u.target != c.level + 1 || CityBuilder.busyUrgent()) {
                bar(p, "The builders are busy, try again in a moment.", Formatting.YELLOW);
                return;
            }
        } else if (Math.abs(lx + 12) > 220 || Math.abs(lz) > 210) {
            bar(p, "Deploy it in the city.", Formatting.YELLOW);
            return;
        }
        c.pending.remove(u);
        stack.decrement(1);
        c.owned.add(u);
        if (u.kind == Upgrade.Kind.LEVEL) {
            c.level = u.target;
            CityBuilder.upgradeShowroom(w, c, p);
            ShowroomDirector.onLevelUp(c);
        } else {
            CityBuilder.onCounterBought(w, c, p, u);
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(5, 40, 15));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.translatable(u.nameKey()).formatted(Formatting.GOLD)));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal("DEPLOYED").formatted(Formatting.GREEN, Formatting.BOLD)));
        }
        w.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, p.getX(), p.getY() + 1.2, p.getZ(), 40, 1.2, 0.8, 1.2, 0.08);
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.2f);
        RivalDirector.onPurchase(w, c, p, u);
        sync(p, c);
    }

    public static void fireEmp(ServerPlayerEntity p) {
        RivalDirector.fireEmp(p);
    }

    public static void log(String msg) {
        TeslaTycoon.LOGGER.info(msg);
    }
}
