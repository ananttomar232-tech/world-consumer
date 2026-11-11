package com.anant.teslatycoon.game;

import java.util.List;

/**
 * Everything the Upgrade Depot sells. The level prices are the brief's (1,500 / 10,000 / 50,000 / 250,000); the counter-items
 * are priced by tools/pacing_sim.py so that an active player (driving, customers, deliveries, robotaxi fares) needs 2 to 3
 * minutes per showroom level, while an idle one needs about 4.
 */
public enum Upgrade {
    HYDRAULICS("hydraulics", 1_200, Kind.COUNTER, 1, 1),
    LEVEL2("level2", 1_500, Kind.LEVEL, 2, 1),
    SOLAR("solar", 8_000, Kind.COUNTER, 2, 2),
    LEVEL3("level3", 10_000, Kind.LEVEL, 3, 2),
    RAM("ram", 35_000, Kind.COUNTER, 3, 3),
    LEVEL4("level4", 50_000, Kind.LEVEL, 4, 3),
    STARLINK("starlink", 180_000, Kind.COUNTER, 4, 4),
    BILLBOARDS("billboards", 180_000, Kind.COUNTER, 4, 4),
    LEVEL5("level5", 250_000, Kind.LEVEL, 5, 4),
    POSTERS("posters", 500, Kind.EXTRA, 0, 1);

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
            case LEVEL5 -> List.of(STARLINK, BILLBOARDS);
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
