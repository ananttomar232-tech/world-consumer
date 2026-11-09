package com.anant.teslatycoon.client.render;

import com.anant.teslatycoon.TeslaTycoon;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Draw state around the mod's own core shader (assets/tesla_tycoon/shaders/core/vehicle.*). */
public final class VehicleGfx {
    private static ShaderProgram program;
    private static boolean warned;

    private static Matrix4f baseView;
    private static Matrix3f baseNormal;
    private static LightmapTextureManager lightmap;
    private static boolean textured;

    private VehicleGfx() {}

    public static void registerShader() {
        CoreShaderRegistrationCallback.EVENT.register(ctx ->
                ctx.register(TeslaTycoon.id("vehicle"), VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, p -> program = p));
    }

    public static boolean ready() {
        if (program == null && !warned) {
            warned = true;
            TeslaTycoon.LOGGER.error("vehicle core shader is not loaded; vehicles are not drawn");
        }
        return program != null;
    }

    public static int glId(Identifier id) {
        return MinecraftClient.getInstance().getTextureManager().getTexture(id != null ? id : MissingSprite.getMissingSpriteId()).getGlId();
    }

    /** Starts drawing in the current matrix space (entity, item or GUI). */
    public static void begin(MatrixStack matrices, int packedLight, float r, float g, float b, float a, Identifier texture) {
        baseView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrices.peek().getPositionMatrix());
        baseNormal = new Matrix3f(matrices.peek().getNormalMatrix());
        lightmap = MinecraftClient.getInstance().gameRenderer.getLightmapTextureManager();
        lightmap.enable();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(515);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(r, g, b, a);
        textured = texture != null;
        if (textured) {
            RenderSystem.setShaderTexture(0, glId(texture));
        }
        GlUniform lc = program.getUniform("LightCoords");
        if (lc != null) {
            lc.set((float) LightmapTextureManager.getBlockLightCoordinates(packedLight), (float) LightmapTextureManager.getSkyLightCoordinates(packedLight));
        }
    }

    /** 0 = opaque, 1 = alpha blended (glass), 2 = additive (flames). */
    public static void pass(int kind, float alpha) {
        GlUniform p = program.getUniform("Params");
        if (p != null) {
            p.set(alpha, textured && kind == 0 ? 1.0f : (textured && kind == 1 ? 1.0f : 0.0f), kind == 2 ? 1.0f : 0.0f, 0.0f);
        }
        switch (kind) {
            case 1 -> {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.depthMask(false);
            }
            case 2 -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
                RenderSystem.depthMask(false);
            }
            default -> {
                RenderSystem.disableBlend();
                RenderSystem.depthMask(true);
            }
        }
    }

    /** Draws one buffer with an extra transform (identity for the body). local may be null. */
    public static void draw(VertexBuffer buffer, Matrix4f local, Matrix3f localNormal) {
        Matrix4f mv = local == null ? baseView : new Matrix4f(baseView).mul(local);
        Matrix3f nm = localNormal == null ? baseNormal : new Matrix3f(baseNormal).mul(localNormal);
        GlUniform normalMat = program.getUniform("NormalMat");
        if (normalMat != null) {
            normalMat.set(nm);
        }
        buffer.bind();
        buffer.draw(mv, RenderSystem.getProjectionMatrix(), program);
    }

    public static void end() {
        VertexBuffer.unbind();
        if (lightmap != null) {
            lightmap.disable();
        }
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }
}
