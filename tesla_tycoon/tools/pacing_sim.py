#!/usr/bin/env python3
"""Pacing simulator for the Tesla Tycoon v2 economy: how long does each showroom level take?

The passive rates are the brief's: level 1 = $5/s, 2 = $35/s, 3 = $150/s, 4 = $800/s. Level prices: 1,500 / 10,000 / 50,000 / 250,000.
A level is only reached by actively playing, so the money comes from the passive rate PLUS the things the player (and the showroom
staff) do:  driving a company car doubles the rate, every customer tips, every delivery truck pays, every robotaxi ride pays.

usage: python3 tools/pacing_sim.py            (prints the table the in-game Upgrade enum is built from; the numbers must match
                                               Company.java / Upgrade.java / ShowroomDirector.java)
"""
BASE = {1: 5.0, 2: 35.0, 3: 150.0, 4: 800.0, 5: 2500.0}     # $/s of the showroom at each level
BILLBOARD = 0.06                                            # +6 % per placed billboard
PENALTY = 0.60                                              # income factor while a Gotiya sabotage is unresolved
DRIVE_BONUS = 1.0                                           # +100 % while the owner drives a company car
TIP_SECONDS = 15.0                                          # a customer pays this many seconds of income at reception
DELIVERY_SECONDS = 8.0                                      # the delivery truck's stock bonus
FARE_SECONDS = 0.7                                          # one robotaxi ride
CUSTOMER_EVERY = {1: 37.5, 2: 33.0, 3: 28.5, 4: 24.0, 5: 19.5}   # s between customers: (780-90*level)/20 + 3 s of randomness
DELIVERY_EVERY = 95.0
RIDES_PER_MIN = 12.8                                        # level 4+: 4 Cybercabs together (stations every ~15 s per cab)

# (id, cost): counters answer the sabotage of the level they are bought in, level purchases move to the next level
PLAN = [
    ("hydraulics", 250),
    ("level2", 1500),
    ("solar", 1500),
    ("level3", 10000),
    ("ram", 5000),
    ("level4", 50000),
    ("starlink", 25000),
    ("billboards", 15000),
    ("level5", 250000),
]
SAB_FIRST_AT = 25.0
SAB_DELAY = 7.0
EXTRA_TIME = {"ram": 14.0, "billboards": 0.0}               # seconds the player is busy but still earns (smashing roadblocks, driving to billboards)


def profile(name):
    """How much of the time the player drives, and whether the automatic income (customers / trucks / robotaxis) runs."""
    return {"active": dict(drive=0.65, auto=1.0), "idle": dict(drive=0.0, auto=1.0), "afk": dict(drive=0.0, auto=0.0)}[name]


def simulate(plan, prof, verbose=True):
    t = 0.0
    money = 0.0
    level = 1
    owned = set()
    billboards = 0
    sab = None
    sab_at = SAB_FIRST_AT
    last = 0.0
    rows = []
    levels = []
    lvl_start = 0.0

    def countered():
        return {1: "hydraulics" in owned, 2: "solar" in owned, 3: "ram" in owned,
                4: "starlink" in owned and "billboards" in owned}.get(level, True)

    def passive():
        r = BASE[level] * (1 + BILLBOARD * billboards)
        if sab is not None and sab != 5 and not countered():
            r *= PENALTY
        return r

    def rate():
        return passive() * (1 + DRIVE_BONUS * prof["drive"])

    dt = 0.05
    i = 0
    next_customer = 10.0
    next_delivery = 35.0
    while i < len(plan):
        pid, cost = plan[i]
        if sab_at is not None and t >= sab_at:
            sab = level
            sab_at = None
        money += rate() * dt
        t += dt
        if prof["auto"]:
            if t >= next_customer:
                money += passive() * TIP_SECONDS
                next_customer = t + CUSTOMER_EVERY[level]
            if t >= next_delivery:
                money += passive() * DELIVERY_SECONDS
                next_delivery = t + DELIVERY_EVERY
            if level >= 4:
                money += passive() * FARE_SECONDS * RIDES_PER_MIN / 60.0 * dt
        if money >= cost:
            money -= cost
            owned.add(pid)
            if pid == "hydraulics": sab = None
            if pid == "solar": sab = None
            if pid == "ram": sab = None
            if pid == "billboards": billboards = 3
            if pid == "starlink" or pid == "billboards":
                if {"starlink", "billboards"} <= owned and sab == 4:
                    sab = None
            if pid.startswith("level"):
                levels.append((level, t - lvl_start))
                lvl_start = t
                level = int(pid[-1])
                sab = None
                sab_at = t + SAB_DELAY
            rows.append((pid, cost, t - last, rate(), t))
            last = t
            i += 1
    levels.append((level, 0.0))
    if verbose:
        print("%-12s %8s %9s %9s %9s" % ("purchase", "cost", "wait s", "$/s now", "t total"))
        for r in rows:
            print("%-12s %8d %9.0f %9.1f %9.0f" % r)
    return rows, levels


if __name__ == "__main__":
    for name in ("active", "idle", "afk"):
        print("\n=== %s player ===" % name)
        rows, levels = simulate(PLAN, profile(name))
        print("time per showroom level: " + "  ".join("L%d %.1f min" % (lv, s / 60.0) for lv, s in levels[:-1]))
        print("total %.1f min" % (rows[-1][4] / 60.0))
