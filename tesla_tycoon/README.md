# Tesla Tycoon for Fabric 1.20.1

Build **Anant Tesla Showroom**, out-sell **Seth Gotiya's Petrol Motors** across the street, survive five rounds of his sabotage
and fly the SpaceX Roadster off the roof in the finale. The six cars are your uploaded 3D models, the showroom banner,
billboards and wall posters are your uploaded images (shipped unchanged).

* Minecraft **1.20.1**, Fabric Loader 0.15+, **Fabric API** (0.92.x), Java 17+
* Jar: `dist/tesla_tycoon-1.0.0.jar` (drop it in `mods/`). In a world type **`/tycoon start`**.

## Quick start

1. `/tycoon start` - the city is built around you in a few seconds (never lags: blocks are placed in small per-tick slices) and the
   time is set to night so the neon shines. You get 2 wall posters.
2. Customers (suited villagers) walk in **by themselves**, pay at reception, test-drive a car around the ring road and bring it back.
   A transport truck unloads Tesla crates in the back alley every ~90 s. Income arrives automatically.
3. **Right-click the rotating exhibit** to test-drive that model yourself (it appears in the glass test-drive bay).
4. Press **B** (or use the **Shop Terminal** in the Tycoon Upgrade Shop next door) to buy upgrades and counter-items.

## Controls

