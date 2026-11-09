package com.anant.teslatycoon.game;

import java.util.List;

/**
 * Everything the Upgrade Shop sells. Costs come from tools/pacing_sim.py: with the planned income every purchase takes
 * 95-115 seconds, so a new upgrade or counter-item is affordable every 1.5 to 2 minutes without grinding.
 */
public enum Upgrade {
    HYDRAULICS("hydraulics", 350, Kind.COUNTER, 1, 1),
    LEVEL2("level2", 550, Kind.LEVEL, 2, 1),
    SOLAR("solar", 650, Kind.COUNTER, 2, 2),
    LEVEL3("level3", 1250, Kind.LEVEL, 3, 2),
    RAM("ram", 1350, Kind.COUNTER, 3, 3),
    LEVEL4("level4", 2500, Kind.LEVEL, 4, 3),
    STARLINK("starlink", 2350, Kind.COUNTER, 4, 4),
    TERMINAL("terminal", 2700, Kind.COUNTER, 4, 4),
    BILLBOARDS("billboards", 3200, Kind.COUNTER, 4, 4),
    LEVEL5("level5", 7600, Kind.LEVEL, 5, 4),
    POSTERS("posters", 150, Kind.EXTRA, 0, 1);

    public enum Kind { LEVEL, COUNTER, EXTRA }

    public final String id;
    public final long cost;
    public final Kind kind;
    /** LEVEL: the showroom level it unlocks. COUNTER: the sabotage it answers. */
    public final int target;
    /** Showroom level needed before it shows up as buyable. */
    public final int minLevel;

    Upgrade(String id, long cost, Kind kind, int target, int minLevel) {
        this.id = id;
        this.cost = cost;
        this.kind = kind;
        this.target = target;
        this.minLevel = minLevel;
    }

    public String nameKey() {
        return "upgrade.tesla_tycoon." + id;
    }

    public String descKey() {
        return "upgrade.tesla_tycoon." + id + ".desc";
    }

    /** What has to be owned first (story order: counter -> next level). */
    public List<Upgrade> requires() {
        return switch (this) {
            case LEVEL2 -> List.of(HYDRAULICS);
            case LEVEL3 -> List.of(SOLAR);
            case LEVEL4 -> List.of(RAM);
            case LEVEL5 -> List.of(STARLINK, TERMINAL, BILLBOARDS);
            default -> List.of();
        };
    }

    public static Upgrade byId(String id) {
        for (Upgrade u : values()) {
            if (u.id.equals(id)) {
                return u;
            }
        }
        return null;
    }

    public static Upgrade byOrdinal(int i) {
        Upgrade[] v = values();
        return i >= 0 && i < v.length ? v[i] : null;
    }
}
