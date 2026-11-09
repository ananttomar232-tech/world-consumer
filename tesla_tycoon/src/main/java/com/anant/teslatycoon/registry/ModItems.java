package com.anant.teslatycoon.registry;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.item.VehicleSpawnerItem;
import com.anant.teslatycoon.item.WallPosterItem;
import com.anant.teslatycoon.vehicle.VehicleSpec;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

public final class ModItems {
    public static final Item WALL_POSTER = new WallPosterItem(new Item.Settings().maxCount(16));

    private ModItems() {}

    public static void register() {
        Registry.register(Registries.ITEM, TeslaTycoon.id("wall_poster"), WALL_POSTER);
        for (VehicleSpec s : VehicleSpecs.ALL) {
            Registry.register(Registries.ITEM, TeslaTycoon.id(s.id + "_spawner"), new VehicleSpawnerItem(s, new Item.Settings().maxCount(16)));
        }
        ItemGroup group = FabricItemGroup.builder()
                .icon(() -> new ItemStack(WALL_POSTER))
                .displayName(Text.translatable("itemGroup.tesla_tycoon"))
                .entries((ctx, entries) -> {
                    entries.add(WALL_POSTER);
                    for (String id : new String[]{"asphalt", "neon", "road_spikes", "concrete_barrier", "billboard_panel", "shop_terminal",
                            "robotaxi_terminal", "delivery_crate", "megapack", "starlink_dish"}) {
                        entries.add(Registries.ITEM.get(TeslaTycoon.id(id)));
                    }
                    for (VehicleSpec s : VehicleSpecs.ALL) {
                        entries.add(Registries.ITEM.get(TeslaTycoon.id(s.id + "_spawner")));
                    }
                })
                .build();
        Registry.register(Registries.ITEM_GROUP, TeslaTycoon.id("main"), group);
    }
}
