package com.anant.teslatycoon.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;

/** A glowing neon panel. LIT is switched off by Gotiya's blackout and back on by the Solar Roof + MegaPack. */
public final class NeonBlock extends Block {
    public static final EnumProperty<NeonColor> COLOR = EnumProperty.of("color", NeonColor.class);
    public static final BooleanProperty LIT = Properties.LIT;

    public NeonBlock() {
        super(AbstractBlock.Settings.copy(Blocks.GLASS).luminance(s -> s.get(LIT) ? 15 : 0).strength(0.3f).sounds(BlockSoundGroup.GLASS)
                .nonOpaque());
        setDefaultState(getStateManager().getDefaultState().with(COLOR, NeonColor.CYAN).with(LIT, true));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(COLOR, LIT);
    }
}
