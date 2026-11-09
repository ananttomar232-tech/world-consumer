package com.anant.teslatycoon.client.model;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import com.anant.teslatycoon.vehicle.VehicleSpec;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Lazy, cached access to the baked vehicles. */
public final class TtmModels {
    private static final Map<String, TtmModel> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    private TtmModels() {}

    public static TtmModel get(String name) {
        TtmModel m = CACHE.get(name);
        if (m != null || FAILED.contains(name)) {
            return m;
        }
        try {
            m = TtmModel.load(name);
            CACHE.put(name, m);
            return m;
        } catch (Throwable t) {
            FAILED.add(name);
            TeslaTycoon.LOGGER.error("Could not load vehicle model '{}'", name, t);
            return null;
        }
    }

    /** Loads every vehicle when a world is joined, so the first one that appears does not cause a hitch. */
    public static void preload() {
        long t0 = System.nanoTime();
        for (VehicleSpec s : VehicleSpecs.ALL) {
            get(s.model);
        }
        TeslaTycoon.LOGGER.info("vehicle models preloaded in {} ms", (System.nanoTime() - t0) / 1_000_000);
    }

    public static void clear() {
        for (TtmModel m : CACHE.values()) {
            m.close();
        }
        CACHE.clear();
        FAILED.clear();
    }
}
