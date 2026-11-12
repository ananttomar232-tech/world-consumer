package com.anant.teslatycoon.registry;

import com.anant.teslatycoon.TeslaTycoon;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** The voice clips of Seth Gotiya and the customers (one sound event per line of assets/tesla_tycoon/gotiya/lines.json). */
public final class ModSounds {
    private static final Map<String, SoundEvent> VOICE = new HashMap<>();

    private ModSounds() {}

    public static void register() {
        for (String id : com.anant.teslatycoon.rival.GotiyaLines.ids()) {
            Identifier key = TeslaTycoon.id("voice." + id);
            VOICE.put(id, Registry.register(Registries.SOUND_EVENT, key, SoundEvent.of(key)));
        }
    }

    public static SoundEvent voice(String lineId) {
        return VOICE.get(lineId);
    }
}
