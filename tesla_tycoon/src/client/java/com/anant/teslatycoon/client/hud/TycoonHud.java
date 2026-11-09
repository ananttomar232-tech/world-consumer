package com.anant.teslatycoon.client.hud;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.client.ClientState;
import com.anant.teslatycoon.client.input.ClientBridgeImpl;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * The always-visible part is the ProBoiz-style cash bar: a dark rounded pill with a glowing green stack of cash on the left and the
 * money ("$25,619") in it, the income in small green text underneath ("+$35/sec"). Everything that explains what to do - what to buy
 * next, Gotiya's alerts and the key hints of the vehicle - is a guide that is hidden until the player presses Right Shift. The red
 * alarm frame and the showroom's health bar are statuses of the finale and are always shown while they matter.
 */
public final class TycoonHud {
    private static final Identifier CASH = TeslaTycoon.id("textures/gui/cash_stack.png");
    private static final int PANEL = 0xB00A101C;
    private static final int CYAN = 0xFF35E6FF;
    private static final int GOLD = 0xFFFFD34D;
    private static final int GREEN = 0xFF5CFF8A;
    private static final int RED = 0xFFFF4D5A;
    private static final int GRAY = 0xFFB8C7DD;

    // the money shown counts towards the real value instead of jumping
    private static float shown = Float.NaN;
    private static long lastNanos;
    private static long lastTarget = Long.MIN_VALUE;
    private static float flash;               // +1 green (earned a lot at once) .. -1 red (spent)
    private static final List<float[]> POPUPS = new ArrayList<>();      // {amount, age seconds}

    private TycoonHud() {}

    /** Whole dollars with thousands separators, e.g. 25,619. */
    public static String money(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    /** A rounded rectangle without textures (stepped corners of radius r). */
    static void rounded(DrawContext ctx, int x1, int y1, int x2, int y2, int r, int color) {
        r = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        ctx.fill(x1, y1 + r, x2, y2 - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
            ctx.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            ctx.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
        }
    }

    private static void tickMoney(float income) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : MathHelper.clamp((now - lastNanos) / 1.0e9f, 0f, 0.1f);
        lastNanos = now;
        long target = ClientState.MIRROR.money;
        if (Float.isNaN(shown)) {
            shown = target;
            lastTarget = target;
        }
        long jump = target - lastTarget;
        if (jump != 0) {
            float big = Math.max(40f, income * 2.5f);
            if (Math.abs(jump) >= big) {
                flash = jump > 0 ? 1f : -1f;
                if (jump > 0 && POPUPS.size() < 4) {
                    POPUPS.add(new float[]{jump, 0f});
                }
            }
            lastTarget = target;
        }
        shown += (target - shown) * (1f - (float) Math.exp(-11f * dt));
        if (Math.abs(target - shown) < 0.6f) {
            shown = target;
        }
        flash *= (float) Math.exp(-3.2f * dt);
        for (int i = POPUPS.size() - 1; i >= 0; i--) {
            POPUPS.get(i)[1] += dt;
            if (POPUPS.get(i)[1] > 1.6f) {
                POPUPS.remove(i);
            }
        }
    }

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
        tickMoney(ClientState.income);

