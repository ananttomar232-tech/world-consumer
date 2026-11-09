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
`#retryscene`; a compiled `tesla_tycoon-1.0.0.jar` and the build script.

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
