package com.anant.teslatycoon.client.mixin;

import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Minecraft player is 1.4x taller than a real driver; drawn at 0.85 scale the seated body fits the cabins of the models. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "scale(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/util/math/MatrixStack;F)V", at = @At("TAIL"))
    private void tesla_tycoon$seatedScale(AbstractClientPlayerEntity player, MatrixStack matrices, float amount, CallbackInfo ci) {
        if (player.getVehicle() instanceof VehicleEntity) {
            matrices.scale(0.85f, 0.85f, 0.85f);
        }
    }
}
