#!/usr/bin/env bash
# Boots a REAL dedicated Fabric server with the generated RealityShop mod and checks it through console commands.
#
# The vanilla GameTest server builds its world from an empty dimension registry (so data-pack dimensions never exist
# there); this script covers what GameTests cannot: the BlackMarket / Bedrock Realm dimensions actually load, the hall is
# built into them, and the mod's commands work on a dedicated server.
#
#   usage: tools/verify_server.sh <generated workspace dir>      (needs JDK 17 on PATH / JAVA_HOME)
#
# Note: a development server needs eula=true; this script writes it into <workspace>/run (you accept the Minecraft EULA).
set -uo pipefail

WS="$(cd "${1:?usage: verify_server.sh <generated workspace dir>}" && pwd)"
RUN="$WS/run"
LOG="$RUN/verify.log"
mkdir -p "$RUN"
printf 'eula=true\n' > "$RUN/eula.txt"
cat > "$RUN/server.properties" <<'EOF'
level-type=flat
generate-structures=false
online-mode=false
view-distance=4
simulation-distance=4
spawn-protection=0
enable-command-block=true
EOF
rm -rf "$RUN/world" "$LOG"

FIFO="$(mktemp -u)"
mkfifo "$FIFO"
( cd "$WS" && ./gradlew runServer --console=plain < "$FIFO" > "$LOG" 2>&1 ) &
GRADLE_PID=$!
exec 3> "$FIFO"

wait_for() {   # wait_for <regex> <seconds>
  for _ in $(seq 1 "$2"); do
    grep -Eq "$1" "$LOG" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}
fail=0
check() {      # check <description> <regex>
  if grep -Eq "$2" "$LOG"; then echo "PASS  $1"; else echo "FAIL  $1   (expected /$2/)"; fail=1; fi
}

if ! wait_for 'Done \([0-9.]+s\)! For help' 240; then
  echo "FAIL  server did not finish starting"; tail -30 "$LOG"; exec 3>&-; kill "$GRADLE_PID" 2>/dev/null; exit 1
fi
check "mod loaded on the dedicated server"            'RealityShop ready'

send() { echo "$1" >&3; sleep "${2:-3}"; }
# nobody is online, so keep the chunks we poke at loaded
send "execute in realityshop:black_market run forceload add -32 -32 32 32" 6
send "execute in realityshop:bedrock_realm run forceload add -16 -16 16 16" 4
send "realityshop rebuild" 12
send "execute in realityshop:black_market run data get block 7 65 12 offer" 3
send "execute in realityshop:black_market run data get block -7 65 12 offer" 3
send "execute in realityshop:black_market run data get block 0 65 27 permanent" 3
send "execute in realityshop:black_market run setblock 0 71 0 minecraft:crying_obsidian" 3
send "execute in realityshop:bedrock_realm run setblock 0 9 0 minecraft:obsidian" 3
send "stop" 2
exec 3>&-
wait_for 'ThreadedAnvilChunkStorage: All dimensions are saved|Stopping server' 60 || true
sleep 3
kill "$GRADLE_PID" 2>/dev/null || true

check "Black Market room rebuilt (admin command)"      'Black Market room rebuilt'
check "pedestal at 7 65 12 sells the Glitch Pickaxe"   'has the following block data: "glitch_pickaxe"'
check "pedestal at -7 65 12 sells the Quantum USB"     'has the following block data: "quantum_usb"'
check "exit gate at 0 65 27 is a permanent gate"           'has the following block data: 1b'
check "setblock works inside the Bedrock Realm"           'Changed the block at 0, 9, 0'
check "Black Market dimension exists (saved on stop)"  "ServerLevel\\[world\\]'/realityshop:black_market"
check "Bedrock Realm dimension exists (saved on stop)"    "ServerLevel\\[world\\]'/realityshop:bedrock_realm"
if grep -Eq "Unknown dimension|Unknown or incomplete command" <(grep -E "realityshop:(black_market|bedrock_realm)" "$LOG"); then
  echo "FAIL  a RealityShop dimension is unknown to the server"; fail=1
fi
if grep -E "\[(ERROR|FATAL)\]|/ERROR\]" "$LOG" | grep -Ei "realityshop|mixin|Exception" | grep -v "nonexistent" | head -5 | grep -q .; then
  echo "FAIL  errors in the server log:"; grep -E "/ERROR\]|/FATAL\]" "$LOG" | grep -Ei "realityshop|mixin|Exception" | grep -v nonexistent | head -8; fail=1
else
  echo "PASS  no RealityShop/mixin errors in the server log"
fi
echo "log: $LOG"
exit $fail
