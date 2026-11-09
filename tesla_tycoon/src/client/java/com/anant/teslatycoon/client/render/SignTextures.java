package com.anant.teslatycoon.client.render;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.client.model.MipTexture;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

/** Loads the uploaded banner / billboard / poster PNGs at full resolution with mipmaps. */
public final class SignTextures {
    private static final Map<String, Identifier> CACHE = new HashMap<>();

    private SignTextures() {}

    public static Identifier get(String name) {
        if (CACHE.containsKey(name)) {
            return CACHE.get(name);
        }
        Identifier id = TeslaTycoon.id("textures/signboard/" + name + ".png");
        Identifier result = null;
        try {
            Resource r = MinecraftClient.getInstance().getResourceManager().getResource(id).orElseThrow();
            try (InputStream in = r.getInputStream()) {
                MipTexture tex = new MipTexture(NativeImage.read(in));
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
                tex.upload();
                result = id;
            }
        } catch (Throwable t) {
            TeslaTycoon.LOGGER.error("Could not load sign texture {}", id, t);
        }
        CACHE.put(name, result);
        return result;
    }

    public static void clear() {
        for (Identifier id : CACHE.values()) {
            if (id != null) {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
            }
        }
        CACHE.clear();
    }
}
