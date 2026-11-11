package com.anant.teslatycoon.client;

import com.anant.teslatycoon.game.Company;
import com.anant.teslatycoon.game.Upgrade;
import java.util.UUID;

/** The last company snapshot the server sent (HUD, shop). A throw-away {@link Company} mirror reuses the buy rules. */
public final class ClientState {
    public static boolean present;
    public static final Company MIRROR = new Company(new UUID(0, 0), "");
    public static float income;
    public static int sabotage;
    public static int climaxHp = 100;
    public static boolean powerOut;
    public static int empCooldown;
    public static boolean driving;
    public static boolean hudHidden;
    /** 0 = quiet, 1 = the alarm is on and Gotiya rams the gate, 2 = the Monster Truck attack. */
    public static int alarm;

    private ClientState() {}

    public static void apply(long money, float inc, int level, int mask, int billboards, int sab, int hp, boolean power, boolean city, int smashed, int emp) {
        present = city;
        MIRROR.money = money;
        MIRROR.level = level;
        MIRROR.owned.clear();
        for (Upgrade u : Upgrade.values()) {
            if ((mask & (1 << u.ordinal())) != 0) {
                MIRROR.owned.add(u);
            }
        }
        MIRROR.billboardsPlaced = billboards;
        MIRROR.ramSmashed = smashed;
        income = inc;
        sabotage = sab;
        climaxHp = hp;
        powerOut = power;
        empCooldown = emp;
    }

    /** The next story purchase (first not-owned entry whose requirements are met). */
    public static Upgrade next() {
        for (Upgrade u : Upgrade.values()) {
            if (u.kind == Upgrade.Kind.EXTRA || MIRROR.owns(u)) {
                continue;
            }
            String why = MIRROR.blockedReason(u);
            if (why == null || why.equals("not enough money")) {
                return u;
            }
        }
        return null;
    }
}
