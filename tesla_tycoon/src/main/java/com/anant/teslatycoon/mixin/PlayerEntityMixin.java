package com.anant.teslatycoon.mixin;

import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shift is "descend / dismount": never throw the pilot out of a flying Roadster. */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Inject(method = "shouldDismount", at = @At("HEAD"), cancellable = true)
    private void tesla_tycoon$keepPilotSeated(CallbackInfoReturnable<Boolean> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self.getVehicle() instanceof VehicleEntity v && v.blocksDismount()) {
            cir.setReturnValue(false);
        }
    }
}
