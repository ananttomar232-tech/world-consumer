#!/usr/bin/env python3
"""Headless CLIENT smoke test: boots a flat world with the dedicated server once, then launches the real Fabric client under
Xvfb + Mesa software OpenGL, joins the world and lets a data pack run a scripted tour. A screenshot is taken at every step.

    python3 tools/smoke_client.py <tour name> [--out DIR] [--size 960x540]

Tours live in TOURS below (list of steps: name, commands run on the server, seconds to wait before the screenshot).
Needs: JDK 17+, Gradle (or ./gradlew), Xvfb, ImageMagick `import`, Mesa software GL (libgl1-mesa-dri). Exit code 2 = prerequisites missing.
"""
import argparse
import os
import shutil
import signal
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN = os.path.join(ROOT, "run")
SAVE = "ttsmoke"
GRADLE = os.environ.get("GRADLE", "/opt/gradle/bin/gradle" if os.path.exists("/opt/gradle/bin/gradle") else os.path.join(ROOT, "gradlew"))

VEHICLES = ["tesla_classic", "tesla_model3", "tesla_cybertruck", "tesla_cybercab", "tesla_roadster", "gotiya_monster_truck"]
KILL_ALL = ["kill @e[type=tesla_tycoon:%s]" % v for v in VEHICLES] + ["kill @e[type=minecraft:villager]"]
BASE = ["time set noon", "gamerule doDaylightCycle false", "gamerule doMobSpawning false", "weather clear", "gamemode creative @p",
        "effect give @p minecraft:night_vision infinite 0 true"]


def cam(x, y, z, tx=0.0, ty=-59.0, tz=0.0):
    return "tp @p %s %s %s facing %s %s %s" % (x, y, z, tx, ty, tz)


def tour_vehicles():
    steps = [("00_setup", BASE + KILL_ALL, 3)]
    for v in VEHICLES:
        k = 1.7 if v == "gotiya_monster_truck" else 1.0
        ty = -57.5 if v == "gotiya_monster_truck" else -59.0
        spawn = ["summon tesla_tycoon:%s 0 -60 0 {Rotation:[0.0f,0.0f]}" % v]
        steps.append(("%s_front" % v, KILL_ALL + spawn + [cam(round(6.5 * k, 1), -58.4 + (0.9 * k if k > 1 else 0), round(8.5 * k, 1), 0, ty, 0)], 7))
        steps.append(("%s_rear" % v, [cam(round(-6.5 * k, 1), -58.4 + (0.9 * k if k > 1 else 0), round(-8.5 * k, 1), 0, ty, 0)], 5))
        steps.append(("%s_side" % v, [cam(round(11 * k, 1), -59.2 + (0.8 * k if k > 1 else 0), 0.5, 0, ty, 0)], 5))
    return steps


def tour_seat():
    """Third person, FRONT view (-Dtesla_tycoon.thirdperson=front, short chase distance): the seated player seen through the windscreen."""
    steps = [("00_setup", BASE + KILL_ALL, 3)]
    for v in ["tesla_classic", "tesla_model3", "tesla_cybertruck", "tesla_cybercab", "tesla_roadster"]:
        spawn = ["summon tesla_tycoon:%s 0 -60 0 {Rotation:[0.0f,0.0f]}" % v]
        steps.append(("%s_seat_outside" % v, KILL_ALL + spawn + ["tp @p 0 -60 6 0 8", "ride @p mount @e[type=tesla_tycoon:%s,limit=1]" % v], 8))
    return steps


def tour_city():
    """Build the city (origin 0,-61,0 in the flat test world) and photograph every part of it."""
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
             ("01_start", ["execute as @p run tycoon start"], 45)]
    def at(name, x, y, z, tx, ty, tz, wait=6):
        steps.append((name, [cam(x, y, z, tx, ty, tz)], wait))
    at("02_showroom_front", -45, -59.5, 16, -44, -52, -6, 9)
    at("03_aerial_showroom", -8, -18, 46, -40, -52, -12)
    at("04_banner_closeup", -44, -50, 12, -43.5, -45, -6, 5)
    at("05_lobby", -45, -59.5, -7, -44, -57.5, -22, 6)
    at("06_pad_floor1", -52, -58.5, -9, -44, -58.0, -20, 5)
    at("07_reception", -49, -59.4, -7, -54, -58.4, -13.5, 5)
    at("08_bay", -33, -59.4, -9, -33, -58.4, -27, 5)
    at("09_shop_front", -14, -59.5, 8, -14, -56, -6, 6)
    at("10_shop_inside", -14, -59.4, -7, -14, -58.0, -17, 5)
    at("11_gotiya", -40, -59.5, 13, -40, -57, 24, 6)
    at("12_billboard_empty", 8, -55, 1, 8, -52, 15, 6)
    at("13_street_lamps", -60, -58, 2, -20, -57, 7, 5)
    at("14_alley", -46, -59, -47, -47, -58, -38, 5)
    at("15_aerial_city", 5, 70, 80, -10, -55, 5, 7)
    return steps


