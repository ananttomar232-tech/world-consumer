package com.anant.teslatycoon.client.model;

import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.ResourceManager;
import org.lwjgl.opengl.GL30;

/** A texture uploaded at its full size with generated mipmaps and trilinear filtering (banners, posters, vehicle skins). */
public final class MipTexture extends AbstractTexture {
    private final NativeImage image;
    public final int width;
    public final int height;

    public MipTexture(NativeImage image) {
        this.image = image;
        this.width = image.getWidth();
        this.height = image.getHeight();
    }

    @Override
    public void load(ResourceManager manager) {
        // uploaded eagerly by upload()
    }

    /** Uploads the image (and closes it). Call after the texture has been registered with the TextureManager. */
    public void upload() {
        int levels = Math.max(0, Math.min(8, 31 - Integer.numberOfLeadingZeros(Math.min(width, height)) - 1));
        TextureUtil.prepareImage(getGlId(), levels, width, height);
        bindTexture();
        image.upload(0, 0, 0, 0, 0, width, height, false, true);
        setFilter(true, true);
        if (levels > 0) {
            GL30.glGenerateMipmap(3553);
        }
    }
}
