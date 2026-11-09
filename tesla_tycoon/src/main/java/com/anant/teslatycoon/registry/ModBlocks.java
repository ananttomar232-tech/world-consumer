package com.anant.teslatycoon.registry;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.block.BarrierBlock;
import com.anant.teslatycoon.block.BillboardPanelBlock;
import com.anant.teslatycoon.block.NeonBlock;
import com.anant.teslatycoon.block.PlainBlock;
import com.anant.teslatycoon.block.SpikesBlock;
import com.anant.teslatycoon.block.TerminalBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

public final class ModBlocks {
    public static final Block ASPHALT = new PlainBlock(Blocks.STONE, BlockSoundGroup.STONE, 1.5f, 0);
    public static final Block NEON = new NeonBlock();
    public static final Block SPIKES = new SpikesBlock();
    public static final Block BARRIER = new BarrierBlock();
    public static final Block BILLBOARD_PANEL = new BillboardPanelBlock();
    public static final Block SHOP_TERMINAL = new TerminalBlock(TerminalBlock.Kind.SHOP);
    public static final Block ROBOTAXI_TERMINAL = new TerminalBlock(TerminalBlock.Kind.ROBOTAXI);
    public static final Block DELIVERY_CRATE = new PlainBlock(Blocks.OAK_PLANKS, BlockSoundGroup.WOOD, 1.0f, 0);
    public static final Block MEGAPACK = new PlainBlock(Blocks.IRON_BLOCK, BlockSoundGroup.METAL, 3.0f, 0);
    public static final Block STARLINK_DISH = new PlainBlock(Blocks.IRON_BLOCK, BlockSoundGroup.METAL, 3.0f, 4);

    private ModBlocks() {}

    private static void reg(String id, Block b) {
        Registry.register(Registries.BLOCK, TeslaTycoon.id(id), b);
        Registry.register(Registries.ITEM, TeslaTycoon.id(id), new BlockItem(b, new Item.Settings()));
    }

    public static void register() {
        reg("asphalt", ASPHALT);
        reg("neon", NEON);
        reg("road_spikes", SPIKES);
        reg("concrete_barrier", BARRIER);
        reg("billboard_panel", BILLBOARD_PANEL);
        reg("shop_terminal", SHOP_TERMINAL);
        reg("robotaxi_terminal", ROBOTAXI_TERMINAL);
        reg("delivery_crate", DELIVERY_CRATE);
        reg("megapack", MEGAPACK);
        reg("starlink_dish", STARLINK_DISH);
    }
}
