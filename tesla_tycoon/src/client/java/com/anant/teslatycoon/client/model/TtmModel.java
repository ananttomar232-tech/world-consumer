package com.anant.teslatycoon.client.model;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.vehicle.ModelMeta;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

/**
 * A baked vehicle (tools/bake_models.py): body, four wheels and a glass layer, each in three levels of detail, uploaded once
 * as GPU vertex buffers. Vertex layout in the file: pos f32x3, normal i8x3 (+pad), uv f32x2, colour u8x4, material u8x4
 * (metallic, roughness, emissive, untextured flag).
 */
public final class TtmModel {
    public static final int LODS = 3;

    public static final class Part {
        public String name;
        public boolean glass;
        public int wheel = -1;
        public final VertexBuffer[] buffers = new VertexBuffer[LODS];
        public final int[] triangles = new int[LODS];
    }

    public final String name;
    public final ModelMeta meta;
    public final List<Part> parts = new ArrayList<>();
    public Identifier texture;
    public boolean textured;

    private TtmModel(String name, ModelMeta meta) {
        this.name = name;
        this.meta = meta;
    }

    public static TtmModel load(String name) throws IOException {
        Identifier id = TeslaTycoon.id("vehicles/" + name + ".ttm");
        Resource resource = MinecraftClient.getInstance().getResourceManager().getResource(id)
                .orElseThrow(() -> new IOException("missing " + id));
        byte[] raw;
        try (InputStream in = resource.getInputStream()) {
            raw = in.readAllBytes();
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.get(0) != 'T' || buf.get(1) != 'T' || buf.get(2) != 'M' || buf.get(3) != '1') {
            throw new IOException("not a TTM1 file: " + id);
        }
        int headerLen = buf.getInt(4);
        String json = new String(raw, 8, headerLen, StandardCharsets.UTF_8);
        JsonObject header = new Gson().fromJson(json, JsonObject.class);
        ModelMeta meta = new Gson().fromJson(json, ModelMeta.class);
        TtmModel model = new TtmModel(name, meta);
        int blob = 8 + headerLen;
        if (header.has("texture") && !header.get("texture").isJsonNull()) {
            model.texture = new Identifier(header.get("texture").getAsString());
            model.textured = true;
            model.registerTexture();
        }
        for (var pe : header.getAsJsonArray("parts")) {
            JsonObject p = pe.getAsJsonObject();
            Part part = new Part();
            part.name = p.get("name").getAsString();
            part.glass = "glass".equals(p.get("layer").getAsString());
            part.wheel = p.get("wheel").getAsInt();
            var lods = p.getAsJsonArray("lods");
            for (int l = 0; l < LODS; l++) {
                JsonObject e = lods.get(l).getAsJsonObject();
                int vc = e.get("vc").getAsInt();
                int ic = e.get("ic").getAsInt();
                boolean wide = "i4".equals(e.get("it").getAsString());
                int off = blob + e.get("off").getAsInt();
                part.buffers[l] = upload(buf, off, vc, ic, wide);
                part.triangles[l] = ic / 3;
            }
            model.parts.add(part);
        }
        return model;
    }

    private void registerTexture() throws IOException {
        Identifier png = new Identifier(texture.getNamespace(), texture.getPath());
        Resource r = MinecraftClient.getInstance().getResourceManager().getResource(png).orElseThrow(() -> new IOException("missing " + png));
        try (InputStream in = r.getInputStream()) {
            NativeImage image = NativeImage.read(in);
            MipTexture tex = new MipTexture(image);
            MinecraftClient.getInstance().getTextureManager().registerTexture(texture, tex);
            tex.upload();
        }
    }

    private static VertexBuffer upload(ByteBuffer b, int off, int vc, int ic, boolean wide) {
        VertexFormat format = VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL;
        BufferBuilder builder = new BufferBuilder(ic * format.getVertexSizeByte() + 256);
        builder.begin(VertexFormat.DrawMode.TRIANGLES, format);
        int vbase = off;
        int ibase = off + vc * 32;
        for (int i = 0; i < ic; i++) {
            int index = wide ? b.getInt(ibase + i * 4) : (b.getShort(ibase + i * 2) & 0xFFFF);
            int o = vbase + index * 32;
            float x = b.getFloat(o);
            float y = b.getFloat(o + 4);
            float z = b.getFloat(o + 8);
            float nx = b.get(o + 12) / 127f;
            float ny = b.get(o + 13) / 127f;
            float nz = b.get(o + 14) / 127f;
            float u = b.getFloat(o + 16);
            float v = b.getFloat(o + 20);
            float r = (b.get(o + 24) & 0xFF) / 255f;
            float g = (b.get(o + 25) & 0xFF) / 255f;
            float bl = (b.get(o + 26) & 0xFF) / 255f;
            float a = (b.get(o + 27) & 0xFF) / 255f;
            int metal = b.get(o + 28) & 0xFF;
            int rough = b.get(o + 29) & 0xFF;
            int emis = b.get(o + 30) & 0xFF;
            int flag = b.get(o + 31) & 0xFF;
            builder.vertex(x, y, z, r, g, bl, a, u, v, metal | (rough << 16), emis | (flag << 16), nx, ny, nz);
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.end());
        VertexBuffer.unbind();
        return buffer;
    }

    public int triangles(int lod) {
        int t = 0;
        for (Part p : parts) {
            t += p.triangles[lod];
        }
        return t;
    }

    public void close() {
        for (Part p : parts) {
            for (VertexBuffer b : p.buffers) {
                if (b != null) {
                    b.close();
                }
            }
        }
        parts.clear();
        if (texture != null) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(texture);
        }
    }
}
