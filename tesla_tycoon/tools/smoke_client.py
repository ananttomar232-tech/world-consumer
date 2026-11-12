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

VEHICLES = ["tesla_classic", "tesla_model3", "tesla_cybertruck", "tesla_cybercab", "tesla_roadster", "gotiya_monster_truck",
            "gotiya_hatch", "gotiya_mustang", "gotiya_lambo"]
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


def tour_seatback():
    """Third person from BEHIND with a short chase distance: the seated player (torso, cape) seen through the rear window of every car."""
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 3)]
    for v in ["tesla_classic", "tesla_model3", "tesla_cybertruck", "tesla_cybercab", "tesla_roadster"]:
        spawn = ["summon tesla_tycoon:%s 0 -60 0 {Rotation:[0.0f,0.0f]}" % v]
        steps.append(("%s_seat_behind" % v, KILL_ALL + spawn + ["tp @p 0 -60 6 0 8", "ride @p mount @e[type=tesla_tycoon:%s,limit=1]" % v], 8))
    return steps


def tour_depot():
    """v3: the physical Upgrade Depot (floating 3D models with price tags), buying puts the item in the hand, placing it builds the floor;
    Gotiya walking out to lay his spikes by hand; the Cybercab's roof and boot; the Roadster cockpit."""
    P = "execute as @p run "
    return [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
            ("01_start", [P + "tycoon start"], 45),
            ("02_depot_inside", ["gamemode survival @p", P + "money set 4000", cam(-14, -59.5, -8, -14, -58.6, -24)], 8),
            ("03_depot_west", [cam(-15, -59.5, -15, -21, -58.4, -13)], 6),
            ("04_depot_north", [cam(-14, -59.5, -17, -14, -58.4, -24)], 6),
            ("05_buy_hydraulics", [P + "tycoon buy hydraulics", cam(-17, -59.5, -11, -21, -58.4, -9)], 5),
            ("06_deploy_hydraulics", [P + "tp @s -40 -60 -3 180 10", P + "tycoon deploy hydraulics"], 5),
            ("07_buy_level2", [P + "tycoon buy level2", cam(-17, -59.5, -15, -21, -58.4, -13)], 5),
            ("08_carry_to_showroom", [P + "tp @s -45 -60 6 0 -10"], 4),
            ("09_place_level2", [P + "tycoon deploy level2"], 18),
            ("10_gotiya_walks", [P + "tycoon sabotage", cam(-50, -57.5, 30, -58, -60, 20)], 7),
            ("11_gotiya_spikes", [cam(-52, -57, 14, -60, -61, 10)], 9),
            ("12_gotiya_spikes_done", [cam(-52, -55, -4, -61, -61, 10)], 12),
            ("13_chat", [], 6),
            ("14_cybercab", ["gamemode creative @p", "summon tesla_tycoon:tesla_cybercab -30 -61 10 {Rotation:[90.0f,0.0f]}", cam(-24, -57.5, 6, -30, -60, 10)], 7),
            ("15_cybercab_rear", [cam(-25, -58, 14.5, -30, -60, 10)], 6),
            ("16_roadster_cockpit", ["summon tesla_tycoon:tesla_roadster -30 -61 16 {Rotation:[90.0f,0.0f]}", "tp @p -30 -61 16 90 12",
                                     "ride @p mount @e[type=tesla_tycoon:tesla_roadster,x=-30,y=-61,z=16,distance=..3,limit=1]"], 5),
            ("17_cockpit_steer", [P + "tycoon drive 0.6 1"], 3),
            ("18_cockpit_steer2", [P + "tycoon drive 0.6 -1"], 3),
            ("19_punch_note", [P + "tycoon drive off"], 3)]