def tour_story():
    """The whole game: sabotage 1..5, every counter, the finale with a real flight and the EMP, then #retryscene."""
    P = "execute as @p run "
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
             ("01_start", [P + "tycoon start"], 50),
             ("02_spikes", [P + "tycoon money 30000", cam(-17, -59.0, 5.5, -10.5, -60.6, 10.5)], 7),
             ("03_buy_hydraulics", [P + "tycoon buy hydraulics"], 7),
             ("04_buy_level2", [P + "tycoon buy level2", cam(-45, -58.5, 24, -44, -52, -6)], 24),
             ("05_two_floors", [cam(-45, -57, 26, -44, -50, -6)], 7),
             ("06_floor2_inside", [P + "tp @s -44 -52.99 -9 180 8"], 6),
             ("07_blackout", [cam(-45, -59, 16, -44, -55, -6)], 9),
             ("08_buy_solar", [P + "tycoon buy solar", cam(-72, -58.5, -14, -65, -59, -27)], 14),
             ("09_solar_lit", [cam(-45, -59, 16, -44, -55, -6)], 6),
             ("10_buy_level3", [P + "tycoon buy level3", cam(-14, -59.0, 10.5, -3.0, -60.0, 10.5)], 24),
             ("11_roadblocks", [cam(-12, -58.5, 10.5, -3.0, -60.0, 10.5)], 7),
             ("12_buy_ram", [P + "tycoon buy ram", P + "tp @s -22 -61 10.5 -90 0", P + "tycoon car tesla_cybertruck"], 5),
             ("13_ram_go", [P + "tycoon drive 1 0 r"], 4),
             ("14_ram_smash", [], 4),
             ("15_ram_after", [P + "tycoon drive off"], 5),
             ("16_level5", [P + "tycoon money 90000", P + "tycoon level 5", P + "tp @s -45.5 -60 -2.5 180 5"], 40),
             ("17_tower", [cam(-45, -50, 30, -44, -38, -6)], 8),
             ("18_climax_start", [P + "tycoon sabotage"], 7),
             ("19_launch", [P + "tycoon drive 0.5 0 j"], 2.5),
             ("20_flight", [P + "tycoon drive 0 0"], 3),
             ("21_flight_more", [], 3),
             ("22_emp", [P + "tycoon emp"], 5),
             ("23_victory", [], 8),
             ("24_retry", [P + "tycoon drive off", P + "tycoon retry"], 40),
             ("25_retry_launchpad", [], 8)]
    return steps


def tour_finale():
    """Level 5 straight away: the Monster Truck charges and smashes the glass front, a real client-side flight with the thrusters,
    the EMP, the victory, landing, and #retryscene."""
    P = "execute as @p run "
    return [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
            ("01_start", [P + "tycoon start"], 50),
            ("02_level5", [P + "tycoon money 90000", P + "tycoon level 5", P + "tp @s -45.5 -60 -2.5 180 5"], 45),
            ("03_tower", [cam(-45, -50, 30, -44, -38, -6)], 7),
            ("04_climax_start", [P + "tycoon sabotage"], 6),
            ("05_truck_charge", [], 3),
            ("06_impact", [], 4),
            ("07_ignite", [P + "tycoon drive 0.5 0 j"], 2.5),
            ("08_hover", [P + "tycoon drive 0 0"], 3),
            ("09_emp", [P + "tycoon emp"], 3),
            ("10_victory", [], 8),
            ("11_descend", [P + "tycoon drive 0.3 0 d"], 6),
            ("12_landed", [P + "tycoon drive off"], 5),
            ("13_retry", [P + "tycoon retry"], 40),
            ("14_retry_charge", [], 8)]


