package com.anant.teslatycoon.game;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

/** Persistent store of every player's {@link Company}, kept in the overworld's data folder. */
public final class TycoonState extends PersistentState {
    private final Map<UUID, Company> companies = new HashMap<>();

    public static TycoonState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TycoonState::fromNbt, TycoonState::new, "tesla_tycoon");
    }

    public Company company(UUID id, String name) {
        Company c = companies.get(id);
        if (c == null) {
            c = new Company(id, name);
            companies.put(id, c);
            markDirty();
        } else if (name != null && !name.equals(c.ownerName)) {
            c.ownerName = name;
        }
        return c;
    }

    public Company find(UUID id) {
        return companies.get(id);
    }

    public Collection<Company> all() {
        return companies.values();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Company c : companies.values()) {
            list.add(c.toNbt());
        }
        nbt.put("Companies", list);
        return nbt;
    }

    public static TycoonState fromNbt(NbtCompound nbt) {
        TycoonState s = new TycoonState();
        for (NbtElement e : nbt.getList("Companies", NbtElement.COMPOUND_TYPE)) {
            Company c = Company.fromNbt((NbtCompound) e);
            s.companies.put(c.owner, c);
        }
        return s;
    }
}