def tour_gotiya():
    """v3: Gotiya as a person - spying at the showroom pillars, laying the spike belt and the roadblocks by hand, then the delayed finale:
    a quiet level 5, the launch pad that starts the alarm, Gotiya at the wheel of the one Monster Truck, the flight, the EMP, #retryscene."""
    P = "execute as @p run "
    wide = cam(-44, -53, 22, -44, -60, -4)
    return [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
            ("01_start", [P + "tycoon start"], 45),
            ("02_quiet_start", [wide], 14),
            ("03_spy_a", [wide], 12),
            ("04_spy_b", [wide], 12),
            ("05_spy_west", [cam(-72, -58.5, 4, -61.6, -59.5, -4.4)], 5),
            ("06_spy_east", [cam(-16, -58.5, 4, -26.4, -59.5, -4.4)], 5),
            ("07_spikes_0", [P + "tycoon sabotage", cam(-50, -57, 18, -58.5, -60, 12)], 4),
            ("08_spikes_1", [], 4),
            ("09_spikes_2", [], 4),
            ("10_spikes_3", [], 5),
            ("11_spikes_done", [], 12),
            ("12_level3", [P + "money add 900000", P + "tycoon level 3", cam(-45, -57, 26, -44, -50, -6)], 40),
            ("13_blocks_0", [P + "tycoon sabotage", cam(-2, -56.5, 10, -10, -60, 18)], 5),
            ("14_blocks_1", [], 4),
            ("15_blocks_2", [], 5),
            ("16_blocks_east", [cam(12, -56.5, 10, 21, -60, 18)], 8),
            ("17_blocks_east2", [], 6),
            ("18_level5", [P + "tycoon level 5", cam(-45, -50, 30, -44, -38, -6)], 60),
            ("19_level5_quiet", [cam(-80, -56, 12, -40, -60, 10)], 8),
            ("20_onto_the_pad", [P + "tp @s -51.5 -60 -18.5 0 5"], 4),
            ("21_truck_driver", [cam(-64, -58.5, 9, -44, -57, -1)], 6),
            ("22_truck_driver2", [], 6),
            ("23_roof", [P + "tp @s -47.5 -25 -19.5 -80 8"], 4),
            ("24_mount", ["ride @p mount @e[type=tesla_tycoon:tesla_roadster,x=-43.5,y=-25,z=-21.5,distance=..3,limit=1]"], 3),
            ("25_ignite", [P + "tycoon drive 0 0 j"], 2.5),
            ("26_hover", [P + "tycoon drive 0 0"], 3),
            ("27_emp", [P + "tycoon emp"], 3),
            ("28_victory", [], 8),
            ("29_retry", [P + "tycoon drive off", P + "tycoon retry"], 45),
            ("30_retry_state", [cam(-64, -58.5, 9, -44, -57, -1)], 8)]


def tour_emp():
    P = "execute as @p run "
    return [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
            ("01_start", [P + "tycoon start"], 45),
            ("02_level5", [P + "money add 900000", P + "tycoon level 5", cam(-45, -50, 30, -44, -38, -6)], 60),
            ("03_pad", [P + "tp @s -51.5 -60 -18.5 0 5"], 5),
            ("04_truck", [cam(-56, -57, 12, -44, -59, -3)], 4),
            ("05_truck2", [cam(-50, -58.5, 8, -44, -58.5, -4)], 5),
            ("06_roof", [P + "tp @s -47.5 -25 -19.5 -80 8"], 4),
            ("07_mount", ["ride @p mount @e[type=tesla_tycoon:tesla_roadster,x=-43.5,y=-25,z=-21.5,distance=..3,limit=1]"], 3),
            ("08_ignite", [P + "tycoon drive 0 0 j"], 2.5),
            ("09_hover", [P + "tycoon drive 0 0"], 12),
            ("10_emp", [P + "tycoon emp"], 3),
            ("11_victory", [], 8),
            ("12_gotiya_out", [P + "tycoon drive off", cam(-56, -57, 12, -44, -59, -3)], 8)]


