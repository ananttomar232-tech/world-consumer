package com.anant.teslatycoon.net;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/** Channel ids, the server-side receivers and the senders used by the game logic. */
public final class ModNetwork {
    public static final Identifier VEHICLE_CONTROLS = TeslaTycoon.id("vehicle_controls");   // C2S: byte bits (1 jump, 2 descend, 4 ram)
    public static final Identifier BUY = TeslaTycoon.id("buy");                             // C2S: byte upgrade ordinal
    public static final Identifier EMP = TeslaTycoon.id("emp");                             // C2S: (empty) the R key
    public static final Identifier STATE = TeslaTycoon.id("state");                         // S2C: the company snapshot for the HUD / shop
    public static final Identifier OPEN_SHOP = TeslaTycoon.id("open_shop");                 // S2C: (empty)
    public static final Identifier DEBUG_DRIVE = TeslaTycoon.id("debug_drive");             // S2C: forced controls for scripted test scenes

    private ModNetwork() {}

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(VEHICLE_CONTROLS, (server, player, handler, buf, sender) -> {
            int bits = buf.readByte();
            server.execute(() -> {
                if (player.getVehicle() instanceof VehicleEntity v && v.getControllingPassenger() == player) {
                    v.onServerControls(bits);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(BUY, (server, player, handler, buf, sender) -> {
            Upgrade u = Upgrade.byOrdinal(buf.readByte());
            server.execute(() -> {
                if (u != null) {
                    Tycoon.buy(player, u);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(EMP, (server, player, handler, buf, sender) -> server.execute(() -> Tycoon.fireEmp(player)));
    }

    public static void sendState(ServerPlayerEntity p, Company c, int empCooldownTicks) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeLong(c.money);
        b.writeFloat((float) c.incomePerSecond());
        b.writeVarInt(c.level);
        int mask = 0;
        for (Upgrade u : c.owned) {
            mask |= 1 << u.ordinal();
        }
        b.writeInt(mask);
        b.writeVarInt(c.billboardsPlaced);
        b.writeVarInt(c.activeSabotage());
        b.writeVarInt((int) c.climaxHp);
        b.writeBoolean(c.powerOut);
        b.writeBoolean(c.cityBuilt);
        b.writeVarInt(c.ramSmashed);
        b.writeVarInt(empCooldownTicks);
        b.writeBoolean(c.driving);
        b.writeVarInt(com.anant.teslatycoon.rival.RivalDirector.alarmStage(c));
        ServerPlayNetworking.send(p, STATE, b);
    }

    public static void sendDebugDrive(ServerPlayerEntity p, boolean on, float throttle, float steer, boolean jump, boolean descend, boolean ram) {
        PacketByteBuf b = PacketByteBufs.create();
        b.writeBoolean(on);
        b.writeFloat(throttle);
        b.writeFloat(steer);
        b.writeBoolean(jump);
        b.writeBoolean(descend);
        b.writeBoolean(ram);
        ServerPlayNetworking.send(p, DEBUG_DRIVE, b);
    }

    public static void sendOpenShop(ServerPlayerEntity p) {
        ServerPlayNetworking.send(p, OPEN_SHOP, PacketByteBufs.create());
    }
}
