# Tesla Tycoon - design (Fabric 1.20.1)

Written back from the brief; the build followed it directly because the brief already fixed purpose, constraints and success criteria.

## Understanding
**Purpose.** A recordable, zero-effort Minecraft tycoon mini-game: the player's *Anant Tesla Showroom* earns money by itself, the player
buys upgrades and counter-items every 1.5-2 minutes, and a comedic rival, *Seth Gotiya*, sabotages every level until a finale in which the
player flies the Roadster off the roof and EMPs his Monster Truck. `#retryscene` exists so the finale can be re-recorded.

**Success criteria (all from the brief).** The six uploaded 3D models are the six vehicles (no fake boxes); the three uploaded PNGs are
the showroom banner, the billboards and the wall poster; driving is smooth and realistic (momentum, steer only while moving, visible wheel
rotation, `stepHeight=1.2`, no camera jitter, driver visibly seated holding the wheel, Roadster flies with SPACE thrusters); a generated
cyberpunk city with the showroom, rival dealership across the road and the upgrade shop next door; vertically stacking showroom with the
newest car on the ground floor; automated manager / customers / delivery truck; 5-level sabotage-and-counter loop; Hinglish chat AI;
`#retryscene`; a compiled `tesla_tycoon-1.0.0.jar` and the build script. (2.0.0 adds the brief below.)

**Assumptions (and the choice made).** Level-1 model is a compact Tesla (named so, per the brief). One glass floor per level -> 5 floors at
Level 5. City is built into the current world (no custom dimension: it would trigger the vanilla "experimental settings" prompt).
Customers' payments are tips on a passive stream so the economy cannot stall. All numbers below are tunable constants.

