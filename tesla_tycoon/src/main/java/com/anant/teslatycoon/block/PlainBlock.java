package com.anant.teslatycoon.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.sound.BlockSoundGroup;

/** Decorative city blocks that need no behaviour (asphalt, delivery crate, MegaPack, ...). */
public final class PlainBlock extends Block {
    public PlainBlock(Block copyFrom, BlockSoundGroup sounds, float hardness, int light) {
        super(AbstractBlock.Settings.copy(copyFrom).strength(hardness, 1200.0f).sounds(sounds).luminance(s -> light));
    }

    public static PlainBlock stone(float hardness) {
        return new PlainBlock(Blocks.STONE, BlockSoundGroup.STONE, hardness, 0);
    }
}