        // ---- always: the ProBoiz cash bar
        String amount = "$" + money((long) shown);
        float scale = 1.5f;
        int textW = (int) (tr.getWidth(amount) * scale);
        int barW = Math.max(112, 36 + textW + 14);
        int barH = 26;
        int glow = (int) (60 + 50 * MathHelper.sin(t * 0.08f)) & 0xFF;
        rounded(ctx, x - 1, y - 1, x + barW + 1, y + barH + 1, 9, (glow << 24) | 0x1FE06A);          // soft green outline glow
        rounded(ctx, x, y, x + barW, y + barH, 8, 0xF2101826);
        rounded(ctx, x + 1, y + 1, x + barW - 1, y + barH - 1, 7, 0xF20A0F1A);
        rounded(ctx, x + 2, y + 2, x + 29, y + barH - 2, 6, 0x66134D2C);                          // the lit socket of the icon
        ctx.drawTexture(CASH, x + 3, y + 1, 24, 24, 0f, 0f, 48, 48, 48, 48);
        int tint = flash > 0.02f ? lerpColor(0xFFFFFFFF, 0xFF7BFFA6, flash) : (flash < -0.02f ? lerpColor(0xFFFFFFFF, 0xFFFF6B75, -flash) : 0xFFFFFFFF);
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x + 33, y + 7, 0);
        ctx.getMatrices().scale(scale, scale, 1f);
        ctx.drawTextWithShadow(tr, amount, 0, 0, tint);
        ctx.getMatrices().pop();
        int sab = ClientState.sabotage;
        String rate = "+$" + money(Math.round(ClientState.income)) + "/sec";
        int rateColor = 0xFF4DFF88;                               // always the clean green of the brief (Gotiya's alert is in the guide)
        ctx.getMatrices().push();
        ctx.getMatrices().translate(x + 7, y + barH + 4, 0);
        ctx.getMatrices().scale(0.9f, 0.9f, 1f);
        ctx.drawTextWithShadow(tr, rate, 0, 0, rateColor);
        if (ClientState.driving) {
            ctx.drawTextWithShadow(tr, "  x2 DRIVING", tr.getWidth(rate), 0, GOLD);
        }
        ctx.getMatrices().pop();
        for (float[] pu : POPUPS) {
            float a = 1f - MathHelper.clamp((pu[1] - 0.9f) / 0.7f, 0f, 1f);
            int alpha = (int) (255 * a) & 0xFF;
            if (alpha > 8) {
                ctx.drawTextWithShadow(tr, "+$" + money((long) pu[0]), x + barW + 8, y + 8 - (int) (pu[1] * 9), (alpha << 24) | 0xFFD34D);
            }
        }

        int areaL = x + Math.max(barW, 180) + 10;                 // the free area right of the bar
        int areaW = Math.max(120, screenW - 8 - areaL);
        int cx = areaL + areaW / 2;
        int alertBottom = 8;                                      // where the alert (if shown) ends, so the health bar can sit below it

        // ---- the finale's alarm frame (red, pulsing) and its banner
        if (ClientState.alarm > 0) {
            float pulse = 0.5f + 0.5f * MathHelper.sin(t * 0.55f);
            int a1 = (int) (30 + 90 * pulse);
            int edge = Math.max(28, screenH / 9);
            ctx.fillGradient(0, 0, screenW, edge, (a1 << 24) | 0xFF0010, 0x00FF0010);
            ctx.fillGradient(0, screenH - edge, screenW, screenH, 0x00FF0010, (a1 << 24) | 0xFF0010);
            ctx.fillGradient(0, 0, edge / 2, screenH, (a1 << 24) | 0xFF0010, 0x00FF0010);
            Text banner = Text.translatable(ClientState.alarm == 1 ? "hud.tesla_tycoon.alarm.gate" : "hud.tesla_tycoon.alarm.attack");
            int bw = tr.getWidth(banner) + 24;
            int bx = Math.max(areaL, Math.min(screenW - 8 - bw, cx - bw / 2));
            ctx.fill(bx, 8, bx + bw, 8 + 16, 0xD0300008);
            ctx.fill(bx, 8, bx + bw, 10, ((int) (140 + 115 * pulse) << 24) | 0xFF2A3A);
            ctx.drawCenteredTextWithShadow(tr, banner, bx + bw / 2, 13, 0xFFFFD0D0);
            alertBottom = 8 + 16 + 4;
        }

        // ---- the guide (Right Shift): the level, what to buy next, Gotiya's alert, blackout, the vehicle's keys
        if (guide) {
            int gy = y + barH + 18;
            Text title = Text.translatable("hud.tesla_tycoon.guide_title");
            Text levelLine = Text.translatable("hud.tesla_tycoon.level", ClientState.MIRROR.level,
                    Text.translatable("entity.tesla_tycoon." + VehicleSpecs.LEVELS.get(ClientState.MIRROR.level - 1).lang));
            Upgrade next = ClientState.next();
            Text nextLine = next == null ? null : Text.translatable("hud.tesla_tycoon.next", Text.translatable(next.nameKey()), money(next.cost));
            Text shopHint = Text.translatable("hud.tesla_tycoon.shop_hint");
            Text blackout = Text.translatable("hud.tesla_tycoon.blackout");
            Text alarmHint = ClientState.alarm == 1 ? Text.translatable("hud.tesla_tycoon.alarm.hint1") : (ClientState.alarm == 2 ? Text.translatable("hud.tesla_tycoon.alarm.hint2") : null);
            int gw = Math.max(190, Math.max(tr.getWidth(title), tr.getWidth(levelLine)) + 14);
            if (nextLine != null) {
                gw = Math.max(gw, Math.max(tr.getWidth(nextLine), tr.getWidth(shopHint)) + 14);
            }
            if (ClientState.powerOut) {
                gw = Math.max(gw, tr.getWidth(blackout) + 14);
            }
            if (alarmHint != null) {
                gw = Math.max(gw, tr.getWidth(alarmHint) + 14);
            }
            int lines = 2 + (nextLine != null ? 2 : 0) + (ClientState.powerOut ? 1 : 0) + (alarmHint != null ? 1 : 0);
            ctx.fill(x, gy, x + gw, gy + 6 + lines * 11, PANEL);
            ctx.fill(x, gy, x + 2, gy + 6 + lines * 11, GOLD);
            int ly = gy + 4;
            ctx.drawTextWithShadow(tr, title, x + 7, ly, GOLD);
            ly += 11;
            ctx.drawTextWithShadow(tr, levelLine, x + 7, ly, GRAY);
            ly += 11;
            if (nextLine != null) {
                boolean afford = ClientState.MIRROR.money >= next.cost;
                ctx.drawTextWithShadow(tr, nextLine, x + 7, ly, afford ? GREEN : GRAY);
                ly += 11;
                ctx.drawTextWithShadow(tr, shopHint, x + 7, ly, CYAN);
                ly += 11;
            }
            if (alarmHint != null) {
                ctx.drawTextWithShadow(tr, alarmHint, x + 7, ly, 0xFFFF8A8A);
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
                int top = alertBottom;
                ctx.fill(cx - bw / 2, top, cx + bw / 2, top + bh, 0xC0200008);
                ctx.fill(cx - bw / 2, top, cx + bw / 2, top + 2, a | 0xFF4D5A);
                int ty = top + 5;
                for (OrderedText o : head) {
                    ctx.drawCenteredTextWithShadow(tr, o, cx, ty, 0xFFFF6B75);
                    ty += 10;
                }
                for (OrderedText o : hint) {
                    ctx.drawCenteredTextWithShadow(tr, o, cx, ty, 0xFFFFE4A8);
                    ty += 10;
                }
                alertBottom = top + bh + 4;
            }
        }

        // ---- always while the Monster Truck attacks: the showroom's health (a status, not a guide)
        if (sab == 5 || ClientState.alarm == 2) {
            int bw2 = Math.min(160, areaW);
            int by2 = guide ? alertBottom : Math.max(10, alertBottom);
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

    private static int lerpColor(int a, int b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
