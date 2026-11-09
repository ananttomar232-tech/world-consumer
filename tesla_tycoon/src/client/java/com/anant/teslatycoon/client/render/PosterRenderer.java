package com.anant.teslatycoon.client.render;

import com.anant.teslatycoon.sign.PosterEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/** The luxury wall poster: wall_poster.png on a 3 x 4 block quad, slightly self-lit so it reads in a dim showroom. */
public final class PosterRenderer extends EntityRenderer<PosterEntity> {
    public PosterRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public Identifier getTexture(PosterEntity entity) {
        return MissingSprite.getMissingSpriteId();
    }

    @Override
    public void render(PosterEntity e, float yaw, float tickDelta, MatrixStack m, VertexConsumerProvider vcp, int light) {
        if (!VehicleGfx.ready()) {
            return;
        }
        Identifier tex = SignTextures.get("wall_poster");
        if (tex == null) {
            return;
        }
        m.push();
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-e.getHorizontalFacing().asRotation()));
        VehicleGfx.begin(m, light, 1f, 1f, 1f, 1f, tex);
        VehicleGfx.pass(0, 1f);
        VehicleGfx.draw(ProcMesh.quad(110), new Matrix4f().translate(0, 0, 0.004f).scale(3f, 4f, 1f), null);
        VehicleGfx.end();
        m.pop();
    }
}
