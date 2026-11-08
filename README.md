# RealityShop for Fabric 1.20.1  -  v2

`build_mod.py` is a single Python script (standard library only) that writes a complete, buildable
**Fabric 1.20.1 / Java 17 / Yarn** workspace for the mod **RealityShop**: you consume the world, then spend the
blocks you consumed in a Black Market on six reality-breaking items.

```bash
python3 build_mod.py --out RealityShop      # writes the workspace (about 10 s at the default texture size)
cd RealityShop
./gradlew build                             # JDK 17 -> build/libs/realityshop-2.0.0.jar
./gradlew runClient                         # play it
./gradlew runGametest                       # 19 server-side game tests, each run in a fresh world (eula=true into build/gametest/eula.txt)
```

Options: `--force` (overwrite generated files), `--texture-size 64|128|256|512|1024` (procedural textures, default 256),
`--models DIR`, `--overrides DIR` (see "Your own assets").

**Prebuilt jar:** [`dist/realityshop-2.0.0.jar`](dist/realityshop-2.0.0.jar) (Minecraft 1.20.1, Fabric Loader 0.15+, Fabric API, Java 17; drop it into `mods/`).

Stack: Fabric Loom 1.10.5, Gradle 8.14.3 (wrapper embedded), Yarn `1.20.1+build.10`, Fabric Loader 0.16.14,
Fabric API 0.92.12+1.20.1. Client-only code lives in its own source set (`src/client`), so a dedicated server never
loads a client class.

## What is new in v2

* **Reality Gate** replaces the static portal block: a free-standing 3 x 3 gate with a **thin frame** (4 px bars, crying-obsidian
  corner stones with glowing eyes, a glowing inlay, amethyst crystals) around a **big, crisp pixel-art vortex** drawn by a core
  shader (flat tones, a black hole in the middle, swallowed blocks orbiting in it - the "block consumer" look).
  Placing it: the frame *snaps together* piece by piece, then the vortex *tears open* from a slit. Right-click the frame: the
  vortex collapses into a glowing orb that flies into your inventory while the frame crumbles.
* **No loading screen.** Touching the swirl fades the screen into a vortex that spreads from the middle, the dimension change
  happens under it (two small client mixins skip "Loading terrain" / "Joining world"), and on arrival the vortex retracts.
  The same on the way back, where you step out *in front of* the gate you came through.
