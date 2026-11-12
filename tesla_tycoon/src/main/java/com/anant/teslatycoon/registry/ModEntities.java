package com.anant.teslatycoon.registry;

import com.anant.teslatycoon.TeslaTycoon;
import com.anant.teslatycoon.npc.SuitedNpcEntity;
import com.anant.teslatycoon.sign.PosterEntity;
import com.anant.teslatycoon.sign.SignboardEntity;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import com.anant.teslatycoon.vehicle.VehicleSpec;
import com.anant.teslatycoon.vehicle.VehicleSpecs;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModEntities {
    public static final Map<VehicleSpec, EntityType<VehicleEntity>> VEHICLES = new HashMap<>();
    public static final EntityType<SignboardEntity> SIGNBOARD = FabricEntityTypeBuilder.<SignboardEntity>create(SpawnGroup.MISC, SignboardEntity::new)
            .dimensions(EntityDimensions.fixed(0.5f, 0.5f)).trackRangeBlocks(224).trackedUpdateRate(40).build();
    public static final EntityType<PosterEntity> POSTER = FabricEntityTypeBuilder.<PosterEntity>create(SpawnGroup.MISC, PosterEntity::new)
            .dimensions(EntityDimensions.fixed(0.5f, 0.5f)).trackRangeBlocks(10).trackedUpdateRate(Integer.MAX_VALUE).forceTrackedVelocityUpdates(false).build();
    public static final EntityType<SuitedNpcEntity> SUITED = FabricEntityTypeBuilder.<SuitedNpcEntity>create(SpawnGroup.MISC, SuitedNpcEntity::new)
            .dimensions(EntityDimensions.fixed(0.6f, 1.95f)).trackRangeBlocks(80).trackedUpdateRate(2).build();

    public static final EntityType<com.anant.teslatycoon.shop.PedestalEntity> PEDESTAL = FabricEntityTypeBuilder.<com.anant.teslatycoon.shop.PedestalEntity>create(SpawnGroup.MISC, com.anant.teslatycoon.shop.PedestalEntity::new)
            .dimensions(EntityDimensions.fixed(1.1f, 2.0f)).trackRangeBlocks(48).trackedUpdateRate(40).build();

    public static final EntityType<com.anant.teslatycoon.cinema.CameraEntity> CAMERA = FabricEntityTypeBuilder.<com.anant.teslatycoon.cinema.CameraEntity>create(SpawnGroup.MISC, com.anant.teslatycoon.cinema.CameraEntity::new)
            .dimensions(EntityDimensions.fixed(0.2f, 0.2f)).trackRangeBlocks(256).trackedUpdateRate(1).forceTrackedVelocityUpdates(false).build();

    private ModEntities() {}

    public static EntityType<VehicleEntity> vehicle(VehicleSpec spec) {
        return VEHICLES.get(spec);
    }

    public static void register() {
        Registry.register(Registries.ENTITY_TYPE, TeslaTycoon.id("signboard"), SIGNBOARD);
        Registry.register(Registries.ENTITY_TYPE, TeslaTycoon.id("wall_poster"), POSTER);
        Registry.register(Registries.ENTITY_TYPE, TeslaTycoon.id("suited_villager"), SUITED);
        Registry.register(Registries.ENTITY_TYPE, TeslaTycoon.id("pedestal"), PEDESTAL);
        Registry.register(Registries.ENTITY_TYPE, TeslaTycoon.id("cinema_camera"), CAMERA);
        FabricDefaultAttributeRegistry.register(SUITED, SuitedNpcEntity.attributes());
        for (VehicleSpec spec : VehicleSpecs.ALL) {
            EntityType<VehicleEntity> type = FabricEntityTypeBuilder.<VehicleEntity>create(SpawnGroup.MISC, VehicleEntity::new)
                    .dimensions(EntityDimensions.fixed(spec.hitW, spec.hitH))
                    .trackRangeBlocks(160)
                    .trackedUpdateRate(1)
                    .forceTrackedVelocityUpdates(false)
                    .build();
            Registry.register(Registries.ENTITY_TYPE, TeslaTycoon.id(spec.id), type);
            VehicleSpecs.bind(type, spec);
            VEHICLES.put(spec, type);
        }
    }
}
