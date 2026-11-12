package com.anant.teslatycoon;

import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TeslaTycoon implements ModInitializer {
    public static final String MOD_ID = "tesla_tycoon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        com.anant.teslatycoon.registry.ModBlocks.register();
        com.anant.teslatycoon.registry.ModEntities.register();
        com.anant.teslatycoon.registry.ModItems.register();
        com.anant.teslatycoon.registry.ModSounds.register();
        com.anant.teslatycoon.game.Tycoon.init();
        com.anant.teslatycoon.net.ModNetwork.registerServer();
        LOGGER.info("Tesla Tycoon ready");
    }
}