* **Bedrock Realm** replaces the obsidian Void Realm (the Quantum USB's destination): a flat bedrock landscape with monoliths.
  There is **no portal back** (die / respawn, or an op uses `/realityshop back`).
* **Gadgets consume what is in FRONT of you** and you never fall: every area starts one block after yours; the floppy, phone,
  furnace, beacon and USB take half-spheres / boxes ahead, and the 3 x 3 columns under your feet are protected.
* **Slower Beacon 5G**: about a minute instead of 15 s (`beaconPurgeSeconds`).
* **Quantum USB 2.95x bigger**: the item model is 2.95x bigger in your hand, on the ground and on other players (the inventory
  icon keeps its size), *and* the purge is 2.95x wider (500 -> 1475 blocks, `usbPurgeSize`). I read the request both ways.
* **Cheaper shop**: 0 / 500 / 5,000 / 50,000 / 300,000 / 1,000,000 blocks.
* **Powers**: every item shows its power as tooltip lines, and the pedestals show it above the name.
* **Counter top left**: a neon pill with an icon, the number, a "Blocks" caption and a +/- bubble when it changes
  (`hudX`, `hudY`, `hudScale`, `hudEnabled`).
* **No black hole after the Glitchy Floppy**: invisible light blocks fill the hole for 45 s (`floppyGlowSeconds`); the
  light of bulk clears is repaired and the touched chunks are re-sent after the light engine caught up.
* **A bigger, better Black Market**: a round cathedral 55 blocks across built from 24 different vanilla blocks - gilded blackstone,
  deepslate, basalt, amethyst, crying obsidian, sea lanterns, shroomlight ... - with tall **stained-glass windows** in six colours,
  a ribbed dome with an oculus and a chandelier, a purple carpet from the gate to the "consumption well", and a lit
  stained-glass **shrine window behind every pedestal** matching its item.
* **Better item models**: the USB, a Hacked RGB Furnace with heat-sink fins and RGB LED bars, a Beacon 5G with a 5G mast, a new
  gate icon.
* **Optimisation**: the glTF models are preloaded when you join (no hitch when the first chicken or phone appears); distant
  DerpChickens draw decimated copies of the 273k-triangle model (about 29k / 6k triangles beyond 12 / 32 blocks, made at load
  time - the model files stay untouched); one shared per-tick time budget for all mass removal; far fewer, cheaper particles.
* **`/cinematic`** (see below).

## Gameplay

* **Blocks Consumed** - every block removed by one of the mod's items is credited to its owner. The counter is a
  `long`, persists, survives death, and is shown top left.
* **Reality Gate** (item `black_market_portal`; you get one on first join, or craft it: Crying Obsidian / Obsidian / Ender
  Pearl, `COC OEO COC`). Place it, walk into the swirl: you stand in the Black Market. Its exit gate steps you out in front of
  the gate you entered.
* **Black Market** - six **Shop Pedestals** show the item floating above them with its power and `Cost: [X] Blocks` - red when
  you cannot afford it, green when you can. Right-click a green pedestal: the cost is deducted and the item is handed over.
* **Bedrock Realm** - where the Quantum USB sends you once its purge is done.

| Item | Cost | Power |
|---|---|---|
| `glitch_pickaxe` | 0 | Mines anything instantly, taking the 3x3x3 around it; credits the blocks. |
| `glitchy_floppy` | 500 | Thrown: destroys a 5x5x5 cube on impact (the hole stays lit) and rains 5 **DerpChickens**. |
| `cursed_kitty_phone` | 5,000 | Right-click the ground: a 50x20x50 pit in front of you vanishes at once, with a scream. |
| `hacked_rgb_furnace` | 50,000 | Placed: eats a radius-100 half-sphere in front of it over 10 s, firing green laser particles. Right-click to run it again. |
| `beacon_5g` | 300,000 | Placed: a red particle tornado while a radius-200 half-sphere in front of it is vacuumed up over about a minute; some blocks spiral up as flying entities. |
| `quantum_usb` | 1,000,000 | Right-click: a 1475x1475 column over the full world height in front of you is purged over 10 s, then you are pulled into the Bedrock Realm. |

Commands: `/realityshop balance`; for testing, op level 2: `add <n>`, `set <n>`, `market`, `bedrock`, `back`,
`gate` (places a gate 4 blocks ahead), `rebuild` (re-builds the shop hall).

Config `config/realityshop.json` (written on first start): `tickBudgetMs`, `destroyBedrock`, `loadMissingChunks`,
`protectedDimensions`, `pickaxeDropsItems`, `giveStarterPortal`, `maxDerpChickens`, `derpChickenLifetimeSeconds`,
`maxSuckedBlocks`, `beaconPurgeSeconds`, `furnacePurgeSeconds`, `usbPurgeSize`, `floppyGlowSeconds`, `hudEnabled`, `hudScale`,
`hudX`, `hudY`, `particleDensity`.

## `/cinematic`

Type `/cinematic` in the chat (a client command: it works in single player and on any server that has the mod). A detached
camera flies one eased **360 degree orbit** around each item - pickaxe, floppy, kitty phone, furnace, beacon, USB, the real
3D gate with its vortex, and the Derp Chicken - on a dark neon stage (pixel starfield, glowing rings, drifting light motes)
with letterbox bars and a title card per item: its power, name, description and price. **Your player, your hand, the HUD
and the world are hidden**; nothing in the world changes and it works anywhere. Single player is paused meanwhile (nothing can
hurt you; on a server it stops if you take damage). `ESC` leaves, `SPACE` skips to the next item; `/cinematic <item>` shows one
(`pickaxe`, `usb`, `gate`, `chicken` ... tab-completes), `/cinematic stop` ends it. About 1 minute in total.

## How the hard parts work

* **Destruction engine** (`destroy/RegionClearJob`). Regions are processed one chunk column at a time, nearest first.
  A 16x16x16 section that lies fully inside the region is swapped for an empty one in O(1) (biomes kept); only the
  sections cut by the region's surface are scanned block by block. Jobs share one per-tick time budget, so even the
  96-million-block USB purge cannot stall the server. By default only **loaded** chunks are touched, bedrock and
  protected dimensions are respected, block entities are cleaned up, nothing drops. `Front` turns "the area in front of the
  player" into boxes / half-spheres that start one block after the player's block.
* **Light.** Bulk clears bypass `setBlockState`, so the job repairs light itself (section status, replacement light arrays,
  sky-surface map, rate-limited `checkBlock`). Vanilla never re-announces light arrays that were replaced wholesale, so when a job
  ends the touched chunks are re-sent *after* the light engine has caught up (a per-chunk barrier), a few per tick. A game test
  requires the result to equal what vanilla `setBlockState` produces.
* **Capability.** Fabric has no Forge-style capabilities; the balance is a persistent, copy-on-death **data attachment**
  (Fabric API) with a server facade (`ConsumedBlocks`) and a sync packet to the client.
* **The gate.** One block with `FACING`, `LATERAL` and `HEIGHT` properties forms the 3 x 3 multiblock; the bottom middle cell owns a
  block entity (animation state, walk-through detection). The swirl is a core shader (`portal_vortex`) that computes the
  spiral per pixel from the game time, so it flows at any frame rate; the same shader draws the full-screen transition, the orb
  and the cinematic stage. Block-model cells use `gate_line` / `gate_glow` animated textures; the frame's collision shape is
  exactly the thin bars.
* **The transition.** The server tells the client when you touch the swirl; the client fades into the vortex while
  `ENTER_TICKS` count down, the teleport runs on the server (between entity ticks, never inside a collision callback) and two
  client mixins (`ClientPlayNetworkHandlerMixin`, `MinecraftClientMixin`) skip the loading screens while a transition runs.
* **Your 3D models.** The glTF/GLB files are shipped untouched and rendered by a small runtime loader (`client/gltf`):
  buffers/images external, data-URI or GLB-embedded, strided accessors, node hierarchy, full-resolution meshes, textures uploaded
  at their original size with mipmaps, and a custom core shader (`gltf_pbr`) doing base colour + normal map +
  metallic-roughness lit like vanilla entities. LOD copies are built from the loaded arrays by vertex clustering (3D cell + UV cell,
  so texture islands are never merged).
* **HUD.** A generated stroke-font atlas + neon plate, uploaded mip-mapped, so the text stays crisp at any GUI scale.
* **Cinematic.** `CameraMixin` replaces the camera pose while a show runs; the stage is drawn at the end of the world pass
  (`WorldRenderEvents.LAST`): a full-screen backdrop, a fresh depth buffer, then floor, item / gate / chicken and motes. Using the
  first-person camera is what keeps the player model unseen.

## Your own assets (drop-in)

Put files next to `build_mod.py`, or pass `--models` / `--overrides`:

* `assets/models/<name>.zip | .glb | .gltf | <name>/` - the 3D models. `derp_chicken` and `cursed_kitty_phone` are used.
  Files are copied byte for byte. Only the *file names* are lower-cased (`material_0_baseColor.png` becomes
  `material_0_basecolor.png`) because Minecraft resource paths cannot contain capitals; the loader maps the URIs inside
  the untouched `.gltf` the same way.
* `assets/overrides/<path under src/main/resources>` - copied last, replacing any generated file, e.g.
  `assets/realityshop/textures/item/glitch_pickaxe.png` (any size, 8K included - shipped as is) or
  `assets/realityshop/sounds/kitty_phone_curse.ogg` (used instead of the vanilla fallback sound).
  Drop the matching `.png.mcmeta` too if the texture is animated; a generated one is removed when you replace its PNG.

## What was verified, and what was not

Run on JDK 17 in a headless Linux container:

| Check | Result |
|---|---|
| `./gradlew build` of the generated workspace | passes |
| `./gradlew runGametest` | **19/19 pass** (fresh world each run): economy attachment, shop offers/costs, pedestal purchase, data-pack dimensions parse with the real codecs, the hall (block variety, windows, dome curve, six pedestals, permanent gate), gate frame shape (thin, solid bars, free opening, low threshold), gate assembly -> vortex opens, picking the gate up, walking into the swirl (also when the player walks on), box and sphere clears (exact counts, credit, protection), paced purge (bedrock survives), sky light equal to vanilla, pickaxe 3x3x3, kitty-phone pit in front, half-sphere in front, floppy impact keeps the footing and leaves a light, the light expires, DerpChicken cap, all items/blocks registered |
| `tools/verify_server.sh` (real dedicated server) | 9/9: mod loads, both dimensions exist and save, hall builds, pedestals hold the right items, the exit gate is permanent, no errors |
| production-style server (stock Fabric launcher + Fabric API + only the built jar) | loads; client mixin config and its refmap are in the jar |
| `tools/verify_client.sh` (real client, Xvfb + Mesa software GL, scripted tour, 73 screenshots) | joins a world and runs the tour: HUD, the gate being summoned (slit, widening swirl, rim sparks), standing, viewed from the side, walked through to the Black Market and back **without a loading screen**, picked up (collapse, orb, crumbling frame) with the item returned, the hall and its pedestal labels, USB in hand, furnace, beacon, floppy impact, Bedrock Realm, and `/cinematic` **typed into the real chat** and run to the end (22 of its 24 sampled frames show the stage, the others are the fades between items); no shader/model/texture errors in the log |

Bugs this verification found and fixed along the way: Fabric silently dropped glTF textures whose file names contain capitals; the
USB item model inherited `item/generated` and drew nothing; clients kept stale (dark) light after a big purge; a game-test world
that survived between runs made a bedrock check depend on history (now every run starts fresh and the test sets its own
markers); my first client tour "teleported" the player into the overworld at the Black Market's height (a `tp` run with the
overworld as source) and killed them by fall damage - a bug in the test script, not the mod, but it hid that the real exit gate
had never been exercised; it is now.

