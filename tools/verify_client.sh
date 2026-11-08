#!/usr/bin/env bash
# BEST-EFFORT client smoke test: launches the REAL Fabric client headlessly (Xvfb + Mesa software OpenGL), auto-joins a
# throwaway singleplayer world, lets a data pack run a scripted tour (HUD, the six items in the hotbar and in the hand,
# DerpChickens, the Reality Gate being summoned / walked through / picked up, the Black Market hall, furnace lasers,
# beacon tornado, the Bedrock Realm) and takes screenshots at every step; at the end it types /cinematic into the chat.
#
# It proves that the client starts, the glTF models + PBR core shader load and render, the HUD textures load and nothing
# logs shader/model/texture errors. Whether the pictures LOOK right is for a human (or an AI) to judge from the
# screenshots; nothing here replaces playing the mod once on a GPU.
#
#   usage: tools/verify_client.sh <generated workspace dir> [screenshot dir]
#   needs: JDK 17, Xvfb, ImageMagick (`import`), Mesa software GL (libgl1-mesa-dri); the Minecraft assets (Loom downloads them)
#
# Exit code: 0 = every automatic check passed, 1 = a check failed, 2 = prerequisites missing (not a mod failure).
set -uo pipefail

WS="$(cd "${1:?usage: verify_client.sh <generated workspace dir> [screenshot dir]}" && pwd)"
RUN="$WS/run"
SHOTS="${2:-$RUN/screenshots}"
LOG="$RUN/client_smoke.log"
SAVE="rssmoke"
DISPLAY_NUM="${RS_DISPLAY:-77}"
START_TIMEOUT="${RS_START_TIMEOUT:-600}"     # seconds to wait for the client to reach the world (software GL is slow)

for tool in Xvfb import xdotool; do
  command -v "$tool" > /dev/null || { echo "SKIP  needs '$tool' (apt-get install xvfb imagemagick xdotool libgl1-mesa-dri)"; exit 2; }
