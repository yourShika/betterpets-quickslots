#!/usr/bin/env bash
# Builds the mod without Gradle/Loom.
#
# Minecraft 26.x ships with real class names and Fabric mods for it declare
# "Fabric-Mapping-Namespace: official", so nothing is remapped at runtime. That
# means the mod can be compiled straight against the game jar of an existing
# installation and its classes shipped as-is.
#
# Needs: a JDK 25, a launcher directory that has Minecraft and the Fabric loader
# installed (as a launcher profile such as 26.2-fabric0.19.3), and a Fabric API
# jar for that Minecraft version. Override the defaults below through the
# environment, e.g.
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
# The launcher profile that combines this Minecraft version with a Fabric loader, e.g. 26.2-fabric0.19.3.
# Its libraries are the game's plus the loader's (the loader itself, Mixin, ASM).
PROFILE="${PROFILE:-$(ls -d "$MCROOT"/versions/"$MC_VERSION"-fabric* 2>/dev/null | sort -V | tail -1 | xargs -r basename)}"
FABRIC_API="${FABRIC_API:-$(ls "$MCROOT"/instances/*/mods/fabric-api-*+"$MC_VERSION".jar 2>/dev/null | sort -V | tail -1)}"
GAME_JAR="$MCROOT/versions/$MC_VERSION/$MC_VERSION.jar"

[ -f "$GAME_JAR" ] || { echo "Minecraft $MC_VERSION not found at $GAME_JAR (set MCROOT / MC_VERSION)"; exit 1; }
[ -n "$PROFILE" ] || { echo "No Fabric profile for $MC_VERSION found in $MCROOT/versions (install the Fabric loader, or set PROFILE)"; exit 1; }
[ -f "$FABRIC_API" ] || { echo "No Fabric API jar for $MC_VERSION found (set FABRIC_API)"; exit 1; }

BUILD="build"
CLASSES="$BUILD/classes"
STUBS="$BUILD/stubs"
DEPS="$BUILD/deps"
OUT="betterpets-quickslots-$MOD_VERSION.jar"

rm -rf "$BUILD"
mkdir -p "$CLASSES" "$STUBS" "$DEPS"

# Fabric API ships as a container: the modules javac needs are nested jars.
( cd "$DEPS" && unzip -o -q "$FABRIC_API" 'META-INF/jars/*.jar' )

# Compile classpath: the game, the Fabric API modules, and the libraries of the Fabric profile - the
# loader and Mixin for the mod itself, and everything the game's own classes reference (javac needs
# those to read the game jar at all).
{
  printf '%s\n' "$GAME_JAR"
  ls "$DEPS"/META-INF/jars/*.jar
  "$PYTHON" tools/classpath.py "$MCROOT" "$PROFILE"
} | tr -d '\r' > "$BUILD/classpath.txt"

# One quoted -cp entry in an argument file; javac accepts forward slashes on Windows too.
printf -- '-cp "%s"\n' "$(paste -sd ';' "$BUILD/classpath.txt")" > "$BUILD/javac-args.txt"
printf -- '-cp "%s;%s"\n' "$STUBS" "$(paste -sd ';' "$BUILD/classpath.txt")" > "$BUILD/javac-args-mod.txt"
find stubs -name '*.java' > "$BUILD/stub-sources.txt"
find src/main/java -name '*.java' > "$BUILD/sources.txt"

# -proc:none: the Mixin jar on the classpath brings an annotation processor that is of no use here
# (it writes name mappings, and this version of the game has nothing to map).
JAVAC_FLAGS=(-encoding UTF-8 --release 25 -proc:none -Xlint:all,-processing,-classfile,-serial)

echo "Compiling against Minecraft $MC_VERSION ($PROFILE), $(basename "$FABRIC_API")..."
# Mod Menu's two API interfaces, compile-only: they make the "modmenu" entrypoint buildable without a
# Mod Menu jar and are not packaged.
"$JDK/bin/javac" "${JAVAC_FLAGS[@]}" -d "$STUBS" @"$BUILD/javac-args.txt" @"$BUILD/stub-sources.txt"
"$JDK/bin/javac" "${JAVAC_FLAGS[@]}" -d "$CLASSES" @"$BUILD/javac-args-mod.txt" @"$BUILD/sources.txt"

echo "Packaging..."
cp -r src/main/resources/. "$CLASSES/"
printf 'Fabric-Mapping-Namespace: official\nFabric-Minecraft-Version: %s\n' "$MC_VERSION" > "$BUILD/manifest.txt"
"$JDK/bin/jar" --create --file "$OUT" --manifest "$BUILD/manifest.txt" -C "$CLASSES" .

echo "Built $OUT"
