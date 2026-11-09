package com.anant.teslatycoon.rival;

import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Tycoon;
import com.anant.teslatycoon.npc.ShowroomDirector;
import com.anant.teslatycoon.vehicle.VehicleEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/** Connects the vehicle physics to the game rules: test drives, spikes, ramming, GPS jamming. */
public final class GameVehicleHook implements VehicleEntity.ServerHook {
    @Override
    public void tick(VehicleEntity vehicle) {
        RivalDirector.vehicleTick(vehicle);
    }

    @Override
    public void interactDisplay(ServerPlayerEntity player, VehicleEntity display) {
        Company c = Tycoon.companyOf(player);
        if (c != null && c.cityBuilt) {
            ShowroomDirector.testDrive(player, display, c);
        }
    }
}
