#!/usr/bin/env python3
"""Pacing simulator for the Tesla Tycoon economy: how long does each purchase take at the planned income?
usage: python3 tools/pacing_sim.py            (prints the table the in-game Upgrade enum is built from)"""
BASE = {1: 5.0, 2: 10.0, 3: 19.0, 4: 34.0, 5: 58.0}      # $/s of the showroom at each level
MULT = {"solar": 1.20, "starlink": 1.10, "terminal": 1.25}   # permanent income boosts
BILLBOARD = 0.06                                           # per placed billboard (3 are needed for the level-4 counter)
PENALTY = 0.60                                             # income factor while a Gotiya sabotage is unresolved
# (id, cost, sabotage that starts when bought / resolved-when)
PLAN = [
    ("hydraulics", 350),
    ("level2", 550),
    ("solar", 650),
    ("level3", 1250),
    ("ram", 1350),
    ("level4", 2500),
    ("starlink", 2350),
    ("terminal", 2700),
    ("billboards", 3200),
    ("level5", 7600),
]
SAB_FIRST_AT = 25.0

def simulate(plan, verbose=True):
    t = 0.0; money = 0.0; level = 1; owned = set(); billboards = 0
    sab = None; sab_started = False
    last = 0.0
    rows = []
    def rate():
        r = BASE[level]
        for k, m in MULT.items():
            if k in owned: r *= m
        r *= 1 + BILLBOARD * billboards
        if sab is not None: r *= PENALTY
        return r
    dt = 0.05
    i = 0
    while i < len(plan):
        pid, cost = plan[i]
        if not sab_started and t >= SAB_FIRST_AT:
            sab_started = True; sab = 1
        money += rate() * dt; t += dt
        if money >= cost:
            money -= cost; owned.add(pid)
            if pid == "hydraulics": sab = None
            if pid == "level2": level = 2; sab = 2
            if pid == "solar": sab = None
            if pid == "level3": level = 3; sab = 3
            if pid == "ram": sab = None
            if pid == "level4": level = 4; sab = 4
            if pid == "billboards": billboards = 3
            if {"starlink", "terminal", "billboards"} <= owned and sab == 4: sab = None
            if pid == "level5": level = 5
            rows.append((pid, cost, t - last, rate(), t))
            last = t
            i += 1
    if verbose:
        print("%-12s %7s %9s %9s %9s" % ("purchase", "cost", "wait s", "$/s now", "t total"))
        for r in rows:
            print("%-12s %7d %9.0f %9.1f %9.0f" % r)
    return rows

if __name__ == "__main__":
    rows = simulate(PLAN)
    waits = [r[2] for r in rows]
    print("\nwaits: min %.0f s  max %.0f s  total %.1f min" % (min(waits), max(waits), rows[-1][4] / 60))