def tour_v4():
    """v4: Gotiya's showroom (lights, petrol cars), the Tesla-logo staff, customers from both sides, the spikes on both sides of the
    showroom, his voiced chat answers and the customers' complaints, the cockpit of all eight cars, /destroy, empathy -> partner."""
    P = "execute as @p run "
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
             ("01_start", [P + "tycoon start"], 45),
             ("02_gotiya_front", [cam(-40, -57.0, 20, -40, -58.0, 44)], 10),
             ("03_gotiya_inside_west", [cam(-41, -58.6, 44.5, -56, -59.5, 49)], 6),
             ("04_gotiya_inside_east", [cam(-39, -58.6, 44.5, -24, -59.5, 49)], 6),
             ("05_clerk_face", [cam(-14, -59.4, -12.5, -14, -58.6, -15)], 5),
             ("06_manager_face", [cam(-53.5, -58.4, -11.2, -53.5, -58.0, -14.1)], 5),
             ("07_customers", [cam(-45.5, -57.5, 4, -45.5, -59.0, -4)], 12),
             ("08_chat_hello", [P + "tycoon chat namaste seth ji, kaise ho?", cam(-40, -57.5, 16, -40.5, -58.5, 28.5)], 6),
             ("09_chat_help", [P + "tycoon chat what should i do to win?"], 6),
             ("10_sabotage", [P + "money set 0", P + "tycoon sabotage", cam(-50, -56.5, 18, -59, -60.5, 8)], 12),
             ("11_spikes_west", [cam(-54, -55.5, -4, -61, -61, 10)], 14),
             ("12_spikes_east", [cam(6, -55.5, -4, -2, -61, 10)], 18),
             ("13_complaint", [cam(-45.5, -57.5, 4, -45.5, -59.0, -4)], 10),
             ("14_level3", [P + "money add 900000", P + "tycoon level 3", cam(-40, -55.5, 18, -40, -58, 44)], 40),
             ("15_gotiya_upgraded", [cam(-40, -58.6, 44.5, -56, -59.5, 49)], 8)]
    for v in ["tesla_classic", "tesla_model3", "tesla_cybertruck", "tesla_cybercab", "tesla_roadster", "gotiya_hatch", "gotiya_mustang", "gotiya_lambo"]:
        steps.append(("16_cockpit_%s" % v, KILL_ALL[:-1] + ["summon tesla_tycoon:%s -30 -61 16 {Rotation:[90.0f,0.0f]}" % v, "tp @p -30 -61 16 90 14",
                                                   "ride @p mount @e[type=tesla_tycoon:%s,x=-30,y=-61,z=16,distance=..3,limit=1]" % v], 5))
    steps += [("17_off", ["kill @e[type=tesla_tycoon:gotiya_lambo,x=-30,y=-61,z=16,distance=..3]", "tp @p -45 -60 10 0 0"], 3),
              ("18_destroy", [P + "destroy"], 4.5),
              ("19_destroy_flight", [], 3.5),
              ("20_destroy_emp", [], 3.5),
              ("21_destroy_boom", [], 3.0),
              ("22_destroy_boom2", [], 3.0),
              ("23_destroy_orbit", [], 3.5),
              ("24_destroy_gotiya", [], 3.5),
              ("25_after", [], 4),
              ("26_ruin", [cam(-40, -55.0, 18, -40, -59, 42)], 6),
              ("27_empathy", [P + "tycoon chat I am really sorry about your shop seth ji, let us work together as partners", cam(-40, -57.5, 16, -40.5, -58.5, 28.5)], 14),
              ("28_partner_rebuilt", [cam(-40, -55.5, 18, -40, -58, 44)], 25)]
    return steps


def tour_v4b():
    """v4: his showroom at level 1 (two cars), what he does on his own for three minutes, /destroy, then the partnership rebuild."""
    P = "execute as @p run "
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
             ("01_start", [P + "tycoon start"], 45),
             ("02_inside", [cam(-41, -58.6, 44.5, -56, -59.5, 49)], 8)]
    for i in range(3, 12):
        steps.append(("%02d_watch" % i, [cam(-40, -45, 12, -42, -60, 22)], 18))
    steps += [("12_destroy", [P + "destroy"], 23),
              ("13_ruin", [cam(-40, -55.0, 18, -40, -59, 42)], 5),
              ("14_partner", [P + "tycoon partner"], 25),
              ("15_rebuilt", [cam(-40, -55.0, 18, -40, -59, 42)], 5),
              ("16_rebuilt_inside", [cam(-40, -58.6, 44.5, -24, -59.5, 49)], 5)]
    return steps


