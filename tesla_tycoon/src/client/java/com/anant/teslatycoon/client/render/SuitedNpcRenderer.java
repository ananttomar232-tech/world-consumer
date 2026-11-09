package com.anant.teslatycoon.client.render;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.npc.SuitedNpcEntity;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** The villager body with a hand-painted business suit (textures/entity/suited_N.png, drawn by tools/gen_assets.py). */
public final class SuitedNpcRenderer extends MobEntityRenderer<SuitedNpcEntity, SuitedNpcRenderer.Model> {
    private static final Identifier[] TEXTURES = new Identifier[SuitedNpcEntity.LOOKS];

    static {
        for (int i = 0; i < TEXTURES.length; i++) {
            TEXTURES[i] = TeslaTycoon.id("textures/entity/suited_" + i + ".png");
        }
    }

    public SuitedNpcRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new Model(ctx.getPart(EntityModelLayers.VILLAGER)), 0.5f);
    }

    @Override
    public Identifier getTexture(SuitedNpcEntity e) {
        return TEXTURES[Math.floorMod(e.look(), TEXTURES.length)];
    }

    @Override
    protected void scale(SuitedNpcEntity e, MatrixStack matrices, float amount) {
        float s = e.role() == SuitedNpcEntity.GOTIYA ? 1.08f : 0.97f;
        boolean inCar = e.getVehicle() instanceof VehicleEntity;
        if (e.getVehicle() instanceof VehicleEntity v) {
            s *= v.spec.occScale;                                    // a customer in a car is scaled to fit under its roof
        }
        matrices.scale(s, s, s);
        if (e.isSitting() && !inCar) {
            matrices.translate(0, -0.52, 0);                         // (the manager on his chair)
        }
    }

    public static final class Model extends VillagerResemblingModel<SuitedNpcEntity> {
        private final ModelPart rightLeg;
        private final ModelPart leftLeg;

        public Model(ModelPart root) {
            super(root);
            this.rightLeg = root.getChild("right_leg");
            this.leftLeg = root.getChild("left_leg");
        }

        @Override
        public void setAngles(SuitedNpcEntity e, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
            super.setAngles(e, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
            if (e.getVehicle() instanceof VehicleEntity v) {
                float lp = v.spec.legPitch();                        // seated in a car: the legs reach the floor
                rightLeg.pitch = lp;
                leftLeg.pitch = lp;
                rightLeg.yaw = 0.12f;
                leftLeg.yaw = -0.12f;
            } else if (e.isSitting()) {
                rightLeg.pitch = -1.45f;
                leftLeg.pitch = -1.45f;
                rightLeg.yaw = 0.25f;
                leftLeg.yaw = -0.25f;
            }
        }
    }
}
