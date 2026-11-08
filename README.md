# RealityShop for Fabric 1.20.1

`build_mod.py` is a single Python script (standard library only) that writes a complete, buildable
**Fabric 1.20.1 / Java 17 / Yarn** workspace for the mod **RealityShop**: you consume the world, then spend the
blocks you consumed in a Black Market dimension on six reality-breaking items.

```bash
python3 build_mod.py --out RealityShop      # writes the workspace (about 10 s at the default texture size)
cd RealityShop
./gradlew build                             # JDK 17 -> build/libs/realityshop-1.0.0.jar
./gradlew runClient                         # play it
./gradlew runGametest                       # 13 server-side game tests (if asked, put eula=true into build/gametest/eula.txt)
```

Options: `--force` (overwrite generated files), `--texture-size 64|128|256|512|1024` (procedural textures, default 256),
`--models DIR`, `--overrides DIR` (see "Your own assets").

Stack: Fabric Loom 1.10.5, Gradle 8.14.3 (wrapper embedded), Yarn `1.20.1+build.10`, Fabric Loader 0.16.14,
Fabric API 0.92.12+1.20.1. Client-only code lives in its own source set (`src/client`), so a dedicated server never
loads a client class.

## Gameplay

* **Blocks Consumed** - every block removed by one of the mod's items is credited to its owner. The counter is a
  `long`, persists, survives death, and is shown by a crisp neon HUD plate at the top of the screen.
* **Black Market** - craft a *Black Market Portal* (Crying Obsidian / Obsidian / Ender Pearl, `COC OEO COC`; one is also
  given on first join) and stand on it: instant teleport into a predefined Crying Obsidian hall. Six **Shop Pedestals**
  show the item floating above them with `Cost: [X] Blocks` - red when you cannot afford it, green when you can.
  Right-click a green pedestal: the cost is deducted and the item is handed over (pedestals sell repeatedly).
* **Void Realm** - the dimension the Quantum USB sends you to.

| Item | Cost | What it does |
|---|---|---|
| `glitch_pickaxe` | 0 | Mines anything instantly, taking the 3x3x3 around it; credits the blocks. |
| `glitchy_floppy` | 5,000 | Thrown: destroys a 5x5x5 cube on impact and rains 5 **DerpChickens** (the supplied chicken model). |
| `cursed_kitty_phone` | 50,000 | Right-click the ground: a 50x20x50 pit disappears at once, with a scream (the supplied phone model). |
| `hacked_rgb_furnace` | 500,000 | Vanilla furnace with a green emissive glow. Placed: eats a 100-radius sphere over 10 s, firing green laser particles at what it destroys. Right-click to run it again. |
| `beacon_5g` | 3,000,000 | Red-tinted beacon. Placed: a red particle tornado of thousands of dust particles while a 200-radius sphere is vacuumed up over 15 s; some of the blocks spiral up as flying entities. |
| `quantum_usb` | 10,000,000 | Right-click: a 500x500 column over the full world height is purged over 10 s, then you are teleported to the Void Realm. |

Commands (`/realityshop ...`): `balance`; and for testing, op level 2: `add <n>`, `set <n>`, `market`, `void`, `back`,
`rebuild` (re-builds the shop room).

Config `config/realityshop.json` (written on first start): `tickBudgetMs` (time per tick spent on mass removal),
`destroyBedrock`, `loadMissingChunks`, `protectedDimensions`, `pickaxeDropsItems`, `giveStarterPortal`,
`maxDerpChickens`, `derpChickenLifetimeSeconds`, `maxSuckedBlocks`, `hudEnabled`, `hudScale`, `particleDensity`.

## How the hard parts work

* **Destruction engine** (`destroy/RegionClearJob`). Regions are processed one chunk column at a time, nearest first.
  A 16x16x16 section that lies fully inside the region is swapped for an empty one in O(1) (biomes kept); only the
  sections cut by the region's surface are scanned block by block. Jobs share one per-tick time budget, so even the
  96-million-block USB purge cannot stall the server. By default only **loaded** chunks are touched, bedrock and
  protected dimensions are respected, block entities are cleaned up, nothing drops.
* **Light.** Bulk clears bypass `setBlockState`, so the job repairs light itself (section status, replacement light
  arrays, sky-surface map, rate-limited `checkBlock`). Vanilla never re-announces light arrays that were replaced
  wholesale, so when a job ends the touched chunks are re-sent *after* the light engine has caught up (a per-chunk
  barrier), a few per tick. A game test requires the result to equal what vanilla `setBlockState` produces.
* **Capability.** Fabric has no Forge-style capabilities; the balance is a persistent, copy-on-death **data attachment**
  (Fabric API) with a server facade (`ConsumedBlocks`) and a sync packet to the client.