def tour_city():
    """Build the metropolis (origin 0,-61,0 in the flat test world) and photograph every part of it: the HUD, the Grand Boulevard, an
    intersection from above, the skyline, a robotaxi station, a highway billboard, the petrol station, the depot, the aerial view."""
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
             ("01_start", ["execute as @p run tycoon start"], 70)]
    def at(name, x, y, z, tx, ty, tz, wait=8):
        steps.append((name, [cam(x, y, z, tx, ty, tz)], wait))
    at("02_hud_showroom", -45.5, -59.0, 14, -44, -55, -6, 10)
    at("03_boulevard_west", -110, -59.4, 10, 0, -58.5, 10.5, 12)
    at("04_boulevard_east", 62, -59.4, 11, -60, -58.5, 10.5, 12)
    steps.append(("04b_spectator", ["gamemode spectator @p"], 3))
    at("05_intersection_aerial", -83, -20, 46, -83, -61, 10, 12)
    at("06_skyline_north", 0, -40, 40, 0, -10, -190, 12)
    at("07_avenue_north", -83, -50, 100, -83, -30, -140, 12)
    steps.append(("07b_creative", ["gamemode creative @p"], 3))
    at("08_station", 8, -58.8, 16, 8, -59, -3, 8)
    at("09_billboard_ring", 100, -58, -166, 100, -52, -154, 14)
    at("10_gotiya", -40, -59.5, 15, -40, -57, 40, 10)
    at("11_depot", -14, -59.5, 4, -14, -56, -6, 8)
    at("12_showroom_lobby", -45, -59.5, -7, -44, -57.5, -22, 8)
    at("13_alley", -46, -59, -47, -47, -58, -38, 8)
    steps.append(("13b_spectator", ["gamemode spectator @p"], 3))
    at("14_aerial_city", -12, 70, 135, -12, -61, -20, 20)
    at("15_aerial_skyline", 150, 30, -120, -60, -10, -20, 20)
    at("16_aerial_ring", -170, 60, -165, 100, -40, -150, 20)
    return steps


def tour_hud():
    """The ProBoiz-style cash bar: the start ($50, +$5/sec), $25,619, level 2 (+$35/sec), the driving bonus, the guide on Right Shift."""
    P = "execute as @p run "
    return [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
            ("01_start", [P + "tycoon start"], 60),
            ("02_hud_start", [cam(-45.5, -59.0, 14, -44, -55, -6)], 6),
            ("03_hud_money", [P + "money add 25619"], 7),
            ("04_hud_level2", [P + "tycoon buy hydraulics", P + "tycoon buy level2"], 22),
            ("05_hud_level2_rate", [], 4),
            ("06_hud_driving", [P + "tp @s -33 -60 8 90 0", P + "tycoon car tesla_model3"], 4),
            ("07_hud_driving_go", [P + "tycoon drive 1 0"], 5),
            ("08_hud_stop", [P + "tycoon drive off"], 4)]


def tour_robotaxi():
    """Level 4: the four Cybercabs on their loops, stopping at the sidewalk stations for their passengers."""
    P = "execute as @p run "
    steps = [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
             ("01_start", [P + "tycoon start"], 70),
             ("02_level4", [P + "money add 900000", P + "tycoon level 4"], 30),
             ("03_terminal", [cam(-39, -59.0, -8, -39, -58.4, -12)], 8)]
    # the camera stays at the station in front of the showroom (station 8 on the boulevard): a cab of the core loop passes it every ~40 s
    for i in range(4, 16):
        steps.append(("%02d_station_watch" % i, [cam(8, -58.6, 14, 6, -59.2, 1)], 8))
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
             ("16_level5", [P + "money add 900000", P + "tycoon level 5", P + "tp @s -45.5 -60 -2.5 180 5"], 50),
             ("17_tower", [cam(-45, -50, 30, -44, -38, -6)], 8),
             ("18_alarm", [P + "tycoon sabotage", cam(-64, -58.5, 9, -44, -57, -1)], 8),
             ("19_lift", [P + "tp @s -51.5 -60 -18.5 0 5"], 5),
             ("20_roof_battle", [], 8),
             ("21_mount", ["ride @p mount @e[type=tesla_tycoon:tesla_roadster,x=-43.5,y=-25,z=-21.5,distance=..3,limit=1]"], 3),
             ("22_launch", [P + "tycoon drive 0 0 j"], 2.5),
             ("23_flight", [P + "tycoon drive 0 0"], 3),
             ("24_emp", [P + "tycoon emp"], 5),
             ("25_victory", [], 8),
             ("26_retry", [P + "tycoon drive off", P + "tycoon retry"], 45),
             ("27_retry_battle", [], 8)]
    return steps