def tour_truck():
    """Debug tour: the finale starts, and the Monster Truck's position is printed every two seconds."""
    P = "execute as @p run "
    # (function sources are silent, so `data get` prints nothing: tellraw with an nbt component does)
    probe = ['tellraw @a [{"text":"TRUCK_A "},{"nbt":"Pos","entity":"@e[type=tesla_tycoon:gotiya_monster_truck,sort=nearest,limit=1]"}]',
             'tellraw @a [{"text":"TRUCK_B "},{"nbt":"Pos","entity":"@e[type=tesla_tycoon:gotiya_monster_truck,sort=furthest,limit=1]"}]']
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
             ("01_start", [P + "tycoon start"], 50),
             ("02_level5", [P + "tycoon money 90000", P + "tycoon level 5", P + "tp @s -45.5 -60 -2.5 180 5"], 45),
             ("03_climax", [P + "tycoon sabotage"] + probe, 2)]
    for i in range(4, 22):
        steps.append(("%02d_probe" % i, probe, 2))
    return steps


def tour_shop():
    """The Tycoon Upgrade Shop screen: locked, affordable and owned cards (the screen opens through the /tycoon shop command)."""
    P = "execute as @p run "
    return [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
            ("01_start", [P + "tycoon start"], 50),
            ("02_shop_poor", [P + "tycoon shop"], 6),
            ("03_shop_mixed", [P + "tycoon money 3000", P + "tycoon buy hydraulics", P + "tycoon buy level2", P + "tycoon shop"], 8),
            ("04_shop_rich", [P + "tycoon money 60000", P + "tycoon buy solar", P + "tycoon buy level3", P + "tycoon shop"], 8)]


def tour_delivery():
    """Debug tour: where is the back-alley delivery truck? (printed every three seconds) plus a few pictures of the dock."""
    P = "execute as @p run "
    probe = ['tellraw @a [{"text":"DELIV "},{"nbt":"Pos","entity":"@e[type=tesla_tycoon:delivery_truck,limit=1]"}]']
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
             ("01_start", [P + "tycoon start"], 40),
             ("02_dock_cam", [cam(-47, -59.0, -30, -47, -58.0, -41)] + probe, 4)]
    for i in range(3, 24):
        steps.append(("%02d_deliv" % i, probe, 3))
    return steps


def tour_guide():
    """The guides (Right Shift): shown here from the start through -Dtesla_tycoon.guide=true; the other tours show the clean HUD."""
    P = "execute as @p run "
    return [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
            ("01_start", [P + "tycoon start"], 38),
            ("02_alert", [cam(-17, -59.0, 5.5, -10.5, -60.6, 10.5)], 6),
            ("03_level5", [P + "tycoon money 90000", P + "tycoon level 5", P + "tp @s -45.5 -60 -2.5 180 5"], 45),
            ("04_finale_guide", [P + "tycoon sabotage"], 8)]


def tour_occupants():
    """A suited customer sits in every car (close-ups through the left window and from above: nothing may stick out of the car), then the
    player takes the wheel and the driver's own first-person view is photographed."""
    cars = ["tesla_classic", "tesla_model3", "tesla_cybertruck", "tesla_cybercab", "tesla_roadster"]
    kill = KILL_ALL + ["kill @e[type=tesla_tycoon:suited_villager]"]
    steps = [("00_setup", BASE + kill + ["tp @p 0 -60 8 180 0"], 3)]
    for v in cars:
        spawn = ["summon tesla_tycoon:%s 0 -60 0 {Rotation:[0.0f,0.0f]}" % v,
                 "summon tesla_tycoon:suited_villager 0 -60 8 {Tags:[\"occ\"]}",
                 "ride @e[type=tesla_tycoon:suited_villager,tag=occ,limit=1] mount @e[type=tesla_tycoon:%s,limit=1]" % v]
        steps.append(("%s_window" % v, kill + spawn + [cam(3.3, -59.1, 0.35, 0, -59.5, 0.1)], 8))
        steps.append(("%s_above" % v, [cam(2.6, -57.4, 3.4, 0, -59.4, 0.0)], 4))
        steps.append(("%s_rear" % v, [cam(-1.9, -58.3, -4.8, 0, -59.4, 0.0)], 4))
        steps.append(("%s_first" % v, ["kill @e[type=tesla_tycoon:suited_villager]", "ride @p mount @e[type=tesla_tycoon:%s,limit=1]" % v], 5))
        steps.append(("%s_free" % v, ["ride @p dismount", "tp @p 0 -60 8 180 0"], 2))
    return steps