done
mkdir -p "$RUN" "$SHOTS"
rm -f "$SHOTS"/*.png "$LOG"

# ---- 1. a pristine throwaway world, created by booting the real dedicated server once (flat world, stops by itself)
make_world() {
  rm -rf "$RUN/world"
  printf 'eula=true\n' > "$RUN/eula.txt"
  printf 'level-type=flat\ngenerate-structures=false\nonline-mode=false\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\ndifficulty=peaceful\n' > "$RUN/server.properties"
  local fifo gp
  fifo="$(mktemp -u)"
  mkfifo "$fifo"
  ( cd "$WS" && ./gradlew runServer --console=plain < "$fifo" > "$RUN/world_gen.log" 2>&1 ) &
  gp=$!
  exec 4> "$fifo"
  for _ in $(seq 1 300); do grep -Eq 'Done \([0-9.]+s\)! For help' "$RUN/world_gen.log" 2> /dev/null && break; sleep 1; done
  echo "stop" >&4
  exec 4>&-
  for _ in $(seq 1 60); do grep -Eq 'ThreadedAnvilChunkStorage: All dimensions are saved' "$RUN/world_gen.log" 2> /dev/null && break; sleep 1; done
  sleep 3
  kill "$gp" 2> /dev/null || true
  rm -f "$fifo"
}
echo "info  creating a pristine world with the dedicated server"
make_world
[ -f "$RUN/world/level.dat" ] || { echo "SKIP  could not create a world (see $RUN/world_gen.log)"; exit 2; }
rm -rf "$RUN/saves/$SAVE"
mkdir -p "$RUN/saves"
cp -r "$RUN/world" "$RUN/saves/$SAVE"
rm -f "$RUN/saves/$SAVE/session.lock"

# ---- 2. the scripted tour, as a data pack inside the save (found and enabled automatically on load)
PACK="$RUN/saves/$SAVE/datapacks/rssmoke"
rm -rf "$PACK"
mkdir -p "$PACK/data/minecraft/tags/functions" "$PACK/data/rssmoke/functions"
printf '{"pack":{"pack_format":15,"description":"RealityShop client smoke test"}}\n' > "$PACK/pack.mcmeta"
printf '{"values":["rssmoke:load"]}\n' > "$PACK/data/minecraft/tags/functions/load.json"
fn() { cat > "$PACK/data/rssmoke/functions/$1.mcfunction"; }
fn load <<'EOF'
scoreboard objectives add rsb dummy
schedule function rssmoke:wait 20t
EOF
fn wait <<'EOF'
execute if entity @a run schedule function rssmoke:s1 400t
execute unless entity @a run schedule function rssmoke:wait 20t
EOF
fn s1 <<'EOF'
tellraw @a "RS_SMOKE hud"
execute as @a run realityshop set 1234567
clear @a
give @a realityshop:cursed_kitty_phone
give @a realityshop:glitch_pickaxe
give @a realityshop:glitchy_floppy
give @a realityshop:quantum_usb
give @a realityshop:hacked_rgb_furnace
give @a realityshop:beacon_5g
give @a realityshop:black_market_portal
give @a realityshop:derp_chicken_spawn_egg
execute as @a at @s run tp @s ~ ~ ~ 0 8
schedule function rssmoke:s2 200t
EOF
fn s2 <<'EOF'
tellraw @a "RS_SMOKE chicken"
execute as @a at @s run summon realityshop:derp_chicken ~-1.5 ~ ~2.6
execute as @a at @s run summon realityshop:derp_chicken ~1.8 ~ ~3.4
schedule function rssmoke:s3 200t
EOF
fn s3 <<'EOF'
tellraw @a "RS_SMOKE gate"
execute as @p run realityshop gate
schedule function rssmoke:s3b 200t
EOF
fn s3b <<'EOF'
tellraw @a "RS_SMOKE gate_side"
execute as @a at @s run tp @s ~-2.2 ~ ~1.2 -38 6
schedule function rssmoke:s3c 100t
EOF
fn s3c <<'EOF'
execute as @a at @s run tp @s ~2.2 ~ ~-1.2 0 8
schedule function rssmoke:s4 60t
EOF
fn s4 <<'EOF'
tellraw @a "RS_SMOKE enter"
execute as @a at @s run tp @s ~ ~ ~4
schedule function rssmoke:s5 220t
EOF
fn s5 <<'EOF'
tellraw @a "RS_SMOKE hall_gate"
execute as @a at @s run tp @s ~ ~ ~ 0 4
schedule function rssmoke:s6 120t
EOF
fn s6 <<'EOF'
tellraw @a "RS_SMOKE hall"
execute as @a at @s run tp @s ~ ~ ~ 180 6
schedule function rssmoke:s6b 120t
EOF
fn s6b <<'EOF'
tellraw @a "RS_SMOKE dome"
execute as @a at @s run tp @s ~ ~ ~ 180 -48
schedule function rssmoke:s6c 120t
EOF
fn s6c <<'EOF'
tellraw @a "RS_SMOKE pedestal"
execute in realityshop:black_market as @a run tp @s 3.8 65 8.6 -42 -7
schedule function rssmoke:s7 120t
EOF
fn s7 <<'EOF'
tellraw @a "RS_SMOKE leave"
execute in realityshop:black_market as @a run tp @s 0.5 65 27.5 0 0
schedule function rssmoke:s8 240t
EOF
fn s8 <<'EOF'
tellraw @a "RS_SMOKE pickup"
execute as @a at @s run tp @s ~ ~ ~ 0 6
execute as @a at @s run data modify block ~ ~ ~2 closer set from entity @s UUID
execute as @a at @s store result block ~ ~ ~2 closingAt long 1 run time query gametime
schedule function rssmoke:s8b 90t
EOF
fn s8b <<'EOF'
execute store result score #c rsb run clear @a realityshop:black_market_portal 0
execute if score #c rsb matches 2.. run tellraw @a "RS_SMOKE pickup_ok"
execute unless score #c rsb matches 2.. run tellraw @a "RS_SMOKE pickup_missing"
schedule function rssmoke:s8c 40t
EOF
fn s8c <<'EOF'
tellraw @a "RS_SMOKE floppy"
execute as @a at @s run tp @s ~ ~ ~ 0 16
execute as @a at @s run summon realityshop:floppy_projectile ~ ~3 ~4 {Motion:[0.0,-0.8,0.0]}
schedule function rssmoke:s9 200t
EOF
fn s9 <<'EOF'
tellraw @a "RS_SMOKE usb"
item replace entity @a weapon.mainhand with realityshop:quantum_usb
schedule function rssmoke:s10 140t
EOF
fn s10 <<'EOF'
tellraw @a "RS_SMOKE furnace"
execute as @a at @s run setblock ~ ~ ~8 realityshop:hacked_rgb_furnace
execute as @a at @s store result score #z rsb run data get entity @s Pos[2]
scoreboard players add #z rsb 1
execute as @a at @s store result block ~ ~ ~8 frontStart int 1 run scoreboard players get #z rsb
execute as @a at @s run data merge block ~ ~ ~8 {running:1b,frontDir:3}
execute as @a at @s run tp @s ~ ~ ~ 0 -8
schedule function rssmoke:s11 300t
EOF
fn s11 <<'EOF'
execute as @a run tp @s 600 -60 600 0 0
schedule function rssmoke:s11b 160t
EOF
fn s11b <<'EOF'
tellraw @a "RS_SMOKE beacon"
execute as @a at @s run setblock ~ ~2 ~24 realityshop:beacon_5g
execute as @a at @s run data merge block ~ ~2 ~24 {running:1b}
execute as @a at @s run tp @s ~ ~ ~ 0 -14
schedule function rssmoke:s12 360t
EOF
fn s12 <<'EOF'
tellraw @a "RS_SMOKE bedrock"
execute as @p run realityshop bedrock
schedule function rssmoke:s13 260t
EOF
fn s13 <<'EOF'
tellraw @a "RS_SMOKE done"
EOF

# ---- 3. client options: no onboarding screens, don't pause when the (window-manager-less) window is unfocused
cat > "$RUN/options.txt" <<'EOF'
onboardAccessibility:false
pauseOnLostFocus:false
tutorialStep:none
joinedFirstServer:true
skipMultiplayerWarning:true
renderDistance:6
simulationDistance:6
maxFps:30
guiScale:2
EOF

# ---- 4. start Xvfb + the client
Xvfb ":$DISPLAY_NUM" -screen 0 1280x720x24 +extension GLX +render -noreset > /dev/null 2>&1 &
XVFB_PID=$!
sleep 2
export DISPLAY=":$DISPLAY_NUM" LIBGL_ALWAYS_SOFTWARE=1 JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Xmx2g -Drealityshop.cinematicSeconds=3.0"
setsid bash -c "cd '$WS' && ./gradlew runClient -PrsQuickPlay=$SAVE --args='--width 1280 --height 720' --console=plain > '$LOG' 2>&1" &
LAUNCHER=$!

cleanup() {
  kill -TERM -- "-$LAUNCHER" 2> /dev/null || true
  pkill -f "net.fabricmc.devlaunchinjector.Main" 2> /dev/null || true
  sleep 2
  kill "$XVFB_PID" 2> /dev/null || true
}
trap 'kill "${PROMPT_PID:-0}" 2> /dev/null; cleanup' EXIT

# Vanilla 1.20.1 marks every world with more than the three vanilla dimensions (i.e. every world with this mod's data-driven
# dimensions) as "experimental" and asks "Worlds using Experimental Settings are not supported" each time it is opened.
# Click "I know what I'm doing!" (1280x720, GUI scale 2) until the integrated server starts.
confirm_experimental_prompt() {
  for _ in $(seq 1 300); do
    grep -q "Starting integrated minecraft server" "$LOG" 2> /dev/null && return 0
    kill -0 "$LAUNCHER" 2> /dev/null || return 1
    if grep -q "Loaded .* advancements" "$LOG" 2> /dev/null; then
      sleep 3
      xdotool mousemove 780 273 && sleep 0.5 && xdotool mousemove 800 273 && sleep 0.5 && xdotool click 1
      sleep 6
    fi
    sleep 1
  done
  return 1
}

wait_marker() {   # wait_marker <name> <seconds>
  for _ in $(seq 1 "$2"); do
    grep -q "RS_SMOKE $1" "$LOG" 2> /dev/null && return 0
    kill -0 "$LAUNCHER" 2> /dev/null || return 1
    sleep 1
  done
  return 1
}
shot() {          # shot <file> <seconds to wait first>
  sleep "$2"
  import -window root "$SHOTS/$1.png" 2> /dev/null && echo "shot  $1.png"
}

fail=0
check() {         # check <description> <command...>
  local what="$1"; shift
  if "$@"; then echo "PASS  $what"; else echo "FAIL  $what"; fail=1; fi
}

confirm_experimental_prompt &
PROMPT_PID=$!
if wait_marker hud "$START_TIMEOUT"; then
  shot 01_hud_and_hand_phone 8
  wait_marker chicken 120 && shot 02_chickens 5
  if wait_marker gate 120; then shot 03_gate_summon_a 0.05; shot 03_gate_summon_b 0.05; shot 03_gate_summon_c 0.1; shot 03_gate_summon_d 0.3; shot 03_gate_summon_e 0.6; shot 03_gate_summon_f 1.0; shot 03_gate_open 3; fi
  wait_marker gate_side 120 && shot 03_gate_side 2.5
  if wait_marker enter 120; then
    shot 04_enter_a 0.05; shot 04_enter_b 0.05; shot 04_enter_c 0.05; shot 04_enter_d 0.05; shot 04_enter_e 0.05
    shot 04_arrive_a 0.1; shot 04_arrive_b 0.1; shot 04_arrive_c 0.3; shot 04_arrive_d 1.0; shot 04_arrive_e 3
  fi
  wait_marker hall_gate 120 && shot 05_hall_exit_gate 3
  wait_marker hall 120 && { shot 06_hall_a 3; shot 06_hall_b 3; }
  wait_marker dome 120 && shot 07_dome 3
  wait_marker pedestal 120 && { shot 07_pedestal_a 2.5; shot 07_pedestal_b 1; }
  if wait_marker leave 120; then shot 08_leave_a 0.05; shot 08_leave_b 0.05; shot 08_leave_c 0.1; shot 08_back_a 1.2; shot 08_back_b 2.5; fi
  if wait_marker pickup 120; then shot 09_pickup_a 0.3; shot 09_pickup_b 0.25; shot 09_pickup_c 0.25; shot 09_pickup_d 0.4; shot 09_pickup_e 1.5; fi
  wait_marker floppy 120 && { shot 09b_floppy_a 0.4; shot 09b_floppy_b 0.6; shot 09b_floppy_c 2.5; }
  wait_marker usb 120 && shot 10_usb_in_hand 3
  if wait_marker furnace 120; then shot 11_furnace_a 3; shot 11_furnace_b 4; fi
  if wait_marker beacon 120; then shot 12_beacon_a 6; shot 12_beacon_b 14; shot 12_beacon_c 20; fi
  if wait_marker bedrock 120; then shot 13_bedrock_a 0.3; shot 13_bedrock_b 0.5; shot 13_bedrock_c 3; shot 13_bedrock_d 6; fi
  wait_marker done 120
  # /cinematic, typed into the real chat: the orbit camera around every item with the player hidden (3 s per item here)
  sleep 4; xdotool mousemove 640 400; sleep 1
  xdotool key slash; sleep 1; xdotool type --delay 90 "cinematic"; sleep 1; xdotool key Return
  for i in $(seq 1 24); do shot "14_cinematic_$(printf %02d $i)" 1.0; done
  for _ in $(seq 1 60); do grep -q "\[cinematic\] stopped" "$LOG" 2> /dev/null && break; sleep 1; done
  reached=0
else
  reached=1
  echo "FAIL  the client did not reach the world within ${START_TIMEOUT}s (see $LOG)"
  tail -25 "$LOG" 2> /dev/null | cut -c1-200
  fail=1
fi

# ---- 5. checks on the client log + screenshots
if [ "$reached" = 0 ]; then
  check "client joined the world and the scripted tour ran to the end" grep -q "RS_SMOKE done" "$LOG"
  check "picking the gate up returned the item to the inventory" grep -q "RS_SMOKE pickup_ok" "$LOG"
  check "glTF cursed_kitty_phone parsed and uploaded" grep -q "glTF 'cursed_kitty_phone' loaded" "$LOG"
  check "glTF derp_chicken parsed and uploaded" grep -q "glTF 'derp_chicken' loaded" "$LOG"
  noerr() { ! grep -Ei "gltf_pbr core shader is not loaded|Could not load glTF|Could not load HUD|Couldn't load shader|Failed to load shader|Shader realityshop:gltf_pbr could not|GLSL|Exception in thread|Invalid path in mod resource-pack|\[ERROR\].*(realityshop|gltf)" "$LOG" | head -8 | grep -q .; }
  check "no shader / glTF / HUD-texture errors in the client log" noerr
  check "/cinematic ran to the end (camera mixin, stage shader, title cards)" grep -q "\[cinematic\] stopped" "$LOG"
  lit=0
  for f in "$SHOTS"/*.png; do
    [ -f "$f" ] || continue
    mean="$(convert "$f" -colorspace Gray -format '%[fx:mean]' info: 2> /dev/null || echo 0)"
    case "$(basename "$f")" in
      14_cinematic_*)   # the cinematic fades through black between two items: a dark frame is expected there
        if awk -v m="$mean" 'BEGIN{exit !(m > 0.03)}'; then lit=$((lit + 1)); fi ;;
      *) if awk -v m="$mean" 'BEGIN{exit !(m > 0.03)}'; then :; else echo "FAIL  $(basename "$f") is blank (mean $mean)"; fail=1; fi ;;
    esac
  done
  check "most /cinematic frames show the stage (not black): $lit of 24" test "$lit" -ge 14
  echo "info  screenshots: $(ls "$SHOTS" | wc -l) in $SHOTS"
fi
echo "log: $LOG"
exit $fail
