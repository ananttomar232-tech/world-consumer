package com.anant.teslatycoon.client.render;

import com.anant.teslatycoon.client.model.TtmModel;
import com.anant.teslatycoon.client.model.TtmModels;
import com.anant.teslatycoon.vehicle.ModelMeta;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Draws the baked vehicles: body, spinning and steering wheels, steering wheel, translucent glass, roadster nozzles and flames. */
public final class VehicleRenderer extends EntityRenderer<VehicleEntity> {
    private static final float LOCK = 0.42f;     // radians of front wheel lock (about 30 degrees)

    public VehicleRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public Identifier getTexture(VehicleEntity entity) {
        return MissingSprite.getMissingSpriteId();
    }

    @Override
    public boolean shouldRender(VehicleEntity e, Frustum frustum, double x, double y, double z) {
        double pad = e.spec.meta.length * 0.55;
        return frustum.isVisible(e.getBoundingBox().expand(pad, 0.5, pad));
    }

    /** Clear glass at 36%, the tinted sunroof (part glass_tint) almost opaque. */
    private static void drawGlass(TtmModel model, int lod) {
        if (lod >= 2) {
            return;
        }
        VehicleGfx.pass(1, 0.36f);
        for (TtmModel.Part part : model.parts) {
            if (part.glass && !part.name.equals("glass_tint") && part.buffers[lod] != null) {
                VehicleGfx.draw(part.buffers[lod], null, null);
            }
        }
        for (TtmModel.Part part : model.parts) {
            if (part.glass && part.name.equals("glass_tint") && part.buffers[lod] != null) {
                VehicleGfx.pass(1, 0.9f);
                VehicleGfx.draw(part.buffers[lod], null, null);
            }
        }
    }

    /** The whole car as a still object in the current matrix space (depot pedestals, the item in the hand). */
    public static void drawStatic(TtmModel model, MatrixStack matrices, int light, int lod) {
        if (!VehicleGfx.ready()) {
            return;
        }
        VehicleGfx.begin(matrices, light, 1f, 1f, 1f, 1f, model.textured ? model.texture : null);
        VehicleGfx.pass(0, 1f);
        for (TtmModel.Part part : model.parts) {
            if (!part.glass && part.buffers[lod] != null) {
                VehicleGfx.draw(part.buffers[lod], null, null);
            }
        }
        drawGlass(model, lod);
        VehicleGfx.end();
    }