def tour_firstperson():
    """The player takes the wheel of every car: the driver's own eyes (first person) looking ahead and down at the wheel and dash.
    (A /tp would throw the player out of the car, so the look direction is set before mounting.)"""
    cars = ["tesla_classic", "tesla_model3", "tesla_cybertruck", "tesla_cybercab", "tesla_roadster"]
    steps = [("00_setup", BASE + KILL_ALL + ["kill @e[type=tesla_tycoon:suited_villager]", "tp @p 0 -60 6 180 0"], 3)]
    for v in cars:
        steps.append(("%s_ahead" % v, KILL_ALL + ["summon tesla_tycoon:%s 0 -60 0 {Rotation:[0.0f,0.0f]}" % v, "tp @p 0 -60 6 0 6",
                                                  "ride @p mount @e[type=tesla_tycoon:%s,limit=1]" % v], 7))
        steps.append(("%s_wheel" % v, ["ride @p dismount", "tp @p 0 -60 6 0 42", "ride @p mount @e[type=tesla_tycoon:%s,limit=1]" % v], 6))
    return steps


TOURS = {"vehicles": tour_vehicles, "seat": tour_seat, "city": tour_city, "story": tour_story, "finale": tour_finale, "truck": tour_truck, "shop": tour_shop, "delivery": tour_delivery, "guide": tour_guide, "occupants": tour_occupants, "firstperson": tour_firstperson}


def have(tool):
    return shutil.which(tool) is not None


def make_world(log):
    shutil.rmtree(os.path.join(RUN, "world"), ignore_errors=True)
    os.makedirs(RUN, exist_ok=True)
    open(os.path.join(RUN, "eula.txt"), "w").write("eula=true\n")
    open(os.path.join(RUN, "server.properties"), "w").write(
        "level-type=flat\ngenerate-structures=false\nonline-mode=false\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\ndifficulty=peaceful\n")
    with open(log, "w") as lf:
        p = subprocess.Popen([GRADLE, "runServer", "--console=plain"], cwd=ROOT, stdin=subprocess.PIPE, stdout=lf, stderr=subprocess.STDOUT, text=True)
        t0 = time.time()
        while time.time() - t0 < 360:
            if "For help, type" in open(log, errors="replace").read():
                break
            if p.poll() is not None:
                break
            time.sleep(1)
        try:
            p.stdin.write("stop\n")
            p.stdin.flush()
        except Exception:
            pass
        try:
            p.wait(timeout=60)
        except subprocess.TimeoutExpired:
            p.kill()
    return os.path.exists(os.path.join(RUN, "world", "level.dat"))


