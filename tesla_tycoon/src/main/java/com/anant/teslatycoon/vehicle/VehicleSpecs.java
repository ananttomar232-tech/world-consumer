package com.anant.teslatycoon.vehicle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.EntityType;

/** The registry of the six vehicles. Level n of the showroom unlocks LEVELS.get(n - 1). */
public final class VehicleSpecs {
    //                                             id                      model            lang key           top     fly    accel   brake   yaw   fly    heavy  step
    public static final VehicleSpec CLASSIC    = new VehicleSpec("tesla_classic",     "l1_classic",    "tesla_classic",   0.98, 0.0, 0.031, 0.034, 5.1f, false, false, 1.2f);
    public static final VehicleSpec MODEL3     = new VehicleSpec("tesla_model3",      "l2_model3",     "tesla_model3",    1.18, 0.0, 0.037, 0.036, 4.9f, false, false, 1.2f);
    public static final VehicleSpec CYBERTRUCK = new VehicleSpec("tesla_cybertruck",  "l3_cybertruck", "tesla_cybertruck",1.08, 0.0, 0.031, 0.040, 4.3f, false, true,  1.2f);
    public static final VehicleSpec CYBERCAB   = new VehicleSpec("tesla_cybercab",    "l4_cybercab",   "tesla_cybercab",  1.28, 0.0, 0.041, 0.038, 4.7f, false, false, 1.2f);
    public static final VehicleSpec ROADSTER   = new VehicleSpec("tesla_roadster",    "l5_roadster",   "tesla_roadster",  1.45, 1.95, 0.050, 0.046, 5.3f, true,  false, 1.2f);
    public static final VehicleSpec GOTIYA     = new VehicleSpec("gotiya_monster_truck", "rival_truck", "gotiya_truck",    0.92, 0.0, 0.030, 0.040, 3.4f, false, true,  2.4f);

    // Seth Gotiya's petrol and diesel cars (his showroom, also drivable)
    public static final VehicleSpec HATCH      = new VehicleSpec("gotiya_hatch",      "p1_hatch",      "gotiya_hatch",    1.02, 0.0, 0.033, 0.034, 5.0f, false, false, 1.2f);
    public static final VehicleSpec MUSTANG    = new VehicleSpec("gotiya_mustang",    "p2_mustang",    "gotiya_mustang",  1.30, 0.0, 0.044, 0.038, 4.8f, false, false, 1.2f);
    public static final VehicleSpec LAMBO      = new VehicleSpec("gotiya_lambo",      "p3_lambo",      "gotiya_lambo",    1.42, 0.0, 0.050, 0.044, 5.0f, false, false, 1.2f);

    public static final VehicleSpec TRUCK      = new VehicleSpec("delivery_truck",    "transport_truck","delivery_truck", 0.72, 0.0, 0.026, 0.04, 3.3f, false, true,  1.2f);

    /** Showroom level 1..5 -> the model that level unlocks. */
    public static final List<VehicleSpec> LEVELS = List.of(CLASSIC, MODEL3, CYBERTRUCK, CYBERCAB, ROADSTER);
    /** Gotiya's dealership: his petrol / diesel exhibits. */
    public static final List<VehicleSpec> PETROL = List.of(HATCH, MUSTANG, LAMBO);
    public static final List<VehicleSpec> ALL = List.of(CLASSIC, MODEL3, CYBERTRUCK, CYBERCAB, ROADSTER, GOTIYA, TRUCK, HATCH, MUSTANG, LAMBO);

    private static final Map<EntityType<?>, VehicleSpec> BY_TYPE = new HashMap<>();

    private VehicleSpecs() {}

    public static void bind(EntityType<?> type, VehicleSpec spec) {
        BY_TYPE.put(type, spec);
    }

    public static VehicleSpec of(EntityType<?> type) {
        VehicleSpec s = BY_TYPE.get(type);
        if (s == null) {
            throw new IllegalStateException("not a vehicle type: " + type);
        }
        return s;
    }
}
