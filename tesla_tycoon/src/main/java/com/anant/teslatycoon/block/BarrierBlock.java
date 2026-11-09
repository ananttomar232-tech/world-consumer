package com.anant.teslatycoon.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.sound.BlockSoundGroup;

/** Seth Gotiya's concrete roadblock (sabotage 3). Hard to break by hand, but the Cybertruck in Beast Ram Mode smashes it. */
public final class BarrierBlock extends Block {
    public BarrierBlock() {
        super(AbstractBlock.Settings.copy(Blocks.STONE).strength(25.0f, 1200.0f).sounds(BlockSoundGroup.STONE));
    }
}
