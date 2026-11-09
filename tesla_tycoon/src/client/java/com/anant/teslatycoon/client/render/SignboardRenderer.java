package com.anant.teslatycoon.client.render;

import com.anant.teslatycoon.sign.SignboardEntity;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/** The showroom banner, the highway billboards (PNG once the ad is installed, an "advertise here" board before) and the text signs. */
public final class SignboardRenderer extends EntityRenderer<SignboardEntity> {
    public SignboardRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public Identifier getTexture(SignboardEntity entity) {
        return MissingSprite.getMissingSpriteId();
    }

    @Override
    public boolean shouldRender(SignboardEntity e, Frustum frustum, double x, double y, double z) {
        return frustum.isVisible(e.getBoundingBox().expand(e.signWidth() * 0.5, e.signHeight() * 0.5, e.signWidth() * 0.5));
    }

    @Override
    public void render(SignboardEntity e, float entityYaw, float tickDelta, MatrixStack m, VertexConsumerProvider vcp, int light) {
        if (!VehicleGfx.ready()) {
            return;
        }
        int kind = e.kind();
        float w = e.signWidth();
        float h = e.signHeight();
        Identifier tex = null;
        if (kind == SignboardEntity.SHOWROOM) {
            tex = SignTextures.get("showroom_banner");
        } else if (kind == SignboardEntity.BILLBOARD && e.hasAd()) {
            tex = SignTextures.get("city_billboard");
        }
        m.push();
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-e.getYaw()));
        if (tex != null) {
            VehicleGfx.begin(m, LightmapTextureManager.MAX_LIGHT_COORDINATE, 1f, 1f, 1f, 1f, tex);
        } else {
            float r = kind == SignboardEntity.PETROL ? 0.10f : 0.03f;
            float g = kind == SignboardEntity.PETROL ? 0.03f : 0.05f;
            float b = kind == SignboardEntity.PETROL ? 0.02f : 0.08f;
            VehicleGfx.begin(m, LightmapTextureManager.MAX_LIGHT_COORDINATE, r, g, b, 1f, null);
        }
        VehicleGfx.pass(0, 1f);
        VehicleGfx.draw(ProcMesh.quad(255), new Matrix4f().scale(w, h, 1f), null);
        VehicleGfx.end();
        if (tex == null) {
            drawText(e, kind, w, h, m, vcp);
        }
        m.pop();
    }

    private void drawText(SignboardEntity e, int kind, float w, float h, MatrixStack m, VertexConsumerProvider vcp) {
        TextRenderer tr = getTextRenderer();
        String[] lines;
        int[] colors;
        float[] scales;
        if (kind == SignboardEntity.PETROL) {
            lines = new String[]{"SETH GOTIYA'S", "PETROL MOTORS", "Diesel Zindabad!"};
            colors = new int[]{0xFFFFB347, 0xFFFF5A3C, 0xFFFFE08A};
            scales = new float[]{1.0f, 1.55f, 0.55f};
        } else if (kind == SignboardEntity.SHOP) {
            lines = new String[]{"TYCOON UPGRADE SHOP", "right-click the terminal"};
            colors = new int[]{0xFF35E6FF, 0xFFB8C7DD};
            scales = new float[]{1.4f, 0.6f};
        } else {
            lines = new String[]{"ADVERTISE HERE", "Anant Tesla Showroom ad space", "Buy the Billboard Marketing Pack, then right-click"};
            colors = new int[]{0xFF35E6FF, 0xFFFFFFFF, 0xFFFFD34D};
            scales = new float[]{2.3f, 0.95f, 0.78f};
        }
        float total = 0;
        for (int i = 0; i < lines.length; i++) {
            total += 9 * scales[i] * 1.25f;
        }
        float s = h * 0.78f / Math.max(total, 1f);
        float maxW = 0;
        for (int i = 0; i < lines.length; i++) {
            maxW = Math.max(maxW, tr.getWidth(lines[i]) * scales[i]);
        }
        s = Math.min(s, w * 0.9f / Math.max(maxW, 1f));
        float y = -total * s / 2f;
        for (int i = 0; i < lines.length; i++) {
            float sc = s * scales[i];
            m.push();
            m.translate(0, 0, 0.03);
            m.scale(sc, -sc, sc);
            float x = -tr.getWidth(lines[i]) / 2f;
            Matrix4f mat = m.peek().getPositionMatrix();
            tr.draw(Text.literal(lines[i]), x, -(y / sc) - 4.5f - 0f, colors[i], false, mat, vcp, TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
            m.pop();
            y += 9 * scales[i] * 1.25f * s;
        }
    }
}
