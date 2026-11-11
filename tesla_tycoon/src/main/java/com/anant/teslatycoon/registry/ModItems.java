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

    public static final java.util.Map<com.anant.teslatycoon.game.Upgrade, Item> UPGRADES = new java.util.EnumMap<>(com.anant.teslatycoon.game.Upgrade.class);
    public static final java.util.Map<VehicleSpec, Item> SPAWNERS = new java.util.HashMap<>();

    private ModItems() {}

    public static void register() {
        Registry.register(Registries.ITEM, TeslaTycoon.id("wall_poster"), WALL_POSTER);
        for (VehicleSpec s : VehicleSpecs.ALL) {
            Item sp = new VehicleSpawnerItem(s, new Item.Settings().maxCount(16));
            Registry.register(Registries.ITEM, TeslaTycoon.id(s.id + "_spawner"), sp);
            SPAWNERS.put(s, sp);
        }
        for (com.anant.teslatycoon.game.Upgrade u : com.anant.teslatycoon.game.Upgrade.values()) {
            Item it = new com.anant.teslatycoon.item.UpgradeItem(u, new Item.Settings().maxCount(1));
            Registry.register(Registries.ITEM, TeslaTycoon.id("upgrade_" + u.id), it);
            UPGRADES.put(u, it);
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
                    for (Item it : UPGRADES.values()) {
                        entries.add(it);
                    }
                })
                .build();
        Registry.register(Registries.ITEM_GROUP, TeslaTycoon.id("main"), group);
    }
}
