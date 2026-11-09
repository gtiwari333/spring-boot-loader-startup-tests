#!/usr/bin/env bash
# Builds the sample fat jar once (spring-boot-maven-plugin 4.2.0-RC2) and makes one copy per
# loader, with org/springframework/boot/loader/** replaced by that loader's classes:
#   work/apps/app-rc2.jar   work/apps/app-pr.jar
# Everything else in the jar is identical, so the only difference is the loader code.
#
# Usage: scripts/build-apps.sh [pom]   pom in sample-app/, default pom.xml (e.g. pom-big.xml)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/work"
POM="${1:-pom.xml}"
[[ -f "$ROOT/sample-app/$POM" ]] || { echo "ERROR: sample-app/$POM not found" >&2; exit 1; }
mkdir -p "$WORK/apps"

echo "==> Building sample app from sample-app/$POM"
(cd "$ROOT/sample-app" && mvn -q -B -f "$POM" -DskipTests clean package)
echo "$POM" > "$WORK/apps/pom.txt"
ORIGINAL="$ROOT/sample-app/target/loader-startup-app.jar"

for variant in rc2 pr; do
	out="$WORK/apps/app-$variant.jar"
	tmp="$(mktemp -d)"
	unzip -q "$WORK/loaders/$variant.jar" 'org/springframework/boot/loader/*' -d "$tmp"
	cp "$ORIGINAL" "$out"
	zip -q -d "$out" 'org/springframework/boot/loader/*' >/dev/null
	(cd "$tmp" && zip -q -r -D "$out" org)
	rm -rf "$tmp"
	echo "==> $out"
done
