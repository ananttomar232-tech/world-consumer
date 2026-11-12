package com.anant.teslatycoon.client.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Chat lines (Seth Gotiya's above all) stay on screen for 30 seconds instead of 10 before they fade. */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    private static final int TICKS = 600;

    @ModifyConstant(method = "render", constant = @Constant(intValue = 200))
    private int teslaTycoon$longerChat(int original) {
        return TICKS;
    }

    @ModifyConstant(method = "getMessageOpacityMultiplier", constant = @Constant(doubleValue = 200.0))
    private static double teslaTycoon$longerFade(double original) {
        return TICKS;
    }
}