def tour_finale():
    """The survival finale from level 5: the alarm and the gate, the truck ramming it, the lobby launch pad up to the roof, the red
    Roadster on the helipad, the real flight with the thrusters, the EMP, the victory, then #retryscene."""
    P = "execute as @p run "
    return [("00_setup", BASE + KILL_ALL + ["tp @p 0 -60 0 0 0"], 4),
            ("01_start", [P + "tycoon start"], 70),
            ("02_level5", [P + "money add 900000", P + "tycoon level 5", P + "tp @s -45.5 -60 -2.5 180 5"], 60),
            ("03_tower", [cam(-45, -50, 30, -44, -38, -6)], 8),
            ("04_lobby_lift", [cam(-45, -59.5, -8, -52, -57, -19)], 8),
            ("05_alarm_start", [P + "tycoon sabotage", cam(-64, -58.5, 9, -44, -57, -1)], 8),
            ("06_truck_ram", [], 8),
            ("07_truck_ram_again", [], 8),
            ("08_onto_the_pad", [P + "tp @s -51.5 -60 -18.5 0 5"], 3.2),
            ("09_rooftop", [], 3.5),
            ("10_roadster_pad", [P + "tp @s -47.5 -25 -19.5 -80 8"], 4),
            ("11_gate_shattered", [P + "tp @s -47.5 -25 -19.5 0 25"], 8),
            ("12_mount", ["ride @p mount @e[type=tesla_tycoon:tesla_roadster,x=-43.5,y=-25,z=-21.5,distance=..3,limit=1]"], 3),
            ("13_ignite", [P + "tycoon drive 0 0 j"], 2.5),
            ("14_hover", [P + "tycoon drive 0 0"], 3),
            ("15_emp", [P + "tycoon emp"], 3),
            ("16_victory", [], 8),
            ("17_descend", [P + "tycoon drive 0.3 0 d"], 6),
            ("18_landed", [P + "tycoon drive off"], 5),
            ("19_retry", [P + "tycoon retry"], 45),
            ("20_retry_battle", [], 8)]


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


TOURS = {"v4": tour_v4, "v4b": tour_v4b, "depot": tour_depot, "gotiya": tour_gotiya, "emp": tour_emp, "vehicles": tour_vehicles, "seat": tour_seat, "seatback": tour_seatback, "city": tour_city, "hud": tour_hud, "robotaxi": tour_robotaxi, "story": tour_story, "finale": tour_finale, "truck": tour_truck, "shop": tour_shop, "delivery": tour_delivery, "guide": tour_guide, "occupants": tour_occupants, "firstperson": tour_firstperson}


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
        "renderDistance:%d\nsimulationDistance:%d\nmaxFps:30\nguiScale:2\nhideGui:%s\nparticles:%d\nmipmapLevels:2\n"
        % (8 if a.tour in ("city", "robotaxi", "finale", "story") else 6, 8 if a.tour in ("city", "robotaxi", "finale", "story") else 6,
           "false" if a.tour in ("depot", "city", "hud", "robotaxi", "finale", "story", "guide", "shop") else "true", 1 if a.tour in ("finale", "story") else 0))
    log = os.path.join(RUN, "client_smoke.log")
    display = ":77"
    xvfb = subprocess.Popen(["Xvfb", display, "-screen", "0", "%sx%sx24" % (w, h), "+extension", "GLX", "+render", "-noreset"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2)
    env = dict(os.environ, DISPLAY=display, LIBGL_ALWAYS_SOFTWARE="1", GALLIUM_DRIVER="llvmpipe")
    env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " -Xmx2g -Dtesla_tycoon.debugdrive=true -Dtesla_tycoon.guide=" + ("true" if a.tour == "guide" else "false") + " -Dtesla_tycoon.thirdperson=" + ("front" if a.tour == "seat" else ("true" if a.tour in ("finale", "truck", "seatback") else "false")) + " -Dtesla_tycoon.chase=" + ("0.3" if a.tour in ("seat", "seatback") else "1")).strip()
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
    bad = [l for l in text.splitlines() if any(k in l for k in ("Could not load vehicle model", "core shader is not loaded", "city job completion failed", "rival task failed", "Couldn't load shader", "GLSL", "Failed to load shader", "Exception in thread"))]
    for l in bad[:10]:
        print("ERR  ", l[:220])
    if bad:
        fail = 1
    print("log: %s" % log)
    return fail


if __name__ == "__main__":
    sys.exit(main())