    @Override
    public void render(VehicleEntity e, float entityYaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
        if (!VehicleGfx.ready()) {
            return;
        }
        TtmModel model = TtmModels.get(e.spec.model);
        if (model == null) {
            return;
        }
        shadowRadius = e.spec.shadow;
        int mode = e.getMode();
        float time = e.age + tickDelta;
        float yaw = e.lerpedYaw(tickDelta);
        if (mode == VehicleEntity.MODE_DISPLAY) {
            yaw += time * 0.9f;
        }
        float pop = MathHelper.clamp((e.spawnAge + tickDelta) / 16f, 0f, 1f);
        float scale = pop * pop * (3f - 2f * pop);
        if (scale < 0.02f) {
            return;
        }
        boolean mine = e.getControllingPassenger() instanceof PlayerEntity p && p.isMainPlayer();
        int lod = 0;
        if (!mine) {
            Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
            double d2 = e.squaredDistanceTo(cam);
            lod = d2 < 44 * 44 ? 0 : (d2 < 104 * 104 ? 1 : 2);
        }
        float spin = MathHelper.lerp(tickDelta, e.prevWheelSpin, e.wheelSpin);
        float steer = MathHelper.lerp(tickDelta, e.prevSteerAngle, e.steerAngle);
        boolean disabled = e.hasFlag(VehicleEntity.F_DISABLED);
        float tint = disabled ? (0.45f + 0.25f * MathHelper.sin(time * 0.9f)) : 1f;

        matrices.push();
        // (the curb step-up smoothing lives in CameraMixin: the body and the seated figure are always drawn on the real surface)
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        float pitch = e.lerpedPitch(tickDelta) + MathHelper.lerp(tickDelta, e.prevVisPitch, e.visPitch);
        float roll = MathHelper.lerp(tickDelta, e.prevVisRoll, e.visRoll);
        matrices.translate(0, 0.5, 0);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
        matrices.translate(0, -0.5, 0);
        if (scale < 0.999f) {
            matrices.scale(scale, scale, scale);
        }

        ModelMeta meta = model.meta;
        VehicleGfx.begin(matrices, light, tint, tint, tint, 1f, model.textured ? model.texture : null);
        VehicleGfx.pass(0, 1f);
        boolean seated = e.hasPassengers();
        // from the driver's own eyes the turning front wheels would cut through the footwell and the dashboard: they are not drawn
        boolean cockpit = mine && MinecraftClient.getInstance().options.getPerspective().isFirstPerson();
        for (TtmModel.Part part : model.parts) {
            if (part.glass || part.buffers[lod] == null) {
                continue;
            }
            if (seated && (part.name.equals("interior_back") || part.name.equals("interior_rear"))) {
                continue;               // the seat back and the rear bench would hide the seated figure's torso and cape from behind
            }
            if (part.wheel >= 0 && cockpit) {
                continue;
            }
            if (part.wheel >= 0) {
                ModelMeta.Wheel w = meta.wheels[part.wheel];
                float ys = w.front ? steer * LOCK : 0f;
                Matrix4f local = new Matrix4f().translate(w.pivot[0], w.pivot[1], w.pivot[2]).rotateY(ys).rotateX(spin)
                        .translate(-w.pivot[0], -w.pivot[1], -w.pivot[2]);
                Matrix3f nl = new Matrix3f().rotateY(ys).rotateX(spin);
                VehicleGfx.draw(part.buffers[lod], local, nl);
            } else {
                VehicleGfx.draw(part.buffers[lod], null, null);
            }
        }
        if (lod == 0 && meta.steer != null) {
            float[] s = meta.steer;
            Matrix4f local = new Matrix4f().translate(s[0], s[1], s[2]).rotateX((float) Math.toRadians(-s[3] + 90f - 90f)).rotateZ(steer * 2.2f);
            VehicleGfx.draw(ProcMesh.steeringWheel(), local, null);
        }
        boolean thrust = e.spec.canFly && (mine ? e.localThrust : e.hasFlag(VehicleEntity.F_THRUST));
        if (meta.thrusters != null && meta.thrusters.length > 0) {
            for (float[] t : meta.thrusters) {
                VehicleGfx.draw(ProcMesh.nozzle(), new Matrix4f().translate(t[0], t[1], t[2]), null);
            }
        }
        drawGlass(model, lod);
        if (meta.thrusters != null && meta.thrusters.length > 0 && (thrust || (e.isFlying() && !mine))) {
            VehicleGfx.pass(2, 1f);
            float flick = 1f + 0.22f * MathHelper.sin(time * 2.9f) + 0.12f * MathHelper.sin(time * 5.3f);
            float len = (thrust ? 2.6f : 0.9f) * flick;
            for (float[] t : meta.thrusters) {
                Matrix4f outer = new Matrix4f().translate(t[0], t[1], t[2] - 0.5f).scale(1.05f * flick, 1.05f * flick, len);
                VehicleGfx.draw(ProcMesh.flame(), outer, null);
                Matrix4f inner = new Matrix4f().translate(t[0], t[1], t[2] - 0.5f).scale(0.55f, 0.55f, len * 0.62f);
                VehicleGfx.draw(ProcMesh.flame(), inner, null);
            }
        }
        VehicleGfx.end();
        matrices.pop();
        super.render(e, entityYaw, tickDelta, matrices, vcp, light);
    }
}
