package com.anant.teslatycoon.client.input;

import com.anant.teslatycoon.net.ModNetwork;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.network.PacketByteBuf;
import org.lwjgl.glfw.GLFW;

/** Reads the keys of the local player at the moment the vehicle ticks (so there is no extra tick of input lag). */
public final class ClientBridgeImpl implements VehicleEntity.ClientBridge {
    public static KeyBinding RAM;
    public static KeyBinding EMP;
    public static KeyBinding GUIDE;
    public static boolean ramOn;
    /** The on-screen guides (what to buy next, Gotiya's alerts, key hints) are hidden until the player presses Right Shift. */
    public static boolean guideOn;
    /** Scripted controls for the headless smoke test (set by /tycoon drive, honoured only with -Dtesla_tycoon.debugdrive=true). */
    public static volatile float[] forced;
    private int lastBits = -1;
    private int sinceSend;

    public static void registerKeys() {
        guideOn = Boolean.getBoolean("tesla_tycoon.guide");        // (only the screenshot tours set this: players start with the guide hidden)
        RAM = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tesla_tycoon.ram", GLFW.GLFW_KEY_V, "key.categories.tesla_tycoon"));
        EMP = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tesla_tycoon.emp", GLFW.GLFW_KEY_R, "key.categories.tesla_tycoon"));
        GUIDE = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tesla_tycoon.guide", GLFW.GLFW_KEY_RIGHT_SHIFT, "key.categories.tesla_tycoon"));
    }

    @Override
    public void poll(VehicleEntity v, VehicleEntity.Controls out) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float[] f = forced;
        if (f != null && Boolean.getBoolean("tesla_tycoon.debugdrive") && mc.player != null && mc.player.getVehicle() == v) {
            out.throttle = f[0];
            out.steer = f[1];
            out.jump = f[2] > 0.5f;
            out.descend = f[3] > 0.5f;
            out.ram = f[4] > 0.5f;
            v.localThrust = out.jump && v.spec.canFly;
            return;
        }
        GameOptions o = mc.options;
        boolean usable = mc.currentScreen == null && mc.player != null && mc.player.getVehicle() == v;
        if (!usable) {
            out.clear();
            v.localThrust = false;
            return;
        }
        out.throttle = (o.forwardKey.isPressed() ? 1f : 0f) - (o.backKey.isPressed() ? 1f : 0f);
        out.steer = (o.leftKey.isPressed() ? 1f : 0f) - (o.rightKey.isPressed() ? 1f : 0f);
        out.jump = o.jumpKey.isPressed();
        out.descend = o.sprintKey.isPressed();
        out.ram = ramOn;
        v.localThrust = out.jump && v.spec.canFly;
    }

    @Override
    public void sync(VehicleEntity v, VehicleEntity.Controls c) {
        int bits = c.bits();
        sinceSend++;
        if (bits != lastBits || sinceSend >= 10) {
            lastBits = bits;
            sinceSend = 0;
            PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer(1));
            buf.writeByte(bits);
            ClientPlayNetworking.send(ModNetwork.VEHICLE_CONTROLS, buf);
        }
    }
}
