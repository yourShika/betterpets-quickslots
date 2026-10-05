#!/usr/bin/env bash
# Builds the mod without Gradle/Loom.
#
# Minecraft 26.x ships with real class names and Fabric mods for it declare
# "Fabric-Mapping-Namespace: official", so nothing is remapped at runtime. That
# means the mod can be compiled straight against the game jar of an existing
# installation and its classes shipped as-is.
#
# Needs: a JDK 25, a launcher directory that has Minecraft and the Fabric loader
# installed, and a Fabric API jar for that Minecraft version. Override the
# defaults below through the environment, e.g.
#
#   MCROOT=~/.minecraft FABRIC_API=~/Downloads/fabric-api-0.159.0+26.2.jar bash build.sh
#
# Everything is driven from the project directory with relative paths, and the
# classpath goes through an argument file, because the absolute paths contain
# spaces and get far too long for a Windows command line.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

MC_VERSION="${MC_VERSION:-26.2}"
MCROOT="${MCROOT:-D:/.minecraftx}"
JDK="${JDK:-C:/Program Files/Java/jdk-25}"
PYTHON="${PYTHON:-python}"

MOD_VERSION="$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' src/main/resources/fabric.mod.json | head -1)"
FABRIC_LOADER="${FABRIC_LOADER:-$(ls "$MCROOT"/libraries/net/fabricmc/fabric-loader/*/fabric-loader-*.jar | sort -V | tail -1)}"
FABRIC_API="${FABRIC_API:-$(ls "$MCROOT"/instances/*/mods/fabric-api-*+"$MC_VERSION".jar 2>/dev/null | sort -V | tail -1)}"
GAME_JAR="$MCROOT/versions/$MC_VERSION/$MC_VERSION.jar"

[ -f "$GAME_JAR" ] || { echo "Minecraft $MC_VERSION not found at $GAME_JAR (set MCROOT / MC_VERSION)"; exit 1; }
[ -f "$FABRIC_LOADER" ] || { echo "Fabric loader jar not found (set FABRIC_LOADER)"; exit 1; }
[ -f "$FABRIC_API" ] || { echo "No Fabric API jar for $MC_VERSION found (set FABRIC_API)"; exit 1; }

BUILD="build"
CLASSES="$BUILD/classes"
DEPS="$BUILD/deps"
OUT="betterpets-quickslots-$MOD_VERSION.jar"

rm -rf "$BUILD"
mkdir -p "$CLASSES" "$DEPS"

# Fabric API ships as a container: the modules javac needs are nested jars.
( cd "$DEPS" && unzip -o -q "$FABRIC_API" 'META-INF/jars/*.jar' )

# Compile classpath: the game, the loader, the Fabric API modules, and the libraries the game's own
# classes reference (javac needs them to read the game jar at all).
{
  printf '%s\n' "$GAME_JAR" "$FABRIC_LOADER"
  ls "$DEPS"/META-INF/jars/*.jar
  "$PYTHON" tools/classpath.py "$MCROOT" "$MC_VERSION"
} | tr -d '\r' > "$BUILD/classpath.txt"

# One quoted -cp entry in an argument file; javac accepts forward slashes on Windows too.
printf -- '-cp "%s"\n' "$(paste -sd ';' "$BUILD/classpath.txt")" > "$BUILD/javac-args.txt"
find src/main/java -name '*.java' > "$BUILD/sources.txt"

echo "Compiling against Minecraft $MC_VERSION, $(basename "$FABRIC_API")..."
"$JDK/bin/javac" -encoding UTF-8 --release 25 -Xlint:all,-processing,-classfile,-serial \
  -d "$CLASSES" @"$BUILD/javac-args.txt" @"$BUILD/sources.txt"

echo "Packaging..."
cp -r src/main/resources/. "$CLASSES/"
printf 'Fabric-Mapping-Namespace: official\nFabric-Minecraft-Version: %s\n' "$MC_VERSION" > "$BUILD/manifest.txt"
"$JDK/bin/jar" --create --file "$OUT" --manifest "$BUILD/manifest.txt" -C "$CLASSES" .

echo "Built $OUT"