def write_pack(steps):
    pack = os.path.join(RUN, "saves", SAVE, "datapacks", "ttsmoke")
    shutil.rmtree(pack, ignore_errors=True)
    os.makedirs(os.path.join(pack, "data", "minecraft", "tags", "functions"))
    os.makedirs(os.path.join(pack, "data", "ttsmoke", "functions"))
    open(os.path.join(pack, "pack.mcmeta"), "w").write('{"pack":{"pack_format":15,"description":"Tesla Tycoon client smoke test"}}\n')
    open(os.path.join(pack, "data", "minecraft", "tags", "functions", "load.json"), "w").write('{"values":["ttsmoke:load"]}\n')

    def fn(name, lines):
        open(os.path.join(pack, "data", "ttsmoke", "functions", name + ".mcfunction"), "w").write("\n".join(lines) + "\n")

    fn("load", ["schedule function ttsmoke:wait 20t"])
    fn("wait", ["execute if entity @a run schedule function ttsmoke:s0 300t", "execute unless entity @a run schedule function ttsmoke:wait 20t"])
    for i, (name, cmds, wait) in enumerate(steps):
        nxt = "schedule function ttsmoke:s%d %dt" % (i + 1, max(20, int(wait * 20)))
        fn("s%d" % i, ['tellraw @a "TT_STEP %s"' % name] + cmds + ([nxt] if i + 1 < len(steps) else ['tellraw @a "TT_DONE"']))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tour", choices=sorted(TOURS))
    ap.add_argument("--out", default=os.path.join(RUN, "screenshots"))
    ap.add_argument("--size", default="960x540")
    ap.add_argument("--keep-world", action="store_true")
    ap.add_argument("--settle", type=float, default=1.5)
    a = ap.parse_args()
    for tool in ("Xvfb", "import"):
        if not have(tool):
            print("SKIP  needs %s" % tool)
            return 2
    steps = TOURS[a.tour]()
    os.makedirs(a.out, exist_ok=True)
    for f in os.listdir(a.out):
        if f.endswith(".png"):
            os.remove(os.path.join(a.out, f))
    wlog = os.path.join(RUN, "world_gen.log")
    if not (a.keep_world and os.path.exists(os.path.join(RUN, "saves", SAVE, "level.dat"))):
        print("info  creating a pristine world with the dedicated server (this can take a minute)")
        if not make_world(wlog):
            print("FAIL  could not create a world, see", wlog)
            return 1
        shutil.rmtree(os.path.join(RUN, "saves", SAVE), ignore_errors=True)
        os.makedirs(os.path.join(RUN, "saves"), exist_ok=True)
        shutil.copytree(os.path.join(RUN, "world"), os.path.join(RUN, "saves", SAVE))
        try:
            os.remove(os.path.join(RUN, "saves", SAVE, "session.lock"))
        except OSError:
            pass
    write_pack(steps)
    w, h = a.size.split("x")
    open(os.path.join(RUN, "options.txt"), "w").write(
        "onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\njoinedFirstServer:true\nskipMultiplayerWarning:true\n"
        "renderDistance:6\nsimulationDistance:6\nmaxFps:30\nguiScale:2\nhideGui:true\nparticles:0\nmipmapLevels:2\n")
    log = os.path.join(RUN, "client_smoke.log")
    display = ":77"
    xvfb = subprocess.Popen(["Xvfb", display, "-screen", "0", "%sx%sx24" % (w, h), "+extension", "GLX", "+render", "-noreset"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2)
    env = dict(os.environ, DISPLAY=display, LIBGL_ALWAYS_SOFTWARE="1", GALLIUM_DRIVER="llvmpipe")
    env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " -Xmx2g -Dtesla_tycoon.debugdrive=true -Dtesla_tycoon.guide=" + ("true" if a.tour == "guide" else "false") + " -Dtesla_tycoon.thirdperson=" + ("front" if a.tour == "seat" else ("true" if a.tour in ("finale", "truck") else "false")) + " -Dtesla_tycoon.chase=" + ("0.3" if a.tour == "seat" else "1")).strip()
    lf = open(log, "w")
    client = subprocess.Popen([GRADLE, "runClient", "-PttQuickPlay=" + SAVE, "--args=--width %s --height %s" % (w, h), "--console=plain"],
                              cwd=ROOT, env=env, stdout=lf, stderr=subprocess.STDOUT, start_new_session=True)
    fail = 0
    seen = 0
    try:
        t0 = time.time()
        names = [s[0] for s in steps]
        while time.time() - t0 < 1500:
            text = open(log, errors="replace").read()
            while seen < len(names) and ("TT_STEP %s" % names[seen]) in text:
                time.sleep(max(0.5, steps[seen][2] - a.settle))        # shoot late in the step, before the next one runs
                path = os.path.join(a.out, "%s.png" % names[seen])
                subprocess.run(["import", "-window", "root", path], env=env, check=False)
                print("shot ", path)
                seen += 1
            if "TT_DONE" in text and seen >= len(names):
                break
            if client.poll() is not None:
                print("FAIL  client exited early (code %s)" % client.returncode)
                fail = 1
                break
            time.sleep(1)
        else:
            print("FAIL  timeout after %d steps of %d" % (seen, len(names)))
            fail = 1
    finally:
        try:
            os.killpg(client.pid, signal.SIGTERM)
        except Exception:
            pass
        subprocess.run(["pkill", "-f", "net.fabricmc.devlaunchinjector.Main"], check=False)
        time.sleep(2)
        xvfb.terminate()
    text = open(log, errors="replace").read()
    bad = [l for l in text.splitlines() if any(k in l for k in ("Could not load vehicle model", "core shader is not loaded", "Couldn't load shader", "GLSL", "Failed to load shader", "Exception in thread"))]
    for l in bad[:10]:
        print("ERR  ", l[:220])
    if bad:
        fail = 1
    print("log: %s" % log)
    return fail


if __name__ == "__main__":
    sys.exit(main())