* **Your 3D models.** The glTF/GLB files are shipped untouched and rendered by a small runtime loader (`client/gltf`):
  buffers/images external, data-URI or GLB-embedded, strided accessors, node hierarchy, full-resolution meshes (the
  chicken is 273k triangles, uploaded as-is), textures uploaded at their original size with mipmaps, and a custom core
  shader (`gltf_pbr`) doing base colour + normal map + metallic-roughness lit like vanilla entities.
* **HUD.** A generated stroke-font atlas + neon plate, uploaded mip-mapped, so the text stays crisp at any GUI scale.

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
| `./gradlew build` of the generated workspace | passes (final state, jar 19.8 MB incl. the two models) |
| `./gradlew runGametest` | **13/13 pass**: economy attachment, shop offers/costs, pedestal purchase, data-pack dimensions parse with the real codecs, shop room, box and sphere clears (exact counts, credit, protection), paced purge (bedrock survives), sky light equal to vanilla, pickaxe 3x3x3, kitty-phone pit, DerpChicken cap, all items/blocks registered |
| `tools/verify_server.sh` (real dedicated server) | 8/8 checks: mod loads, both dimensions exist and save, room builds, pedestals hold the right items, no errors |
| `tools/verify_client.sh` (real client, Xvfb + Mesa software GL, scripted tour, 12 screenshots) | joins a world and runs the tour; both glTF models load and render; HUD, hand/hotbar items, USB cuboid model, animated portal, pedestal text, Void Realm and the red beacon tornado were seen in screenshots; no shader/model/texture errors in the log |

Bugs this verification found and fixed: Fabric silently dropped glTF textures whose file names contain capitals; the
USB item model inherited `item/generated` and drew nothing; clients kept stale (dark) light after a big purge; held
phone/USB were too large.

**Not verified - please check on your machine:**

* The green **laser beams**. The first client run showed they never rendered (1.20.1 only draws its five built-in
  particle sheets, so my custom additive sheet was skipped); the fix (use the vanilla translucent sheet) compiles and is
  in this repo, but I did **not** get to look at it on screen. Run `tools/verify_client.sh <workspace>` and open
  `04_furnace_lasers_*.png`, or just place a Hacked RGB Furnace.
* A real GPU (only Mesa's software renderer was available), sound (no audio device; vanilla fallback sounds are mapped), and
  real multiplayer with several clients.
* The full 500x500 USB purge with a real player clicking the item (the engine is game-tested at smaller sizes with
  pacing, and the Void Realm teleport was exercised with a real player through `/realityshop void`).
* Performance with many DerpChickens: each is the full 273k-triangle model. They are distance-culled (64 blocks),
  capped (`maxDerpChickens`) and expire after 45 s by default.

## Things to know

* Minecraft 1.20.1 marks every world with more than the three vanilla dimensions as "experimental" and asks
  **"Worlds using Experimental Settings are not supported"** each time you open it. Click *I know what I'm doing!*
  (this is vanilla behaviour for data-pack dimensions, not an error).
* "8K": nothing caps texture size - glTF textures and override textures are uploaded at full size (your GPU's
  `GL_MAX_TEXTURE_SIZE` decides what is possible). The *generated* placeholder textures default to 256 px;
  use `--texture-size` or drop in your own.
* One UV set (`TEXCOORD_0`) drives every map; the two supplied models use identical sets (the loader logs a warning if a
  model does not). Skins/animations are not supported (neither model has any).
* Interpretations: "5x5" for the floppy is a 5x5x5 cube; the kitty phone pit is 50 wide, 50 deep and 20 tall
  (16 below to 3 above the clicked block); the "8K" look is about sharpness, not a literal 8192 px HUD texture.
* The request also contained a long prompt for a different mod ("HeistMod"). You chose RealityShop, so that mod was not built.
* `fabric.mod.json` points at `realityshop.mixins.json`, which is intentionally empty (no mixins were needed).

## Repository layout

```
build_mod.py              the generator (Java, JSON, shaders, textures, Gradle files, self-checks are all inside)
assets/models/            the two supplied models (CC-BY-4.0), copied byte for byte
assets/wrapper, CREDITS   embedded into build_mod.py by tools/embed_assets.py (Gradle wrapper + credits)
tools/verify_server.sh    dedicated-server boot check
tools/verify_client.sh    best-effort headless client tour with screenshots
tools/embed_assets.py     re-embeds assets/wrapper + CREDITS.txt into build_mod.py
```

## Credits (CC-BY-4.0, used unmodified)

* **"Chicken"** by joshuacaca973 - https://sketchfab.com/3d-models/chicken-59590091ec7647a4beae2e08acb1903a
* **"Retro mobile phone with heart icons on the scre"** by Violetforest -
  https://sketchfab.com/3d-models/retro-mobile-phone-with-heart-icons-on-the-scre-493bf5df4b764db8849c24f1a60ca672

Licence: <http://creativecommons.org/licenses/by/4.0/>. The credits are also written into the generated mod
(`fabric.mod.json` contributors, `CREDITS.txt`).
