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
    /** $/s of the showroom at each level: the brief's 5 / 35 / 150 / 800 (level 5 is the endgame). */
    public static final double[] BASE_INCOME = {0, 5.0, 35.0, 150.0, 800.0, 2500.0};
    public static final double SABOTAGE_FACTOR = 0.60;
    public static final double BILLBOARD_BONUS = 0.06;      // per placed billboard
    public static final double DRIVE_BONUS = 1.0;           // +100% while the owner drives a company car
    public static final double TIP_SECONDS = 13.0;          // a customer pays this many seconds of income at reception
    public static final double DELIVERY_SECONDS = 8.0;      // the delivery truck's stock bonus
    public static final double FARE_SECONDS = 0.7;          // one robotaxi ride
    public static final int BILLBOARDS_NEEDED = 3;
    public static final int MAX_BILLBOARDS = 6;

    public final UUID owner;
    public String ownerName;

    public long money;
    public double carry;
    public int level = 1;
    public final Set<Upgrade> owned = EnumSet.noneOf(Upgrade.class);
    /** Bought on a pedestal, still an item in the player's hand: not built / deployed yet. */
    public final Set<Upgrade> pending = EnumSet.noneOf(Upgrade.class);
    public double speed = 1.0;            // /money speed: multiplies the passive income
    public boolean hudHidden;             // /money toggle
    public int billboardsPlaced;
    public int billboardMask;              // bit i set: billboard i shows the ad
    public long totalEarned;
    public int customersServed;

    // ---- story
    public int sabTriggered;              // bit k set once sabotage k has started
    public boolean climaxWon;
    public long climaxHp = 100;
    public boolean powerOut;              // sabotage 2 is cutting the neon
    public boolean gateClosed;            // the finale's security gate (level 5) stands
    public boolean gateBroken;            // ... and the Monster Truck has smashed a hole in it
    public int deliveries;
    // ---- Seth Gotiya (v4)
    public int rivalBuilt;                // which version of his dealership stands (level * 10 + 1 destroyed / + 2 partner); 0 = old world
    public boolean rivalDestroyed;        // /destroy (or the EMP finale cinematic) flattened his dealership
    public boolean gotiyaPartner;         // he joined the business
    public int gotiyaTrust;               // kind words from the player (empathy -> partnership)
    public long gotiyaSales;              // his own sales, for his boasting

    // ---- city
    public boolean cityBuilt;
    public boolean cityComplete = true;   // false while the skyline around the district is still being built (resumed after a restart)
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
        if (gotiyaPartner || rivalDestroyed || (sabTriggered & (1 << level)) == 0) {
            return 0;
        }
        return switch (level) {
            case 1 -> owns(Upgrade.HYDRAULICS) ? 0 : 1;
            case 2 -> owns(Upgrade.SOLAR) ? 0 : 2;
            case 3 -> owns(Upgrade.RAM) && ramSmashed >= 2 ? 0 : 3;
            case 4 -> (owns(Upgrade.STARLINK) && owns(Upgrade.BILLBOARDS) && billboardsPlaced >= BILLBOARDS_NEEDED) ? 0 : 4;
            case 5 -> climaxWon ? 0 : 5;
            default -> 0;
        };
    }

    public int ramSmashed;

    /** True while the owner is driving a company car (set by {@code Tycoon.tick}; not saved). */
    public transient boolean driving;
    public transient long drivingUntil;
    public transient double lastDriveX = Double.NaN;
    public transient double lastDriveZ = Double.NaN;

    /** The showroom's own income, without the driving bonus: this is what tips, deliveries and fares are measured in. */
    public double passivePerSecond() {
        double r = BASE_INCOME[Math.max(1, Math.min(5, level))];
        r *= 1.0 + BILLBOARD_BONUS * billboardsPlaced;
        if (activeSabotage() != 0 && activeSabotage() != 5) {
            r *= SABOTAGE_FACTOR;
        }
        if (climaxWon) {
            r *= 1.25;
        }
        if (gotiyaPartner) {
            r *= 1.30;                    // Anant & Gotiya Motors: his customers are ours now
        }
        return r * speed;
    }

    /** What the HUD shows as +$/sec and what accrues every tick: the passive income, doubled while the owner drives. */
    public double incomePerSecond() {
        return passivePerSecond() * (driving ? 1.0 + DRIVE_BONUS : 1.0);
    }

    /** A bonus worth {@code seconds} of the showroom's passive income (rounded, at least $1). */
    public long worth(double seconds) {
        return Math.max(1L, Math.round(passivePerSecond() * seconds));
    }

    /** Why this cannot be bought now, or null if it can. */
    public String blockedReason(Upgrade u) {
        if (u.kind != Upgrade.Kind.EXTRA && owns(u)) {
            return "owned";
        }
        if (pending.contains(u)) {
            return "bought - place it";
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
        NbtList pend = new NbtList();
        for (Upgrade u : pending) {
            pend.add(NbtString.of(u.id));
        }
        n.put("Pending", pend);
        n.putDouble("Speed", speed);
        n.putBoolean("HudHidden", hudHidden);
        n.putInt("Billboards", billboardsPlaced);
        n.putInt("BbMask", billboardMask);
        n.putBoolean("Gate", gateClosed);
        n.putBoolean("GateBroken", gateBroken);
        n.putLong("Earned", totalEarned);
        n.putInt("Served", customersServed);
        n.putInt("Sab", sabTriggered);
        n.putBoolean("Won", climaxWon);
        n.putLong("Hp", climaxHp);
        n.putBoolean("PowerOut", powerOut);
        n.putInt("Deliveries", deliveries);
        n.putInt("Smashed", ramSmashed);
        n.putInt("RivalBuilt", rivalBuilt);
        n.putBoolean("RivalDestroyed", rivalDestroyed);
        n.putBoolean("GotiyaPartner", gotiyaPartner);
        n.putInt("GotiyaTrust", gotiyaTrust);
        n.putLong("GotiyaSales", gotiyaSales);
        n.putBoolean("City", cityBuilt);
        n.putBoolean("Complete", cityComplete);
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
        for (NbtElement e : n.getList("Pending", NbtElement.STRING_TYPE)) {
            Upgrade u = Upgrade.byId(e.asString());
            if (u != null) {
                c.pending.add(u);
            }
        }
        c.speed = n.contains("Speed") ? n.getDouble("Speed") : 1.0;
        c.hudHidden = n.getBoolean("HudHidden");
        c.billboardsPlaced = n.getInt("Billboards");
        c.billboardMask = n.contains("BbMask") ? n.getInt("BbMask") : (1 << c.billboardsPlaced) - 1;
        c.gateClosed = n.getBoolean("Gate");
        c.gateBroken = n.getBoolean("GateBroken");
        c.totalEarned = n.getLong("Earned");
        c.customersServed = n.getInt("Served");
        c.sabTriggered = n.getInt("Sab");
        c.climaxWon = n.getBoolean("Won");
        c.climaxHp = n.contains("Hp") ? n.getLong("Hp") : 100;
        c.powerOut = n.getBoolean("PowerOut");
        c.deliveries = n.getInt("Deliveries");
        c.ramSmashed = n.getInt("Smashed");
        c.rivalBuilt = n.getInt("RivalBuilt");
        c.rivalDestroyed = n.getBoolean("RivalDestroyed");
        c.gotiyaPartner = n.getBoolean("GotiyaPartner");
        c.gotiyaTrust = n.getInt("GotiyaTrust");
        c.gotiyaSales = n.getLong("GotiyaSales");
        c.cityBuilt = n.getBoolean("City");
        c.cityComplete = !n.contains("Complete") || n.getBoolean("Complete");
        c.origin = BlockPos.fromLong(n.getLong("Origin"));
        c.dimension = n.getString("Dim");
        c.cityTick = n.getLong("CityTick");
        return c;
    }
}
