package com.anant.teslatycoon.block;

import net.minecraft.util.StringIdentifiable;

public enum NeonColor implements StringIdentifiable {
    CYAN("cyan"), MAGENTA("magenta"), WHITE("white"), RED("red");

    private final String name;

    NeonColor(String name) {
        this.name = name;
    }

    @Override
    public String asString() {
        return name;
    }
}