**Not verified - please check on your machine:**

* The green **laser beams** of the furnace: the area is eaten and the particles are emitted, but I could not confirm the beam
  look from headless screenshots.
* A real GPU (only Mesa's software renderer was available), sound (no audio device; vanilla fallback sounds are mapped), and
  real multiplayer with several clients (the gate transition and the cinematic were exercised on the integrated server only).
* The full 1475 x 1475 USB purge with a real player clicking the item (the engine is game-tested at smaller sizes with pacing,
  and the Bedrock Realm trip was exercised with a real player through `/realityshop bedrock`).
* Performance with many DerpChickens on real hardware: they are distance-culled (64 blocks), capped (`maxDerpChickens`), use
  LODs and expire after 45 s by default.

## Things to know

* Minecraft 1.20.1 marks every world with more than the three vanilla dimensions as "experimental" and asks
  **"Worlds using Experimental Settings are not supported"** each time you open it. Click *I know what I'm doing!*
  (this is vanilla behaviour for data-pack dimensions, not an error).
* "8K": nothing caps texture size - glTF textures and override textures are uploaded at full size (your GPU's
  `GL_MAX_TEXTURE_SIZE` decides what is possible). The *generated* placeholder textures default to 256 px;
  use `--texture-size` or drop in your own.
* One UV set (`TEXCOORD_0`) drives every map; the two supplied models use identical sets (the loader logs a warning if a
  model does not). Skins/animations are not supported (neither model has any).
* Interpretations: "5x5" for the floppy is a 5x5x5 cube; the kitty phone pit is 50 wide, 50 deep and 20 tall; the "8K" look is
  about sharpness, not a literal 8192 px HUD texture; "increase size of usb by 2.95x" was applied to the model and to the purge.
* The request also contained a long prompt for a different mod ("HeistMod"). You chose RealityShop, so that mod was not built.
* Debug switches (JVM properties): `-Drealityshop.debug=true` logs every gate trip (positions, timings) on the server and the
  client; `-Drealityshop.cinematicSeconds=3` shortens the cinematic's seconds per item (the automated tour uses it).
* `fabric.mod.json` points at `realityshop.mixins.json` (intentionally empty: no common mixins) and at
  `realityshop.client.mixins.json` (three client mixins: two for the seamless transition, one for the cinematic camera).

## Repository layout

```
build_mod.py              the generator (Java, JSON, shaders, textures, Gradle files, self-checks are all inside)
assets/models/            the two supplied models (CC-BY-4.0), copied byte for byte
assets/wrapper, CREDITS   embedded into build_mod.py by tools/embed_assets.py (Gradle wrapper + credits)
tools/verify_server.sh    dedicated-server boot check
tools/verify_client.sh    best-effort headless client tour with screenshots (incl. /cinematic)
tools/embed_assets.py     re-embeds assets/wrapper + CREDITS.txt into build_mod.py
dist/                     the prebuilt jar
```

## Credits (CC-BY-4.0, used unmodified)

* **"Chicken"** by joshuacaca973 - https://sketchfab.com/3d-models/chicken-59590091ec7647a4beae2e08acb1903a
* **"Retro mobile phone with heart icons on the scre"** by Violetforest -
  https://sketchfab.com/3d-models/retro-mobile-phone-with-heart-icons-on-the-scre-493bf5df4b764db8849c24f1a60ca672

Licence: <http://creativecommons.org/licenses/by/4.0/>. The credits are also written into the generated mod
(`fabric.mod.json` contributors, `CREDITS.txt`).
