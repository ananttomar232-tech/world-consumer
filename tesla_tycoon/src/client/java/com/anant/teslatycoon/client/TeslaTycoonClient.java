package com.anant.teslatycoon.client;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.client.input.ClientBridgeImpl;
import com.anant.teslatycoon.client.model.TtmModels;
import com.anant.teslatycoon.client.render.ProcMesh;
import com.anant.teslatycoon.client.render.VehicleGfx;
import com.anant.teslatycoon.client.render.PosterRenderer;
import com.anant.teslatycoon.client.render.SignTextures;
import com.anant.teslatycoon.client.render.SignboardRenderer;
import com.anant.teslatycoon.client.render.SuitedNpcRenderer;
import com.anant.teslatycoon.client.render.VehicleRenderer;
import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpec;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import com.anant.teslatycoon.client.hud.TycoonHud;
import com.anant.teslatycoon.net.ModNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.network.PacketByteBuf;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

public final class TeslaTycoonClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        VehicleGfx.registerShader();
        ClientBridgeImpl.registerKeys();
        VehicleEntity.bridge = new ClientBridgeImpl();
        for (VehicleSpec spec : VehicleSpecs.ALL) {
            EntityRendererRegistry.register(ModEntities.vehicle(spec), VehicleRenderer::new);
        }
        EntityRendererRegistry.register(ModEntities.SIGNBOARD, SignboardRenderer::new);
        EntityRendererRegistry.register(ModEntities.POSTER, PosterRenderer::new);
        EntityRendererRegistry.register(ModEntities.SUITED, SuitedNpcRenderer::new);
        EntityRendererRegistry.register(ModEntities.PEDESTAL, com.anant.teslatycoon.client.render.PedestalRenderer::new);
        EntityRendererRegistry.register(ModEntities.CAMERA, net.minecraft.client.render.entity.EmptyEntityRenderer::new);
        // a showroom-level upgrade in the hand is the car itself, in 3D
        java.util.Map<net.minecraft.item.Item, String> carItems = new java.util.HashMap<>();
        for (com.anant.teslatycoon.game.Upgrade u : com.anant.teslatycoon.game.Upgrade.values()) {
            if (u.kind == com.anant.teslatycoon.game.Upgrade.Kind.LEVEL) {
                carItems.put(com.anant.teslatycoon.registry.ModItems.UPGRADES.get(u), VehicleSpecs.LEVELS.get(u.target - 1).model);
            }
        }
        for (VehicleSpec spec : VehicleSpecs.ALL) {
            carItems.put(com.anant.teslatycoon.registry.ModItems.SPAWNERS.get(spec), spec.model);
        }
        for (java.util.Map.Entry<net.minecraft.item.Item, String> ci : carItems.entrySet()) {
            final String model = ci.getValue();
            net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry.INSTANCE.register(ci.getKey(),
                    (stack, mode, matrices, vcp, light, overlay) -> {
                        com.anant.teslatycoon.client.model.TtmModel m = TtmModels.get(model);
                        if (m == null) {
                            return;
                        }
                        if (vcp instanceof net.minecraft.client.render.VertexConsumerProvider.Immediate im) {
                            im.draw();
                        }
                        matrices.push();
                        matrices.translate(0.5, 0.3, 0.5);
                        float s = 0.95f / Math.max(1f, m.meta.length);
                        matrices.scale(s, s, s);
                        com.anant.teslatycoon.client.render.VehicleRenderer.drawStatic(m, matrices, light, 1);
                        matrices.pop();
                    });
        }
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return TeslaTycoon.id("models");
            }

            @Override
            public void reload(ResourceManager manager) {
                TtmModels.clear();
                ProcMesh.clear();
                SignTextures.clear();
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            TtmModels.preload();
            String view = System.getProperty("tesla_tycoon.thirdperson", "");      // used by tools/smoke_client.py to photograph the seated driver
            if ("front".equals(view)) {
                client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_FRONT);
            } else if (Boolean.parseBoolean(view)) {
                client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
            }
        }));
        com.anant.teslatycoon.client.voice.VoiceChat.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            com.anant.teslatycoon.client.voice.VoiceChat.tick(client);
            while (ClientBridgeImpl.GUIDE.wasPressed()) {
                ClientBridgeImpl.guideOn = !ClientBridgeImpl.guideOn;       // Right Shift shows / hides the guides
            }
            while (ClientBridgeImpl.RAM.wasPressed()) {
                ClientBridgeImpl.ramOn = !ClientBridgeImpl.ramOn;
            }
            while (ClientBridgeImpl.EMP.wasPressed()) {
                if (client.player != null && client.player.getVehicle() instanceof VehicleEntity) {
                    ClientPlayNetworking.send(ModNetwork.EMP, new PacketByteBuf(io.netty.buffer.Unpooled.buffer(0)));
                }
            }
        });
        HudRenderCallback.EVENT.register(TycoonHud::render);
        HudRenderCallback.EVENT.register((ctx, delta) -> com.anant.teslatycoon.client.voice.VoiceChat.renderHud(ctx));
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.STATE, (client, handler, buf, sender) -> {
            long money = buf.readLong();
            float income = buf.readFloat();
            int level = buf.readVarInt();
            int mask = buf.readInt();
            int billboards = buf.readVarInt();
            int sab = buf.readVarInt();
            int hp = buf.readVarInt();
            boolean power = buf.readBoolean();
            boolean city = buf.readBoolean();
            int smashed = buf.readVarInt();
            int emp = buf.readVarInt();
            int pend = buf.readInt();
            boolean hudHidden = buf.readBoolean();
            boolean driving = buf.readBoolean();
            int alarm = buf.readVarInt();
            client.execute(() -> {
                ClientState.apply(money, income, level, mask, billboards, sab, hp, power, city, smashed, emp);
                ClientState.driving = driving;
                ClientState.alarm = alarm;
                ClientState.MIRROR.pending.clear();
                for (com.anant.teslatycoon.game.Upgrade u : com.anant.teslatycoon.game.Upgrade.values()) {
                    if ((pend & (1 << u.ordinal())) != 0) {
                        ClientState.MIRROR.pending.add(u);
                    }
                }
                ClientState.hudHidden = hudHidden;
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(ModNetwork.DEBUG_DRIVE, (client, handler, buf, sender) -> {
            boolean on = buf.readBoolean();
            float t = buf.readFloat();
            float st = buf.readFloat();
            boolean j = buf.readBoolean();
            boolean d = buf.readBoolean();
            boolean r = buf.readBoolean();
            ClientBridgeImpl.forced = on ? new float[]{t, st, j ? 1f : 0f, d ? 1f : 0f, r ? 1f : 0f} : null;
        });
    }
}
