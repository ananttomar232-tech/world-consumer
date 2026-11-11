package com.anant.teslatycoon.sign;

import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.rival.RivalDirector;
import java.util.Comparator;
import java.util.List;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

/** Installing city_billboard.png on an empty frame: needs the Billboard Marketing Pack, adds +6% income each, permanently. */
public final class Billboards {
    private Billboards() {}

    /** What a right-click on a billboard back board did. */
    public enum Result { NOTHING_NEARBY, INSTALLED, ALREADY_LIVE, NEEDS_PACK, PACK_USED_UP }

    /** The game rule itself (no messages, so the game tests can run it without a player). */
    public static Result install(ServerWorld sw, Company c, BlockPos pos) {
        List<SignboardEntity> near = sw.getEntitiesByClass(SignboardEntity.class, new Box(pos).expand(16), s -> s.kind() == SignboardEntity.BILLBOARD);
        if (near.isEmpty()) {
            return Result.NOTHING_NEARBY;
        }
        SignboardEntity board = near.stream().min(Comparator.comparingDouble(s -> s.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5))).get();
        if (board.hasAd()) {
            return Result.ALREADY_LIVE;
        }
        if (!c.owns(Upgrade.BILLBOARDS)) {
            return Result.NEEDS_PACK;
        }
        if (c.billboardsPlaced >= Company.MAX_BILLBOARDS) {
            return Result.PACK_USED_UP;
        }
        board.setAd(true);
        int idx = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < com.anant.teslatycoon.city.Buildings.BILLBOARDS.length; i++) {
            double[] ctr = com.anant.teslatycoon.city.Buildings.billboardCentre(com.anant.teslatycoon.city.Buildings.BILLBOARDS[i][0],
                    com.anant.teslatycoon.city.Buildings.BILLBOARDS[i][1], com.anant.teslatycoon.city.Buildings.billboardFacing(i));
            double d = board.squaredDistanceTo(c.origin.getX() + ctr[0], c.origin.getY() + ctr[1], c.origin.getZ() + ctr[2]);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        c.billboardMask |= 1 << idx;
        c.billboardsPlaced = Integer.bitCount(c.billboardMask);
        sw.spawnParticles(ParticleTypes.END_ROD, board.getX(), board.getY(), board.getZ(), 60, board.signWidth() * 0.4, board.signHeight() * 0.4, 0.5, 0.05);
        sw.playSound(null, board.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.BLOCKS, 1.0f, 1.0f);
        return Result.INSTALLED;
    }

    public static void tryInstall(ServerPlayerEntity player, World world, BlockPos pos) {
        Company c = Tycoon.companyOf(player);
        if (c == null || !c.cityBuilt) {
            return;
        }
        ServerWorld sw = (ServerWorld) world;
        switch (install(sw, c, pos)) {
            case NOTHING_NEARBY -> { }
            case ALREADY_LIVE -> player.sendMessage(Text.literal("This billboard already shows the Anant Tesla ad.").formatted(Formatting.GRAY), true);
            case NEEDS_PACK -> player.sendMessage(Text.literal("Empty ad frame. Buy the Billboard Marketing Pack in the Upgrade Depot to fill it.").formatted(Formatting.RED), true);
            case PACK_USED_UP -> player.sendMessage(Text.literal("Your marketing pack is used up (" + Company.MAX_BILLBOARDS + " billboards).").formatted(Formatting.YELLOW), true);
            case INSTALLED -> {
                player.sendMessage(Text.literal("Billboard live! ").formatted(Formatting.AQUA, Formatting.BOLD)
                        .append(Text.literal("(" + c.billboardsPlaced + "/" + Company.BILLBOARDS_NEEDED + " needed to steal Gotiya's customers, +6% income each)").formatted(Formatting.WHITE)), true);
                RivalDirector.onBillboard(sw, c, player);
                Tycoon.sync(player, c);
            }
        }
    }
}
