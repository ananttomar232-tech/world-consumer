package com.anant.teslatycoon.client.hud;

import com.anant.teslatycoon.client.ClientState;
import com.anant.teslatycoon.client.input.ClientBridgeImpl;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * The always-visible part is small: money, income, the showroom level and (during the finale) the showroom's health bar.
 * Everything that explains what to do - what to buy next, Gotiya's alerts and the key hints of the vehicle - is a guide that
 * is hidden until the player presses Right Shift (press again to hide it).
 */
public final class TycoonHud {
    private static final int PANEL = 0xB00A101C;
    private static final int CYAN = 0xFF35E6FF;
    private static final int GOLD = 0xFFFFD34D;
    private static final int GREEN = 0xFF5CFF8A;
    private static final int RED = 0xFFFF4D5A;
    private static final int GRAY = 0xFFB8C7DD;

    private TycoonHud() {}

    public static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!ClientState.present || mc.player == null || mc.options.hudHidden) {
            return;
        }
        TextRenderer tr = mc.textRenderer;
        long time = mc.world == null ? 0 : mc.world.getTime();
        float t = time + tickDelta;
        boolean guide = ClientBridgeImpl.guideOn;
        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();
        int x = 8;
        int y = 8;
        int w = 200;

        // ---- always: money, income, level
        ctx.fill(x, y, x + w, y + 60, PANEL);
        ctx.fill(x, y, x + 2, y + 60, CYAN);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x + 9, y + 6, 0);
        ctx.getMatrices().scale(1.7f, 1.7f, 1f);
        ctx.drawTextWithShadow(tr, String.format("$%,d", ClientState.MIRROR.money), 0, 0, GOLD);
        ctx.getMatrices().pop();
        ctx.drawTextWithShadow(tr, String.format("+$%.1f / sec", ClientState.income), x + 9, y + 27, ClientState.sabotage != 0 && ClientState.sabotage != 5 ? RED : GREEN);
        ctx.drawTextWithShadow(tr, Text.translatable("hud.tesla_tycoon.level", ClientState.MIRROR.level,
                Text.translatable("entity.tesla_tycoon." + VehicleSpecs.LEVELS.get(ClientState.MIRROR.level - 1).lang)), x + 9, y + 39, GRAY);

        int sab = ClientState.sabotage;
        int areaL = x + w + 10;                                   // the free area right of the money panel
        int areaW = Math.max(120, screenW - 8 - areaL);
        int cx = areaL + areaW / 2;
        int alertBottom = 8;                                      // where the alert (if shown) ends, so the health bar can sit below it

        // ---- the guide (Right Shift): what to buy next, Gotiya's alert, blackout, the vehicle's keys
        if (guide) {
            int gy = y + 64;
            Text title = Text.translatable("hud.tesla_tycoon.guide_title");
            Upgrade next = ClientState.next();
            Text nextLine = next == null ? null : Text.translatable("hud.tesla_tycoon.next", Text.translatable(next.nameKey()), String.format("%,d", next.cost));
            Text shopHint = Text.translatable("hud.tesla_tycoon.shop_hint");
            Text blackout = Text.translatable("hud.tesla_tycoon.blackout");
            int gw = Math.max(w, tr.getWidth(title) + 14);
            if (nextLine != null) {
                gw = Math.max(gw, Math.max(tr.getWidth(nextLine), tr.getWidth(shopHint)) + 14);
            }
            if (ClientState.powerOut) {
                gw = Math.max(gw, tr.getWidth(blackout) + 14);
            }
            int lines = 1 + (nextLine != null ? 2 : 0) + (ClientState.powerOut ? 1 : 0);
            ctx.fill(x, gy, x + gw, gy + 6 + lines * 11, PANEL);
            ctx.fill(x, gy, x + 2, gy + 6 + lines * 11, GOLD);
            int ly = gy + 4;
            ctx.drawTextWithShadow(tr, title, x + 7, ly, GOLD);
            ly += 11;
            if (nextLine != null) {
                boolean afford = ClientState.MIRROR.money >= next.cost;
                ctx.drawTextWithShadow(tr, nextLine, x + 7, ly, afford ? GREEN : GRAY);
                ly += 11;
                ctx.drawTextWithShadow(tr, shopHint, x + 7, ly, CYAN);
                ly += 11;
            }
            if (ClientState.powerOut) {
                ctx.drawTextWithShadow(tr, blackout, x + 7, ly, RED);
            }
            if (sab != 0) {
                float pulse = 0.55f + 0.45f * MathHelper.sin(t * 0.35f);
                int a = ((int) (255 * pulse)) << 24;
                List<OrderedText> head = tr.wrapLines(Text.translatable("hud.tesla_tycoon.sabotage." + sab), areaW - 20);
                List<OrderedText> hint = tr.wrapLines(Text.translatable("hud.tesla_tycoon.counter." + sab), areaW - 20);
                int tw = 0;
                for (OrderedText o : head) {
                    tw = Math.max(tw, tr.getWidth(o));
                }
                for (OrderedText o : hint) {
                    tw = Math.max(tw, tr.getWidth(o));
                }
                int bw = Math.min(areaW, tw + 24);
                int bh = 7 + (head.size() + hint.size()) * 10 + 3;
                ctx.fill(cx - bw / 2, 8, cx + bw / 2, 8 + bh, 0xC0200008);
                ctx.fill(cx - bw / 2, 8, cx + bw / 2, 10, a | 0xFF4D5A);
                int ty = 13;
                for (OrderedText o : head) {
                    ctx.drawCenteredTextWithShadow(tr, o, cx, ty, 0xFFFF6B75);
                    ty += 10;
                }
                for (OrderedText o : hint) {
                    ctx.drawCenteredTextWithShadow(tr, o, cx, ty, 0xFFFFE4A8);
                    ty += 10;
                }
                alertBottom = 8 + bh + 4;
            }
        }

        // ---- always while the Monster Truck attacks: the showroom's health (a status, not a guide)
        if (sab == 5) {
            int bw2 = Math.min(160, areaW);
            int by2 = guide ? alertBottom : 10;
            ctx.fill(cx - bw2 / 2, by2, cx + bw2 / 2, by2 + 8, 0xC0000000);
            ctx.fill(cx - bw2 / 2 + 1, by2 + 1, cx - bw2 / 2 + 1 + (bw2 - 2) * Math.max(0, ClientState.climaxHp) / 100, by2 + 7, ClientState.climaxHp > 35 ? 0xFF35E6FF : 0xFFFF4D5A);
            ctx.drawCenteredTextWithShadow(tr, Text.translatable("hud.tesla_tycoon.showroom_hp", ClientState.climaxHp), cx, by2 + 10, 0xFFFFFFFF);
        }

        // ---- the vehicle's key hints (guide only), bottom right: out of the way of the chat and the hotbar
        if (guide && mc.player.getVehicle() instanceof VehicleEntity v) {
            List<Text> texts = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            if (v.spec.canFly) {
                boolean ready = ClientState.empCooldown <= 0 && ClientState.MIRROR.level >= 5;
                texts.add(Text.translatable("hud.tesla_tycoon.fly"));
                colors.add(CYAN);
                texts.add(ready ? Text.translatable("hud.tesla_tycoon.emp_ready") : Text.translatable("hud.tesla_tycoon.emp_cooldown", Math.max(1, ClientState.empCooldown / 20)));
                colors.add(ready ? GREEN : GRAY);
            } else if (v.spec.heavy && ClientState.MIRROR.owns(Upgrade.RAM)) {
                texts.add(Text.translatable(v.hasFlag(VehicleEntity.F_RAM) ? "hud.tesla_tycoon.ram_on" : "hud.tesla_tycoon.ram_off"));
                colors.add(v.hasFlag(VehicleEntity.F_RAM) ? RED : GRAY);
            } else if (ClientState.MIRROR.owns(Upgrade.HYDRAULICS)) {
                texts.add(Text.translatable("hud.tesla_tycoon.hop"));
                colors.add(GRAY);
            }
            List<OrderedText> lines = new ArrayList<>();
            List<Integer> lineColors = new ArrayList<>();
            int lw = 0;
            for (int i = 0; i < texts.size(); i++) {
                for (OrderedText o : tr.wrapLines(texts.get(i), 150)) {
                    lines.add(o);
                    lineColors.add(colors.get(i));
                    lw = Math.max(lw, tr.getWidth(o));
                }
            }
            if (!lines.isEmpty()) {
                int right = screenW - 8;
                int top = screenH - 30 - lines.size() * 10;
                ctx.fill(right - lw - 10, top - 4, right, top + lines.size() * 10 + 2, PANEL);
                for (int i = 0; i < lines.size(); i++) {
                    ctx.drawTextWithShadow(tr, lines.get(i), right - lw - 5, top + i * 10, lineColors.get(i));
                }
            }
        }
    }
}
