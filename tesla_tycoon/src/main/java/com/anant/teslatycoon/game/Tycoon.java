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
            if (text.toLowerCase(Locale.ROOT).equals("#retryscene")) {
                sender.server.execute(() -> RivalDirector.retryScene(sender));
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
                    .append(Text.literal("Type /tycoon start to build the Anant Tesla Showroom city around you.").formatted(Formatting.WHITE)), false);
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
            c.carry += c.incomePerSecond() / 20.0;
            long whole = (long) c.carry;
            if (whole > 0) {
                c.money += whole;
                c.totalEarned += whole;
                c.carry -= whole;
            }
            c.cityTick++;
            ShowroomDirector.tick(w, c, p);
            RivalDirector.tick(w, c, p);
            if (t % 10 == (p.getId() & 7)) {
                ModNetwork.sendState(p, c, RivalDirector.empCooldown(p));
            }
        }
        if (t % 200 == 0) {
            st.markDirty();
        }
    }

    /** Pushes a fresh snapshot to the owner right now (after a purchase or an event). */
    public static void sync(ServerPlayerEntity p, Company c) {
        ModNetwork.sendState(p, c, RivalDirector.empCooldown(p));
        state().markDirty();
    }

    public static void buy(ServerPlayerEntity p, Upgrade u) {
        Company c = companyOf(p);
        if (c == null || !c.cityBuilt) {
            return;
        }
        String why = c.blockedReason(u);
        if (why != null) {
            p.sendMessage(Text.literal("Cannot buy " + Text.translatable(u.nameKey()).getString() + ": " + why).formatted(Formatting.RED), true);
            p.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0f, 1.0f);
            return;
        }
        c.money -= u.cost;
        ServerWorld w = worldOf(c);
        switch (u.kind) {
            case LEVEL -> {
                c.level = u.target;
                CityBuilder.upgradeShowroom(w, c, p);
            }
            case COUNTER -> {
                c.owned.add(u);
                CityBuilder.onCounterBought(w, c, p, u);
            }
            case EXTRA -> ShowroomDirector.giveStarterPosters(p, 4);
        }
        if (u.kind == Upgrade.Kind.LEVEL) {
            c.owned.add(u);
        }
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.2f);
        p.sendMessage(Text.literal("Purchased: ").formatted(Formatting.GREEN)
                .append(Text.translatable(u.nameKey()).formatted(Formatting.GOLD, Formatting.BOLD)), false);
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