## Architecture
| Area | Choice | Why |
|---|---|---|
| Models | offline baker -> `.ttm` (decimated LOD0-2, wheels/glass/lights split) -> one `VertexBuffer` per part + one core shader | the originals are 1.37M / 500k / 171k triangles; "lag-free" |
| Vehicle sim | one `VehicleEntity`, **client-authoritative like vanilla boats**, server validates the vanilla VehicleMove packet; `noGravity` so the "floating" kick never fires | zero input latency and no rubber-banding; reads keys at tick time |
| Camera | passenger yaw follows the car (boat technique), decaying `visualY` offset hides curb steps, longer chase distance, eye lowered with the occupant's scale | jitter-free 1st/3rd person |
| Flight | state machine on the same entity: SPACE thrust, CTRL descend, hover damping, mouse pitch/yaw; server mirrors the flags for particles | one physics path |
| City | `Plan` (box ops in local coords) executed in per-tick slices by `CityBuilder`; the same plan replays only NEON ops for blackout/solar | no lag spikes; deterministic; repairs for `#retryscene` |
| Economy | `Company` per player in a `PersistentState`; costs solved by `tools/pacing_sim.py`, asserted by a game test | pacing is data, not hope |
| NPCs | scripted `SuitedNpcEntity` (waypoints, vanilla limb animation), directors own the stories | robust, cheap |
| Rival | `RivalDirector` state machine + `GotiyaChat`; sabotage k starts when level k is bought | one sabotage per level |
| Signs | entities drawing the PNGs unmodified via mipmapped textures; `BillboardPanelBlock.onUse` installs ads | big flat quads, no atlas |
| Interiors + occupants | `tools/interior_gen.py` bakes a procedural interior (seats, dash, pixel-art touch screen, console, door cards, headliner) into every car from its measured cabin (`tools/cabin_probe.py`); the same numbers give each car an occupant scale, a leg angle that reaches the floor and the eye height | the uploaded cars are hollow, and a Minecraft figure is taller than a driver: nothing may stick out of a car |
| HUD | only money / income / level (and the finale's health bar) are always visible; the objective, Gotiya's alert and the key hints are a guide toggled with Right Shift | a clean screen for recording |

## Risks and how they were covered
* *Heavy meshes* -> decimated (meshoptimizer) with wheels split; verified in-game.
* *Server kicks or rubber-banding for driven/flying vehicles* -> `noGravity`, vanilla packet, collision rules identical on both sides; the
  story test drives and flies a real client-side vehicle and the log was checked for "moved wrongly".
* *The finale could not be won (found by the client tour, not by the code)* -> the 4.7-block-wide Monster Truck jammed on a 4-high fence pole in
  Gotiya's forecourt; the driveway was opened, the route re-planned, a stuck failsafe added and a game test drives the truck out of the garage.
* *A customer's head sticking out of a car roof (reported)* -> the customers were drawn standing; occupants are now scaled and posed per cabin
  (head under the roof lining, legs to the floor) and verified in an `occupants` tour with a customer in every car.
* *Maven Central 429s in this sandbox* -> capped Gradle retries, repeated builds.
* *Real GPU feel / audio / shader packs* -> not testable here; stated in the README.

## v2.0.0 (second brief: metropolis, ProBoiz HUD, pacing, robotaxis, survival finale, vehicle fixes)

**Understanding.** The first version proved the mechanics, but the city was a one-road mock-up, the money arrived too fast and the finale
needed creative mode. The second brief asks for: a real >=300 x 300 city with avenues, markings, intersections, trees and a skyline, the
showroom and Gotiya on opposite sides of a grand boulevard with the Upgrade Depot next door and far-apart highway billboards; a ProBoiz-style
cash bar; levels of 2-3 minutes at $5 / $35 / $150 / $800 per second with the level prices $1.5k / $10k / $50k / $250k; 4 autonomous Cybercabs
and customers who test-drive the exhibits; a finale that plays in Survival (alarm, ramming, lift, helipad, red Roadster, thrusters, EMP,
`#retryscene`); a tight 1.6 x 1.4 car hit box and a driver that sits upright with torso and cape visible.

**Choices.**
| Area | Choice | Why |
|---|---|---|
| Economy | passive rate = the brief's numbers; **driving doubles it**, customers tip 15 s of income, a delivery 8 s, a robotaxi ride 0.7 s; counters are priced by `tools/pacing_sim.py` and asserted by a game test (active 2-3 min per level, standing still 4-6) | the brief's rates alone give 5 minutes per level; "active roleplay and driving" is what closes the gap |
| City builder | the plan is cut into 16 x 16 columns, built nearest-first in ~14-19 ms per tick; chunks a few columns ahead are loaded with temporary forced tickets; the district around the showroom first (you play after ~3 s), the skyline in the background; edits (floors, gate) jump the queue | 600 chunks must never stall the server; the whole plan stays one list of ops |
| Skyline | one procedural `Plan.Shape` per tower (shell, pilasters, lit-window pattern, stepped crown, antenna) instead of hundreds of thousands of ops | memory and plan time |
| Neon / blackout | lamps, signs and the showroom's lights are toggled neon ops; the power switch touches loaded chunks and every chunk that loads later is reconciled from the chunk-load event | a blackout must not force-load the whole city |
| Robotaxis | each of the 4 cabs always has a *virtual* position on its closed loop and earns its fares there; a real simulated car with a real passenger exists only within ~100 blocks of the player; stops are `Ai` waypoints | the fleet works wherever you are, and unloaded chunks never strand a car |
| Finale | alarm (stage 1: siren, flashing beacons, gate, truck ramming without damage) -> lobby launch pad (server-set velocity through a glass shaft, teleport safety net) -> roof visit -> battle (stage 2: gate shatters, charges, HP, EMP); restored after a reload | no creative mode; Gotiya's attack comes only after Level 5 *and* the rooftop |
| Vehicles | cars hit box 1.6 x 1.4, the crosshair target is widened separately; curb smoothing moved from the body to the camera; the interior is baked as `interior` + `interior_back` + `interior_rear` and the last two are not drawn while somebody sits in the car | a chase camera otherwise sees a floating head over the rear bench |

**Risks found while building it.**
* *A server freeze at the first chunk load* (found with `jcmd Thread.print` on the stuck headless client): the neon fix-up ran inside the
  chunk-load event and asked the world for a block of the chunk that was still being promoted - the server thread waited for itself. It now
  reads and writes the chunk it is given. A game test cannot see this (no company is registered there); the client tour did.
* *The Roadster test flew into a neighbouring test's city* -> the city tests run in their own batches.
* *Memory of 300k+ plan ops* -> procedural shapes for towers and trees.
