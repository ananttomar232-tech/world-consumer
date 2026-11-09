package com.anant.teslatycoon.client.mixin;

import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Minecraft player is taller than a real driver: drawn at the cabin's own scale (about 0.7 - 0.85) the seated body fits under the roof. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "scale(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/util/math/MatrixStack;F)V", at = @At("TAIL"))
    private void tesla_tycoon$seatedScale(AbstractClientPlayerEntity player, MatrixStack matrices, float amount, CallbackInfo ci) {
        if (player.getVehicle() instanceof VehicleEntity v) {
            float k = v.spec.occScale;                                     // fitted to the cabin, so no head or arm pokes out of the car
            matrices.scale(k, k, k);
        }
    }
}
