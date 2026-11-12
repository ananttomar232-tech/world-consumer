# Tesla Tycoon for Fabric 1.20.1  -  v4.0.0

Build **Anant Tesla Showroom** in a real metropolis, out-sell **Seth Gotiya's Petrol Motors** across the Grand Boulevard, survive his
sabotage and win the finale in pure **Survival**: alarm, gate, rooftop launch pad, the red Flying SpaceX Roadster, the EMP. The six cars
are your uploaded 3D models, the showroom banner, billboards and wall posters are your uploaded images (shipped unchanged).

* Minecraft **1.20.1**, Fabric Loader 0.15+, **Fabric API** (0.92.x), Java 17+
* Jar: `dist/tesla_tycoon-4.0.0.jar` (drop it in `mods/`). In a world type **`/tycoon start`**.

## What is new in 4.0.0
* **Gotiya's own showroom** across the boulevard: glass front, polished floor, ceiling lights, red neon and lit display spots with
  his **petrol cars** - your three uploads, the **Ford Mustang Shelby GT500**, the **Lamborghini Aventador** and the **Minecraft
  hatchback** (also drivable: `/give @s tesla_tycoon:gotiya_lambo_spawner` etc.). **He upgrades whenever you do**: more cars, more
  lights, gold trim, neon pillars, roof spotlights, a red carpet, a neon roof. Rich customers of his own walk in and stand at a car;
  Gotiya walks over and **shows it to them** (some buy - he boasts about it).
* **Every car has its own cockpit**, with a matching steering wheel:
  * Classic (Level 1): a jugaad cabin with a phone on a clip-on holder for a screen, one round dial, a marigold garland, a little
    golden idol, the lemon-and-chilli charm and a **fluffy red wheel cover**.
  * Model 3: the wide touch screen and wood dash, round wheel.
  * Cybertruck: a stainless slab, a huge 18.5" screen and a **squircle yoke**.
  * Cybercab: **no wheel and no pedals**, just a 21" lounge screen.
  * Roadster: a digital cluster behind a **butterfly yoke**.
  * Hatchback: a wooden fascia, two analog dials, a cassette radio and a manual gear stick.
  * Mustang: a twin-cowl dash, three gauges, toggle switches and a white shift knob.
  * Lamborghini: a hexagonal rev cluster, a carbon dash, the red start-button cover and a flat-bottomed wheel.
* **Seth Gotiya is busy all day**: every 15 to 35 s at home he sneaks over to spy (and runs when caught), walks through your
  showroom like a customer (and comments if you are near), walks round his own showroom, or shows a car to a customer.
* **He talks - out loud.** 164 lines with **recorded Hindi voice clips** (Piper TTS, no voice-chat mod needed: the voice comes from
  where he stands; far away you hear it quietly). **He answers every chat line**: greetings, questions about him, Tesla vs petrol,
  money, his cars, jokes, food, family, cricket, insults... and **"help / what should I do?" gets a real hint** for the trouble of the
  moment. Chat lines stay on screen 30 s instead of 10.
* **Sabotage, fixed**: the spike belts and the concrete walls now cut the road **on both sides of the showroom**, across the whole
  width. **Your customers complain** in chat (with a voice) when there is trouble, and Gotiya rubs it in. If you cannot afford the
  counter, **Tesla Insurance pays the difference**. The moment the task is done, **everything he put on the road disappears** (also old
  leftovers after a reload). AI cars squeeze past a roadblock instead of waiting forever.
* **Beast Ram works out of the box**: armed by default (V turns it off), smashes at lower speed and a wider path, and a bought-but-unused
  Ram is fitted automatically when you sit in the Cybertruck.
