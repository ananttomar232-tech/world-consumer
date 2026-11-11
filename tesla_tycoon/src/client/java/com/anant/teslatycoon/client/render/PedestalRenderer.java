package com.anant.teslatycoon.client.render;

import com.anant.teslatycoon.client.ClientState;
import com.anant.teslatycoon.client.hud.TycoonHud;
import com.anant.teslatycoon.client.model.TtmModel;
import com.anant.teslatycoon.client.model.TtmModels;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.registry.ModItems;
import com.anant.teslatycoon.shop.PedestalEntity;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/** The floating, turning 3D model over a depot pedestal (the real car for a showroom level, the 3D item for a countermeasure) and its tag. */
public final class PedestalRenderer extends EntityRenderer<PedestalEntity> {
    public PedestalRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public Identifier getTexture(PedestalEntity entity) {
        return MissingSprite.getMissingSpriteId();
    }

    @Override
    public void render(PedestalEntity e, float yaw, float tickDelta, MatrixStack m, VertexConsumerProvider vcp, int light) {
        Upgrade u = e.upgrade();
        float t = e.age + tickDelta;
        int full = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        m.push();
        m.translate(0, 0.55 + 0.06 * MathHelper.sin(t * 0.08f), 0);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 2.4f));
        if (u.kind == Upgrade.Kind.LEVEL && VehicleGfx.ready()) {
            TtmModel model = TtmModels.get(VehicleSpecs.LEVELS.get(u.target - 1).model);
            if (model != null) {
                float s = 1.25f / Math.max(1f, model.meta.length);
                m.scale(s, s, s);
                VehicleRenderer.drawStatic(model, m, full, 1);
            }
        } else {
            m.translate(0, 0.25, 0);
            m.scale(1.3f, 1.3f, 1.3f);
            MinecraftClient.getInstance().getItemRenderer().renderItem(new ItemStack(ModItems.UPGRADES.get(u)), ModelTransformationMode.GROUND, full,
                    OverlayTexture.DEFAULT_UV, m, vcp, e.getWorld(), 0);
        }
        m.pop();
        // the tag: [Name] - $1,500 (green once you own it, gold when you can afford it)
        boolean owned = u.kind != Upgrade.Kind.EXTRA && ClientState.MIRROR.owns(u);
        boolean pending = ClientState.MIRROR.pending.contains(u);
        String why = ClientState.MIRROR.blockedReason(u);
        String label = "[" + Text.translatable(u.nameKey()).getString() + "] - " + (owned ? "OWNED" : (pending ? "PLACE IT" : "$" + TycoonHud.money(u.cost)));
        int color = owned ? 0xFF5CFF8A : (pending ? 0xFF35E6FF : (why == null ? 0xFFFFD34D : ("not enough money".equals(why) ? 0xFFFFFFFF : 0xFF9AA4B5)));
        TextRenderer tr = getTextRenderer();
        m.push();
        m.translate(0, 1.55, 0);
        m.multiply(dispatcher.getRotation());
        m.scale(-0.022f, -0.022f, 0.022f);
        Matrix4f mat = m.peek().getPositionMatrix();
        float w = tr.getWidth(label);
        tr.draw(label, -w / 2f, 0, color, false, mat, vcp, TextRenderer.TextLayerType.NORMAL, 0x90000000, full);
        if (!owned && !pending && why != null && !"not enough money".equals(why)) {
            String sub = why;
            tr.draw(sub, -tr.getWidth(sub) / 2f, 11, 0xFFB8C7DD, false, mat, vcp, TextRenderer.TextLayerType.NORMAL, 0x60000000, full);
        }
        m.pop();
    }
}
