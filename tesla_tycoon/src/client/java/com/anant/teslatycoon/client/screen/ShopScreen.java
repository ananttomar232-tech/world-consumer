package com.anant.teslatycoon.client.screen;

import com.anant.teslatycoon.client.ClientState;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.net.ModNetwork;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

/** The Tycoon Upgrade Shop: showroom levels, Gotiya counter-items, extras. */
public final class ShopScreen extends Screen {
    private final List<Card> cards = new ArrayList<>();

    public ShopScreen() {
        super(Text.translatable("screen.tesla_tycoon.shop"));
    }

    @Override
    protected void init() {
        cards.clear();
        Upgrade[] ups = Upgrade.values();
        int rows = (ups.length + 1) / 2;
        int cardW = Math.min(228, (width - 30) / 2);
        int gap = 4;
        int top = 52;
        int bottom = height - 32;                              // the Done button lives below
        // the cards share the height that is left, so all of them are on screen even in a 480x270 GUI (a 1080p window at GUI scale 4)
        int cardH = Math.max(14, Math.min(34, (bottom - top - gap * (rows - 1)) / rows));
        int x0 = (width - (cardW * 2 + gap)) / 2;
        for (int i = 0; i < ups.length; i++) {
            int col = i % 2;
            int row = i / 2;
            Card c = new Card(x0 + col * (cardW + gap), top + row * (cardH + gap), cardW, cardH, ups[i]);
            cards.add(c);
            addDrawableChild(c);
        }
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close()).dimensions(width / 2 - 50, height - 26, 100, 20).build());
    }

    public void refresh() {
        clearChildren();
        init();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        ctx.fillGradient(0, 0, width, height, 0xE0050A14, 0xE0101C30);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFF35E6FF);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(String.format("$%,d   (+$%.1f/s)", ClientState.MIRROR.money, ClientState.income)), width / 2, 28, 0xFFFFD34D);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.tesla_tycoon.shop_sub"), width / 2, 39, 0xFFB8C7DD);
        super.render(ctx, mouseX, mouseY, delta);
        for (Card c : cards) {
            if (c.isMouseOver(mouseX, mouseY)) {
                List<OrderedText> lines = textRenderer.wrapLines(Text.translatable(c.u.descKey()), 220);
                ctx.drawOrderedTooltip(textRenderer, lines, mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private final class Card extends ClickableWidget {
        final Upgrade u;

        Card(int x, int y, int w, int h, Upgrade u) {
            super(x, y, w, h, Text.empty());
            this.u = u;
        }

        private String why() {
            return ClientState.MIRROR.blockedReason(u);
        }

        @Override
        protected void renderButton(DrawContext ctx, int mx, int my, float delta) {
            String why = why();
            boolean owned = "owned".equals(why);
            boolean can = why == null;
            int bg = owned ? 0xC0243A24 : can ? (isHovered() ? 0xE0105A48 : 0xC00C3A34) : 0xC01A2030;
            int edge = owned ? 0xFF5CFF8A : can ? 0xFF35E6FF : 0xFF3A4660;
            ctx.fill(getX(), getY(), getX() + width, getY() + height, bg);
            ctx.fill(getX(), getY(), getX() + 2, getY() + height, edge);
            int nameColor = owned ? 0xFF9BE8A8 : can ? 0xFFFFFFFF : 0xFF8A97B2;
            String sub = owned ? "OWNED" : (can ? String.format("Buy for $%,d", u.cost) : String.format("$%,d  -  %s", u.cost, why));
            int subColor = owned ? 0xFF5CFF8A : can ? 0xFFFFD34D : 0xFFFF8A8A;
            if (height >= 20) {
                int ty = getY() + (height - 19) / 2;
                ctx.drawTextWithShadow(textRenderer, Text.translatable(u.nameKey()), getX() + 7, ty, nameColor);
                ctx.drawTextWithShadow(textRenderer, sub, getX() + 7, ty + 11, subColor);
            } else {                                           // very small GUI: one line, the state on the right
                int ty = getY() + (height - 8) / 2;
                ctx.drawTextWithShadow(textRenderer, Text.translatable(u.nameKey()), getX() + 7, ty, nameColor);
                String shortSub = owned ? "OWNED" : (can ? String.format("$%,d", u.cost) : "locked");
                ctx.drawTextWithShadow(textRenderer, shortSub, getX() + width - 6 - textRenderer.getWidth(shortSub), ty, subColor);
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            if (why() == null) {
                PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer(1));
                buf.writeByte(u.ordinal());
                ClientPlayNetworking.send(ModNetwork.BUY, buf);
            }
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }
}
