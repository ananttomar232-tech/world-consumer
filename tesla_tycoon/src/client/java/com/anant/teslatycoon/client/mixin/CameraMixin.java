package com.anant.teslatycoon.client.mixin;

import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A 5-block car needs a longer chase camera, and the seated (0.85 scale) driver's eyes sit a little lower than a standing player's. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private Entity focusedEntity;

    @Shadow
    protected abstract void moveBy(double x, double y, double z);

    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;clipToSpace(D)D"), index = 0)
    private double tesla_tycoon$chaseDistance(double distance) {
        if (focusedEntity != null && focusedEntity.getVehicle() instanceof VehicleEntity v) {
            return distance + 1.6 + v.spec.meta.length * 0.8;
        }
        return distance;
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void tesla_tycoon$eyeHeight(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (focused.getVehicle() instanceof VehicleEntity v) {
            if (thirdPerson) {
                moveBy(0.0, 0.7 + v.spec.meta.height * 0.25, 0.0);
            } else {
                moveBy(0.0, -0.24, 0.0);
            }
        }
    }
}