| Key | Cars | Roadster (Level 5) | Cybertruck |
|---|---|---|---|
| **W / S** | accelerate (momentum builds) / brake, then reverse | forward speed in the air | |
| **A / D** | steer - only turns while the car moves; wheels turn visibly | banked yaw | |
| **SPACE** | hydraulic hop (after *Jump Hydraulics*) | **hold: ignite the twin rocket thrusters**, release: hover | |
| **CTRL** | | descend | |
| **Mouse** | free look (camera follows the car's turns) | **pitch + yaw steering in the air** | |
| **V** | | | **Beast Ram Mode** on/off (after the Ram module) |
| **R** | | **EMP shockwave** | |
| **Shift** | get out (not possible while flying) | | |
| **B** | open the Upgrade Shop | | |

`stepHeight` is **1.2** on every vehicle: 1-block curbs, slabs and stair-like inclines are driven over without stopping, and a short
decaying camera offset hides the step so first- and third-person (F5) views stay smooth. The driver is drawn seated (0.85 scale) in the
cabin with both hands on a rotating steering wheel; the glass is translucent so you can see them.

## The story (about 17 minutes, a purchase every 1.5 - 2 minutes)

Costs were solved by `tools/pacing_sim.py` and are checked by a game test. Each *counter* answers one of Gotiya's sabotages.

| # | You buy | Cost | Gotiya's move | What it does |
|---|---|---|---|---|
| 1 | **Jump Hydraulics + Armor Tires** | $350 | **Road puncture spikes** on the road (25 s after the city starts) | SPACE hops; armoured tires crush spikes |
| 2 | **Showroom Level 2** | $550 | | New glass floor; **Tesla Model 3** on the ground floor, the Compact moves up |
| 3 | **Tesla Solar Roof + MegaPack** | $650 | **Blackout** - every neon block goes dark, customers stop | Neon power restored, +20% income |
| 4 | **Showroom Level 3** | $1,250 | | **Armored Cybertruck** |
| 5 | **Beast Ram Mode Module** | $1,350 | **Concrete roadblocks** on the road | Press V in the Cybertruck, smash 2 roadblocks |
| 6 | **Showroom Level 4** | $2,500 | | **Cybercab (Robotaxi)** |
| 7 | **Starlink Satellite Dish** | $2,350 | **Cybercab GPS jammed** (it steers drunk) | +10% income |
| 8 | **Robotaxi Terminal** | $2,700 | | +25% income |
| 9 | **Billboard Marketing Pack** | $3,200 | | Right-click empty billboard boards: `city_billboard.png` goes up, **+6% income each**, 3 needed. Gotiya's customers are stolen |
| 10 | **Showroom Level 5** | $7,600 | **Finale: Gotiya's Monster Truck attacks the showroom** | **Flying Roadster**, rooftop launchpad, EMP |

*Finale*: you are placed in the Roadster on the rooftop launchpad. Hold **SPACE**, fly at the truck and press **R**: the EMP shockwave
fries it. Every hit the truck lands smashes glass and costs the showroom 14% - at 0% type **#retryscene**.

### `#retryscene`
Typing `#retryscene` in chat (or `/tycoon retry`) instantly **repairs every showroom block**, puts you and a fresh Flying Roadster on the
rooftop launchpad and **restarts Gotiya's Monster Truck attack cleanly** so you can record the scene again. (Below level 5 it repairs and
restarts the current level's sabotage.)

### Seth Gotiya's chat
He wears a suit outside his dealership, taunts you every ~15-30 s as `[Seth Gotiya]: ...` and answers **any** message you type with a
Hinglish comeback (it reacts to what you wrote: Tesla talk, money, insults, greetings, "lol", questions ...), brags while sabotaging and
cries when you counter him.

## Commands
`/tycoon start | home | shop | balance | posters | retry` (everyone) and, for operators, `rebuild`, `money <n>`, `level <1-5>`, `sabotage`,
`buy <id>`, `car <id>`, `emp`, `drive ...` (scripted controls, only honoured with `-Dtesla_tycoon.debugdrive=true`).

## How the 3D models were used
`tools/bake_models.py` reads the six ZIPs **byte-for-byte** (they live in `assets/tesla_tycoon/models/`) and converts them to compact `.ttm`
meshes: node transforms baked, re-oriented (front = +Z) and scaled to blocks, **wheels split into parts** (so they spin and steer), glass
and lights separated, and three levels of detail. The originals were far too heavy for a game: Level 1 is **1.37 million** triangles,
Level 5 half a million, the Cybercab 171 thousand - almost all in its wheels. After decimation (meshoptimizer) the hero LOD0 meshes are 28k, 54k
and 18k triangles, with 2-3k at distance. Everything is drawn from GPU vertex buffers with a small core shader (lightmap, fog,
emissive lights, translucent glass, cheap sky reflections so dark paint keeps its shape) - one draw call per part.
The Level 5 emissive texture was baked into vertex colours, so there is no 2048 px texture to load.

| Level | Entity | Source model (see CREDITS.txt) | Tris LOD0/1/2 |
|---|---|---|---|
| 1 | Tesla Classic Compact | "Tesla Model Y" (your *Jugaad Rickshaw* file) | 28.6k / 7.3k / 2.3k |
| 2 | Tesla Model 3 | "VEHICLE - TESLA MODEL 3" | 4.3k |
| 3 | Armored Tesla Cybertruck | "Tesla Cybertruck Low Poly" | 8.3k |
| 4 | Autonomous Tesla Cybercab | "Hyundai based concept car" | 17.9k / 5.4k / 1.6k |
| 5 | Flying SpaceX Tesla Roadster | "Tesla Roadtser 2026" (+ generated twin rocket nozzles and plasma flames) | 54k / 13k / 4k |
| - | Seth Gotiya's Monster Truck | "Stylized Monster Truck \| HandPaint" | 1.5k |

The only vehicle that is *not* one of your models is the **delivery truck** in the back-alley scene (generated from boxes).

## Interpretations I made
* The *Jugaad Rickshaw* ZIP contains a compact Tesla, so Level 1 is the **Tesla Classic Compact**, as you asked.
* "Floors 2, 3, 4" - the showroom gets one glass floor per level, so Level 5 has **five** floors (the newest model always on the ground
  floor, older models one floor higher each) plus the rooftop launchpad.
* The city is built **into your current world** at your position (not a new dimension: a data-driven dimension makes Minecraft show the
  "experimental settings" warning every time the world is opened).
* Customers' payments are small tips on top of a steady passive income, so a missed customer never stalls the economy.
* Hostile mobs cannot spawn inside the city; the time is set to night once when the city is built (the day cycle is untouched).

## Build from source
```
python3 build_tesla_tycoon.py            # JDK 17+; gradle build -> dist/tesla_tycoon-1.0.0.jar
python3 build_tesla_tycoon.py --rebake   # also re-bake models/resources (pip install numpy pillow meshoptimizer)
cd tesla_tycoon && ./gradlew build runGametest
```
The project is an ordinary Fabric/Loom workspace: open `tesla_tycoon/` in IntelliJ.

## Verification that was actually run
* **10 GameTests** (`./gradlew runGametest`): acceleration steps never exceed the spec (no jerks), cars do not turn while standing still,
  braking/reverse, a 1-block curb is climbed and the camera offset engages and decays, the Roadster lifts off, hovers and lands, AI cars drive
  a route and park, purchases arrive every 80-130 s, upgrade rules, the Hinglish chat, the city plan, and an **integration test that builds
  the city, switches the neon off and on, adds floors and checks the exhibit of every floor**.
* **Headless client tours** (`tools/smoke_client.py vehicles|city|story|seat`) under Xvfb + software OpenGL: every vehicle photographed, the
  whole city photographed, and the full story played with real client-side driving, flight and EMP. Screenshots were reviewed by eye.
* Not verified here: how it feels on a real GPU with a real keyboard, audio (the sandbox has no sound device), and compatibility with
  shader packs (Iris) - custom core shaders are not supported by most shader packs. Sodium/Lithium are fine in principle.

## Credits
See `CREDITS.txt` (5 CC-BY-4.0 models, the Sketchfab-licensed Monster Truck - **do not extract and re-upload it**).
