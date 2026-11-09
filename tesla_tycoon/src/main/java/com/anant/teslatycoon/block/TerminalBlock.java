package com.anant.teslatycoon.block;

import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.game.Upgrade;
import com.anant.teslatycoon.net.ModNetwork;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** A glowing kiosk: the Upgrade Shop terminal (opens the shop) or the Robotaxi Terminal (shows the fleet status). */
public final class TerminalBlock extends HorizontalFacingBlock {
    public enum Kind { SHOP, ROBOTAXI }

    private final Kind kind;

    public TerminalBlock(Kind kind) {
        super(AbstractBlock.Settings.copy(Blocks.IRON_BLOCK).strength(3.0f, 1200.0f).sounds(BlockSoundGroup.METAL).luminance(s -> 12));
        this.kind = kind;
        setDefaultState(getStateManager().getDefaultState().with(FACING, net.minecraft.util.math.Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayerEntity sp)) {
            return ActionResult.PASS;
        }
        Company c = Tycoon.companyOf(sp);
        if (kind == Kind.SHOP) {
            if (c == null || !c.cityBuilt) {
                sp.sendMessage(Text.literal("Build your city first: /tycoon start").formatted(Formatting.RED), true);
            } else {
                ModNetwork.sendOpenShop(sp);
            }
        } else if (c != null) {
            boolean on = c.level >= 4;
            sp.sendMessage(Text.literal(on ? com.anant.teslatycoon.npc.RobotaxiDirector.status(c)
                    : "Robotaxi Terminal offline (reach showroom level 4 in the Upgrade Depot)")
                    .formatted(on ? Formatting.AQUA : Formatting.RED), true);
        }
        return ActionResult.CONSUME;
    }
}
