package com.anant.teslatycoon.game;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.math.BlockPos;

/** One player's showroom business. Saved with the world (see {@link TycoonState}). */
public final class Company {
    public static final double[] BASE_INCOME = {0, 5.0, 10.0, 19.0, 34.0, 58.0};     // $/s per showroom level
    public static final double SABOTAGE_FACTOR = 0.60;
    public static final int BILLBOARDS_NEEDED = 3;
    public static final int MAX_BILLBOARDS = 6;

    public final UUID owner;
    public String ownerName;

    public long money;
    public double carry;
    public int level = 1;
    public final Set<Upgrade> owned = EnumSet.noneOf(Upgrade.class);
    public int billboardsPlaced;
    public long totalEarned;
    public int customersServed;

    // ---- story
    public int sabTriggered;              // bit k set once sabotage k has started
    public boolean climaxWon;
    public long climaxHp = 100;
    public boolean powerOut;              // sabotage 2 is cutting the neon
    public int deliveries;

    // ---- city
    public boolean cityBuilt;
    public BlockPos origin = BlockPos.ORIGIN;
    public String dimension = "minecraft:overworld";
    public long cityTick;

    public Company(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    public boolean owns(Upgrade u) {
        return owned.contains(u);
    }

    /** The sabotage (1..5) that is hurting the business right now, or 0. */
    public int activeSabotage() {
        if ((sabTriggered & (1 << level)) == 0) {
            return 0;
        }
        return switch (level) {
            case 1 -> owns(Upgrade.HYDRAULICS) ? 0 : 1;
            case 2 -> owns(Upgrade.SOLAR) ? 0 : 2;
            case 3 -> owns(Upgrade.RAM) && ramSmashed >= 2 ? 0 : 3;
            case 4 -> (owns(Upgrade.STARLINK) && owns(Upgrade.TERMINAL) && owns(Upgrade.BILLBOARDS) && billboardsPlaced >= BILLBOARDS_NEEDED) ? 0 : 4;
            case 5 -> climaxWon ? 0 : 5;
            default -> 0;
        };
    }

    public int ramSmashed;

    public double incomePerSecond() {
        double r = BASE_INCOME[Math.max(1, Math.min(5, level))];
        if (owns(Upgrade.SOLAR)) {
            r *= 1.20;
        }
        if (owns(Upgrade.STARLINK)) {
            r *= 1.10;
        }
        if (owns(Upgrade.TERMINAL)) {
            r *= 1.25;
        }
        r *= 1.0 + 0.06 * billboardsPlaced;
        if (activeSabotage() != 0 && activeSabotage() != 5) {
            r *= SABOTAGE_FACTOR;
        }
        if (climaxWon) {
            r *= 1.25;
        }
        return r;
    }

    /** Why this cannot be bought now, or null if it can. */
    public String blockedReason(Upgrade u) {
        if (u.kind != Upgrade.Kind.EXTRA && owns(u)) {
            return "owned";
        }
        if (level < u.minLevel) {
            return "needs showroom level " + u.minLevel;
        }
        for (Upgrade req : u.requires()) {
            if (!owns(req)) {
                return "buy " + req.id + " first";
            }
        }
        if (u == Upgrade.LEVEL5 && billboardsPlaced < BILLBOARDS_NEEDED) {
            return "place " + BILLBOARDS_NEEDED + " billboards first";
        }
        if (u == Upgrade.LEVEL4 && ramSmashed < 2) {
            return "smash 2 roadblocks with the Cybertruck first";
        }
        if (u.kind == Upgrade.Kind.LEVEL && u.target != level + 1) {
            return "wrong level";
        }
        if (money < u.cost) {
            return "not enough money";
        }
        return null;
    }

    public NbtCompound toNbt() {
        NbtCompound n = new NbtCompound();
        n.putUuid("Owner", owner);
        n.putString("Name", ownerName == null ? "" : ownerName);
        n.putLong("Money", money);
        n.putDouble("Carry", carry);
        n.putInt("Level", level);
        NbtList list = new NbtList();
        for (Upgrade u : owned) {
            list.add(NbtString.of(u.id));
        }
        n.put("Owned", list);
        n.putInt("Billboards", billboardsPlaced);
        n.putLong("Earned", totalEarned);
        n.putInt("Served", customersServed);
        n.putInt("Sab", sabTriggered);
        n.putBoolean("Won", climaxWon);
        n.putLong("Hp", climaxHp);
        n.putBoolean("PowerOut", powerOut);
        n.putInt("Deliveries", deliveries);
        n.putInt("Smashed", ramSmashed);
        n.putBoolean("City", cityBuilt);
        n.putLong("Origin", origin.asLong());
        n.putString("Dim", dimension);
        n.putLong("CityTick", cityTick);
        return n;
    }

    public static Company fromNbt(NbtCompound n) {
        Company c = new Company(n.getUuid("Owner"), n.getString("Name"));
        c.money = n.getLong("Money");
        c.carry = n.getDouble("Carry");
        c.level = Math.max(1, Math.min(5, n.getInt("Level")));
        for (NbtElement e : n.getList("Owned", NbtElement.STRING_TYPE)) {
            Upgrade u = Upgrade.byId(e.asString());
            if (u != null) {
                c.owned.add(u);
            }
        }
        c.billboardsPlaced = n.getInt("Billboards");
        c.totalEarned = n.getLong("Earned");
        c.customersServed = n.getInt("Served");
        c.sabTriggered = n.getInt("Sab");
        c.climaxWon = n.getBoolean("Won");
        c.climaxHp = n.contains("Hp") ? n.getLong("Hp") : 100;
        c.powerOut = n.getBoolean("PowerOut");
        c.deliveries = n.getInt("Deliveries");
        c.ramSmashed = n.getInt("Smashed");
        c.cityBuilt = n.getBoolean("City");
        c.origin = BlockPos.fromLong(n.getLong("Origin"));
        c.dimension = n.getString("Dim");
        c.cityTick = n.getLong("CityTick");
        return c;
    }
}
