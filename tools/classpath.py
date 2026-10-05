#!/usr/bin/env python3
"""Prints the library jars a Minecraft version needs, one path per line.

    python tools/classpath.py <minecraft-root> <version-id>

Reads <root>/versions/<id>/<id>.json the way a launcher does: it follows "inheritsFrom" (so a Fabric
profile such as 26.2-fabric0.19.3 yields the vanilla libraries plus the loader's), and honours the
per-library OS rules so only this platform's natives are listed. Used by build.sh for the compile
classpath and by run-preview.sh to start the game.
"""
import json
import os
import platform
import sys

OS_NAME = {"Windows": "windows", "Darwin": "osx", "Linux": "linux"}.get(platform.system(), "linux")


def allowed(rules):
    """Launcher rule evaluation: no rules = allowed; otherwise the last matching rule decides."""
    if not rules:
        return True
    verdict = False
    for rule in rules:
        os_rule = rule.get("os")
        if os_rule is None or os_rule.get("name") == OS_NAME:
            verdict = rule.get("action") == "allow"
    return verdict


def artifact_path(lib):
    path = lib.get("downloads", {}).get("artifact", {}).get("path")
    if path:
        return path
    # Fabric-profile entries carry only a Maven coordinate: group:name:version[:classifier]
    parts = lib["name"].split(":")
    group, name, version = parts[0], parts[1], parts[2]
    classifier = "-" + parts[3] if len(parts) > 3 else ""
    return f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}{classifier}.jar"


def libraries(root, version):
    with open(os.path.join(root, "versions", version, version + ".json"), encoding="utf-8") as handle:
        data = json.load(handle)
    result = libraries(root, data["inheritsFrom"]) if "inheritsFrom" in data else []
    for lib in data.get("libraries", []):
        if allowed(lib.get("rules")):
            result.append(os.path.join(root, "libraries", artifact_path(lib)).replace("\\", "/"))
    return result


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    seen = set()
    for jar in libraries(sys.argv[1], sys.argv[2]):
        if jar not in seen:
            seen.add(jar)
            print(jar)


if __name__ == "__main__":
    main()
