#!/usr/bin/env bash
# Exercises the mod without a Better Pets server: screenshots plus a small end-to-end check.
#
# Starts the real game (Minecraft + Fabric loader + Fabric API + this mod) in the throw-away game
# directory run/ and generates a creative test world. On that world's integrated server a stand-in
# for the plugin answers on the real channel with the pets from tools/preview-pets.tsv; the mod then
# has to shake hands, show its screen, park a pet and switch pets all by itself. Takes about a
# minute, needs no input, and exits non-zero if a check fails.
# Handy for working on the layout: build, run this, look at run/screenshots/.
#
#   bash build.sh && bash run-preview.sh
#
# Same environment overrides as build.sh, plus:
#   GUI_SCALE / LANGUAGE / WIDTH / HEIGHT   what is rendered
#   EXTRA_MODS   more mod jars to put into the test instance, separated by commas (with Mod Menu among
#                them the run also checks that its configure button leads to the settings screen)
#   HIDDEN=1     Windows only: run the game on a desktop of its own, so no window appears and nothing
#                takes the focus away from what you are doing
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

MC_VERSION="${MC_VERSION:-26.2}"
MCROOT="${MCROOT:-D:/.minecraftx}"
JDK="${JDK:-C:/Program Files/Java/jdk-25}"
PYTHON="${PYTHON:-python}"
GUI_SCALE="${GUI_SCALE:-3}"
LANGUAGE="${LANGUAGE:-en_us}"
WIDTH="${WIDTH:-1280}"
HEIGHT="${HEIGHT:-720}"

MOD_VERSION="$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' src/main/resources/fabric.mod.json | head -1)"
MOD_JAR="betterpets-quickslots-$MOD_VERSION.jar"
FABRIC_API="${FABRIC_API:-$(ls "$MCROOT"/instances/*/mods/fabric-api-*+"$MC_VERSION".jar 2>/dev/null | sort -V | tail -1)}"
# The launcher profile that combines this Minecraft version with a Fabric loader, e.g. 26.2-fabric0.19.3.
PROFILE="${PROFILE:-$(ls -d "$MCROOT"/versions/"$MC_VERSION"-fabric* | sort -V | tail -1 | xargs basename)}"
ASSET_INDEX="$(sed -n 's/.*"assets": *"\([^"]*\)".*/\1/p' "$MCROOT/versions/$MC_VERSION/$MC_VERSION.json" | head -1)"

[ -f "$MOD_JAR" ] || { echo "$MOD_JAR not found -- run build.sh first"; exit 1; }
[ -f "$FABRIC_API" ] || { echo "No Fabric API jar for $MC_VERSION found (set FABRIC_API)"; exit 1; }

RUN="run"
# The preview creates its test world anew each time, so the old one has to go.
rm -rf "$RUN/mods" "$RUN/screenshots" "$RUN/saves" "$RUN/preview-report.txt"
mkdir -p "$RUN/mods" "$RUN/natives"
cp "$MOD_JAR" "$FABRIC_API" "$RUN/mods/"
if [ -n "${EXTRA_MODS:-}" ]; then
  IFS=',' read -r -a extra_mods <<< "$EXTRA_MODS"
  for extra in "${extra_mods[@]}"; do cp "$extra" "$RUN/mods/"; done
fi
# Skip the first-start prompts, stay silent and windowed.
cat > "$RUN/options.txt" <<EOF
onboardAccessibility:false
skipMultiplayerWarning:true
tutorialStep:none
narrator:0
fullscreen:false
pauseOnLostFocus:false
soundCategory_master:0.0
guiScale:$GUI_SCALE
lang:$LANGUAGE
EOF

{
  "$PYTHON" tools/classpath.py "$MCROOT" "$PROFILE"
  printf '%s\n' "$MCROOT/versions/$MC_VERSION/$MC_VERSION.jar"
} | tr -d '\r' > "$RUN/classpath.txt"
printf -- '-cp "%s"\n' "$(paste -sd ';' "$RUN/classpath.txt")" > "$RUN/java-args.txt"

GAME_ARGS=(-Xmx2G
  --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED
  -Djava.library.path=natives/java -Djna.tmpdir=natives/jna
  -Dorg.lwjgl.system.SharedLibraryExtractPath=natives/lwjgl -Dio.netty.native.workdir=natives/netty
  -Dbetterpets.quickslots.preview=../tools/preview-pets.tsv
  @java-args.txt net.fabricmc.loader.impl.launch.knot.KnotClient
  --username Preview --version "$PROFILE" --gameDir .
  --assetsDir "$MCROOT/assets" --assetIndex "$ASSET_INDEX"
  --uuid 00000000-0000-0000-0000-000000000000 --accessToken 0 --clientId 0 --xuid 0
  --versionType release --width "$WIDTH" --height "$HEIGHT")

echo "Starting Minecraft $MC_VERSION ($PROFILE) with $MOD_JAR ..."
cd "$RUN"
# The game's own exit code says nothing useful here; the report written by the preview does.
if [ "${HIDDEN:-0}" = "1" ]; then
  # On a Windows desktop of its own (see tools/run-hidden.ps1). The command line goes through a file:
  # every argument that holds a space is quoted for cmd.exe there.
  {
    printf 'cmd.exe /c ""%s"' "$(cygpath -w "$JDK/bin/java.exe")"
    for arg in "${GAME_ARGS[@]}"; do
      case "$arg" in *' '*) printf ' "%s"' "$arg" ;; *) printf ' %s' "$arg" ;; esac
    done
    printf ' > game.log 2>&1"'
  } > hidden-command.txt
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$HERE/tools/run-hidden.ps1")" \
    -WorkDir "$(pwd -W)" -CommandFile hidden-command.txt || true
else
  "$JDK/bin/java" "${GAME_ARGS[@]}" > game.log 2>&1 || true
fi
cd "$HERE"

if [ ! -f "$RUN/preview-report.txt" ]; then
  echo "The preview did not finish -- see $RUN/game.log and $RUN/logs/latest.log"
  exit 1
fi
cat "$RUN/preview-report.txt"
echo "Screenshots are in $RUN/screenshots/"
! grep -q '^FAIL' "$RUN/preview-report.txt"