* **Customers every 5 to 8 seconds**, from both ends of the street (each one pays half as much - the pacing stays the same).
* **The Tesla staff's face is the Tesla logo** (the shopkeeper in the depot and the manager at reception).
* **The last task**: once Level 5 is placed, Gotiya comes for the showroom by himself after 45 s (the launch pad starts it sooner).
  Your own summoned Monster Trucks are no longer deleted (only the story's truck is kept single).
* **`/destroy`** - the last task on autopilot, as a cutscene: the red Roadster lifts off the roof, flies over the boulevard, its EMP kills
  his Monster Truck, Gotiya jumps out, and a chain of explosions takes down his dealership and his petrol cars. It ends on him crying
  in the rubble.
* **Empathy**: be kind to him in chat ("sorry", "let's be friends", "we can work together", "don't cry"...). After his defeat one
  heartfelt message is enough; during the war it takes several. Then **Seth Gotiya joins your business**: no more sabotage, his
  dealership is rebuilt as the joint EV + petrol showroom (cyan neon, a Cybertruck among his cars), +30% income, and he works the
  floor as your partner.

## What is new in 3.0.0
* **Physical Upgrade Depot, no GUI.** The 2D shop screen and the B key are gone. In the depot next to the showroom every upgrade floats
  and turns over its own pedestal (the real car model for a showroom level, a 3D item for a countermeasure) with a tag such as
  `[Tesla Model 3] - $1,500`. **Right-click the pedestal**: the cash is taken and the item is in your hand. Carry it to the showroom
  (levels) or anywhere in the city (countermeasures) and **right-click to build / deploy it**. Lost the item? Right-click the pedestal again.
* **Admin commands**: `/money add <n>`, `/money set <n>`, `/money speed <multiplier>`, `/money toggle` (cash HUD on / off).
* **Seth Gotiya is a person now.** One sabotage every 1.5 - 2 minutes at the earliest. He **walks out of his dealership** and lays a
  full-width belt of road spikes by hand, stacks the concrete roadblocks block by block, and now and then **sneaks behind a showroom
  pillar to spy on you - and runs when you come close**. He types like a player: `<Seth_Gotiya> message` in plain white. Nothing else is
  ever written to chat (purchases, hints and warnings are on the action bar; level-ups are screen titles).
* **Showroom**: a customer every 10 - 15 seconds (cash ding at reception, test drive, return); the Tesla Semi delivers on every new level.
  Start at **$0**; an active player can afford the next upgrade every **60 - 90 seconds** (game test).
* **Vehicles**: left-click (punch) a car to pop it into your inventory (it is a 3D item; right-click to park it again). Wheels sit flush
  inside the fenders and turn about the exact centre of their own mesh; from the driver's eyes the front wheels are not drawn, so they can
  never cut into the cockpit. The Cybercab has a dark tinted sunroof and a closed boot. The author's 3D text (and the studio backdrop)
  is stripped from the Cybertruck.
* **Finale**: the world is peaceful until **you** start it: buy and place Level 5, then step on the lobby launch pad. Only then does the
  alarm sound - and **Seth Gotiya himself sits at the wheel** of the Monster Truck. There is exactly **one** Monster Truck in the world
  (duplicates are removed every 2 seconds); before the finale there is none at all.

## What is new in 2.0.0
* **A real city**: 391 x 371 blocks. Four N-S avenues and six E-W streets (15 wide, with lane lines, centre lines, edge lines, zebra
  crossings, stop lines and traffic lights at all **24 intersections**; the outer ring is the highway), the **Grand Boulevard** (22 wide,
  four lanes and a planted median), street trees and neon lamps on every pavement, parks, 10 robotaxi stations, and **80+ skyscrapers**
  (up to 130 blocks, every one a different shape, with lit windows, glowing corner strips, stepped crowns and antennas) - the tallest on
  the outer rows, so the horizon is a wall of towers. The district around the showroom is built first (you can play at once); the skyline
  rises around you in the background (a boss bar shows the progress; it never lags, the build uses ~11 ms per tick and loads chunks ahead
  with temporary tickets).
* **Showroom and Gotiya face each other across the boulevard.** The **Upgrade Depot** stands right next door to the showroom.
  The six **billboards** are on the highway turns, 130-200 blocks from the showroom and far from each other: you have to drive round the
  city to put the ads up.
* **ProBoiz-style cash bar**: a dark rounded bar with a glowing green cash stack, `$25,619` (with commas, counting up smoothly), and
  `+$35/sec` in small green text under it (`x2 DRIVING` while you drive). Nothing else is on screen; Right Shift shows the guide.
* **Tycoon pacing**: $5 / $35 / $150 / $800 per second at levels 1-4, level prices $1,500 / $10,000 / $50,000 / $250,000. An active player
  (driving = double income, customers' tips, delivery trucks, robotaxi fares) needs **2 to 3 minutes per level**; standing still takes 4 to 6.
* **Autonomous robotaxis**: level 4 switches on the Robotaxi Terminal and **4 Cybercabs** that drive closed loops through the whole city
  and stop at the sidewalk stations to let a passenger out and pick up the next one (fares pay you).
* **Showroom customers** walk in, go to reception, look at the exhibit on its pad, take that model for a test drive round the block and
  bring it back to the bay.
* **Survival finale** (no creative mode): buying level 5 sounds an **alarm** (siren, red flashing beacons, red HUD frame), a security
  gate slams shut across the pavement and Gotiya's Monster Truck rams it from across the boulevard. Step on the glowing **launch pad** in
  the lobby: a high-speed lift takes you to the **rooftop helipad**, where the **red Flying Roadster** is fuelled and waiting. Only when
  you reach the roof does the gate shatter and the real attack begin: right-click the Roadster, **SPACE** for the twin rocket thrusters,
  **R** for the EMP that disables the truck. `#retryscene` resets the battle instantly (`#retryscene full` replays the alarm and the lift).
* **Vehicles**: all five cars have a tight **1.6 x 1.4 hit box** (no more getting stuck a block away from curbs and pillars), the body
  never sinks into the road after a curb (the smoothing is camera-only), and the driver sits upright on the seat with the full torso and
  the cape visible - the seat back and the rear bench are not drawn while somebody sits in the car.

## Quick start

1. `/tycoon start` - the district is built around you in seconds, the skyline follows (time is set to night so the neon shines). You
   get 2 wall posters and $50.
2. Customers (suited villagers) walk in **by themselves**, pay at reception, look at the exhibit and test-drive it. A transport truck
   unloads Tesla crates in the back alley every ~90 s. Income arrives automatically; **driving a company car doubles it**.
3. **Right-click the rotating exhibit** to test-drive that model yourself (it appears in the glass test-drive bay).
4. Walk into the **Upgrade Depot** next door, right-click a pedestal to buy, then place the item (see above).
5. The screen stays clean: only the cash bar. Press **Right Shift** to show / hide the **guide** (what to buy next, Gotiya's alerts,
   the key hints of the car you sit in).

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
| **Right Shift** | show / hide the on-screen guide (next purchase, Gotiya's alert, key hints) | | |

`stepHeight` is **1.2** on every vehicle: 1-block curbs, slabs and stair-like inclines are driven over without stopping; a short decaying
offset of the **camera** (never of the car) hides the step so first- and third-person (F5) views stay smooth. The driver is drawn seated at
the cabin's own scale (see *Interiors and seated people*) with both hands on a rotating steering wheel and the feet on the floor.

## The story (about 12 minutes of active play, 2 to 3 minutes per showroom level)

Prices and incomes are solved by `tools/pacing_sim.py` and checked by a game test. Each *counter* answers one of Gotiya's sabotages.

| # | You buy | Cost | Gotiya's move | What it does |
|---|---|---|---|---|
| 1 | **Jump Hydraulics + Armor Tires** | $250 | **Road puncture spikes** on the road (25 s after the city starts) | SPACE hops; armoured tires crush spikes |
| 2 | **Showroom Level 2** | $1,500 | | New glass floor; **Tesla Model 3** on the ground floor, the Compact moves up. **$35/sec** |
| 3 | **Tesla Solar Roof + MegaPack** | $1,500 | **Blackout** - every neon block goes dark, customers stop | Neon power restored |
| 4 | **Showroom Level 3** | $10,000 | | **Armored Cybertruck**. **$150/sec** |
| 5 | **Beast Ram Mode Module** | $5,000 | **Concrete roadblocks** on the boulevard | Press V in the Cybertruck, smash 2 roadblocks |
| 6 | **Showroom Level 4** | $50,000 | | **Cybercab Robotaxi network**: terminal + 4 autonomous cabs. **$800/sec** |
| 7 | **Starlink Satellite Dish** | $25,000 | **Cybercab GPS jammed** (it steers drunk) | Part 1 of the GPS counter |
| 8 | **Billboard Marketing Pack** | $15,000 | | Part 2: drive to the billboards on the highway turns and right-click them: `city_billboard.png` goes up, **+6% income each**, 3 needed |
| 9 | **Showroom Level 5** | $250,000 | **Finale** (see below) | **Flying Roadster** on the rooftop helipad, the lobby launch pad, the EMP |

Passive incomes are exactly $5 / $35 / $150 / $800 per second at levels 1-4 (billboards add 6% each); driving a company car doubles the
rate, a customer's tip is 15 s of income, a delivery 8 s, a robotaxi ride 0.7 s.

### The finale (Survival, no creative mode)
1. **Level 5 is bought** - alarm: siren, red flashing beacons on the roof and the gate posts, red pulsing frame and banner on the HUD.
   The security gate closes along the boulevard pavement (it has a 3-wide opening for you, not for a 4.7-wide truck). Gotiya's Monster
   Truck leaves his garage, crosses the boulevard and **rams the gate again and again** (sparks, shaking bars, nothing is damaged yet).
2. **The lobby launch pad**: stand on the glowing pad in the showroom lobby - a glass lift shaft through every floor shoots you up to the
   rooftop helipad (about 1.5 s). The red Flying Roadster is there, fuelled.
3. **On the roof** the real attack starts (a moment later): the gate shatters, the truck charges the glass front (every hit costs the
   showroom 14%). Right-click the Roadster, hold **SPACE** (twin plasma thrusters), fly down at the truck and press **R**: the EMP
   shockwave fries it. At 0% type **#retryscene**.

### `#retryscene`
Typing `#retryscene` in chat (or `/tycoon retry`) instantly **repairs every showroom block and the gate**, puts you on the roof next to a
fresh red Roadster and **restarts the Monster Truck attack**. `#retryscene full` (or `/tycoon retry full`) starts again at the alarm (gate, lift and all). (Below
level 5 it repairs and restarts the current level's sabotage.)

### Seth Gotiya's chat
He wears a suit outside his dealership, taunts you every ~15-30 s as `[Seth Gotiya]: ...` and answers **any** message you type with a
Hinglish comeback (it reacts to what you wrote: Tesla talk, money, insults, greetings, "lol", questions ...), brags while sabotaging and
cries when you counter him.

## Commands
`/destroy` (the cutscene), `/tycoon start | home | balance | posters | retry` (everyone) and, for operators, `/money add|set|speed|toggle`,
`/tycoon rebuild`, `level <1-5>`, `sabotage`, `buy <id>`, `deploy <id>`, `car <id>`, `emp`, `partner`, `chat <text>` (as if typed in chat),
`drive ...` (scripted controls, only honoured with `-Dtesla_tycoon.debugdrive=true`).

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

The Level 5 Roadster is **recoloured red** after baking (`tools/bake_models.py --paint-only`; the uploaded model is silver). The only vehicle that is *not* one of your models is the **delivery truck** in the back-alley scene (generated from boxes).

### Interiors and seated people
The uploaded cars are hollow shells (only the Classic Compact had a dark interior of its own), so `tools/interior_gen.py` builds a real
interior for every car and bakes it into the model as the parts `interior`, `interior_back` (the driver's seat back and head restraint) and
`interior_rear` (rear bench and parcel shelf) (LOD0 and LOD1, ~3.3k / ~1k triangles in total; the last two are skipped while the car has an
occupant, otherwise they would hide the torso and the cape from a chase camera): soft leather seats with
bolsters, centre inserts and headrests, a rear bench (the Roadster has a bulkhead), a dashboard with a trim band and an LED line, the glowing
pixel-art touch screen (navigation map, car, app dock), a steering column, a centre console with cup holders, door cards with armrests and
ambient LEDs, a carpeted floor with pedals and - for solid roofs - a headliner that follows the roof exactly. Each car has its own colours:
cream (Classic), black with white inserts (Model 3), grey (Cybertruck), all-white (Cybercab), red leather (Roadster).
The same numbers decide **how big the seated driver and the customers are drawn** (`occ` in the `.meta.json`, 0.71 - 0.85): the head always
stays below the roof lining, the arms reach the wheel and the legs reach the floor, so nothing sticks out of a car. First-person eyes follow.
(`python3 tools/cabin_probe.py`, `tools/interior_preview.py` and `tools/preview3d.py` look inside the cars without starting Minecraft.)

## Interpretations I made
* The *Jugaad Rickshaw* ZIP contains a compact Tesla, so Level 1 is the **Tesla Classic Compact**, as you asked.
* "Floors 2, 3, 4" - the showroom gets one glass floor per level, so Level 5 has **five** floors (the newest model always on the ground
  floor, older models one floor higher each) plus the rooftop launchpad.
* The city is built **into your current world** at your position (not a new dimension: a data-driven dimension makes Minecraft show the
  "experimental settings" warning every time the world is opened).
* A seated Minecraft figure is taller than a real driver, so drivers and customers are drawn at 71 - 85 % size inside the cars.
* Customers' payments are small tips on top of a steady passive income, so a missed customer never stalls the economy.
* Hostile mobs cannot spawn inside the city; the time is set to night once when the city is built (the day cycle is untouched).

## Build from source
```
python3 build_tesla_tycoon.py            # JDK 17+; gradle build -> dist/tesla_tycoon-3.0.0.jar
python3 build_tesla_tycoon.py --rebake   # also re-bake models/resources (pip install numpy pillow meshoptimizer)
cd tesla_tycoon && ./gradlew build runGametest
```
The project is an ordinary Fabric/Loom workspace: open `tesla_tycoon/` in IntelliJ.

## Verification that was actually run
* **16 GameTests** (`gradle runGametest`, all green): vehicle physics (smooth acceleration, no turning while standing still, brakes and
  reverse, 1-block curb with the 1.6 x 1.4 hit box, Roadster lift-off / hover / landing), AI cars drive and park, **the economy plays the
  story three ways (active, idle, AFK) and checks that an active player can afford the next purchase every 50 - 95 s**, upgrade rules,
  the Hinglish chat, the city plan and geometry, an integration test that builds the district (neon off/on, floors, exhibits, billboard
  +6 %), **no Monster Truck exists before the finale, sabotage 1 leaves the full-width spike belt, the finale spawns exactly one Monster
  Truck with Seth Gotiya in the driver's seat**, and the drive tests (Monster Truck garage -> showroom front, delivery truck -> dock).
* **v4 checks**: the game tests now also check that the spikes and the walls close the road on both sides and are removed when beaten,
  that Gotiya recognises help / how-are-you / empathy / car questions, that one heartfelt message after his defeat wins him over (one
  "sorry" mid-war does not), that a call for help during the blackout names the Solar Roof, and that every line key has a registered
  voice clip. Tours `v4` and `v4b`: his showroom inside and out, the Tesla-logo staff, chat answers + insurance + a customer's complaint +
  his comeback, the cockpit of all eight cars, the whole `/destroy` cutscene, empathy -> partner -> the rebuilt joint showroom; the
  server log shows him spying and showing cars to his customers.
* **Headless client tours** (`tools/smoke_client.py <tour>`: the real Fabric client under Xvfb + software OpenGL, every screenshot looked at):
  * `depot` (Survival): the Upgrade Depot with the floating models and price tags (`[Tesla Model 3] - $1,500`, `OWNED`, `PLACE IT`),
    buying puts the item in the hotbar (the 3D car model is drawn as the item), placing Level 2 builds the floor; the Cybercab's sunroof
    and closed boot; the Roadster cockpit while steering left and right (no wheel inside the cabin).
  * `gotiya`: a quiet start ($0, no alarm, no Monster Truck); Gotiya spying at the showroom and running away (`arre arre... main toh bas
    rasta dekh raha tha!! bhaago`); walking out and laying the spike belt row by row; stacking the concrete roadblocks with his name tag
    above him; level 5 stays quiet until the launch pad is used; then the alarm, the gate ram, the rooftop and the battle. Chat contains
    only `<Seth_Gotiya> ...` lines.
  * `emp`: level 5 -> launch pad -> alarm -> truck rams the gate -> roof -> Roadster -> thrusters -> EMP -> victory (server log:
    `alarm sounds`, `rams the gate (1)`, `(2)`, `battle starts`, `hits the showroom front`, `EMP disabled the monster truck - victory`).
    This tour found a real bug: a hovering Roadster drifts, and the EMP (62-block sphere) then missed the truck; it now reaches 120 blocks
    sideways and any height.
  * `city`, `hud`, `robotaxi`, `seatback`, `story`, `occupants`, `firstperson` from earlier versions.
* Not verified here: a real GPU / keyboard / sound card, the white cape (the test player has no cape), and shader packs (Iris does not
  support custom core shaders).

## Credits
See `CREDITS.txt` (5 CC-BY-4.0 models, the Sketchfab-licensed Monster Truck - **do not extract and re-upload it**).
