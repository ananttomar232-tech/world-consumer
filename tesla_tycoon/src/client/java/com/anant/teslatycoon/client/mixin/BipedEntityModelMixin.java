package com.anant.teslatycoon.client.mixin;

import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The driver holds the steering wheel: both arms forward, hands turning with the wheel. */
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin {
    @Shadow
    @Final
    public ModelPart rightArm;

    @Shadow
    @Final
    public ModelPart leftArm;

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void tesla_tycoon$holdWheel(LivingEntity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw,
                                        float headPitch, CallbackInfo ci) {
        if (entity.getVehicle() instanceof VehicleEntity v && v.getControllingPassenger() == entity) {
            float s = v.steerAngle;
            rightArm.pitch = -1.22f - s * 0.12f;
            leftArm.pitch = -1.22f + s * 0.12f;
            rightArm.yaw = -0.22f - s * 0.05f;
            leftArm.yaw = 0.22f - s * 0.05f;
            rightArm.roll = 0.04f;
            leftArm.roll = -0.04f;
        }
    }
}
