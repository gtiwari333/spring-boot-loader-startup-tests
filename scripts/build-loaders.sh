#!/usr/bin/env bash
# Produces the two loader jars that are compared:
#   work/loaders/rc2.jar  spring-boot-loader 4.2.0-RC2 from Maven Central   (before)
#   work/loaders/pr.jar   loader/spring-boot-loader built from your clone   (after)
#
# Usage: scripts/build-loaders.sh <path-to-spring-boot-clone>
#
# The clone is built as-is: whatever branch is checked out, including uncommitted changes.
# What was built is recorded in work/loaders/pr.txt.
set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <path-to-spring-boot-clone>" >&2; exit 1; }
SPRING_BOOT_DIR="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOADERS="$ROOT/work/loaders"
RC_VERSION="4.2.0-RC2"

[[ -x "$SPRING_BOOT_DIR/gradlew" && -d "$SPRING_BOOT_DIR/loader/spring-boot-loader" ]] \
	|| { echo "ERROR: '$SPRING_BOOT_DIR' is not a spring-boot clone" >&2; exit 1; }
SPRING_BOOT_DIR="$(cd "$SPRING_BOOT_DIR" && pwd)"
mkdir -p "$LOADERS"

echo "==> Downloading spring-boot-loader $RC_VERSION"
curl -fsSL -o "$LOADERS/rc2.jar" \
	"https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-loader/$RC_VERSION/spring-boot-loader-$RC_VERSION.jar"

echo "==> Building loader/spring-boot-loader in $SPRING_BOOT_DIR"
(cd "$SPRING_BOOT_DIR" && ./gradlew -q :loader:spring-boot-loader:jar)
cp "$(ls "$SPRING_BOOT_DIR"/loader/spring-boot-loader/build/libs/spring-boot-loader-*.jar \
	| grep -Ev -- '-(sources|javadoc|test-fixtures|plain)\.jar$' | head -1)" "$LOADERS/pr.jar"

# Record which code went into pr.jar
{
	git -C "$SPRING_BOOT_DIR" log -1 --format='%h %s (%D)'
	if [[ -n "$(git -C "$SPRING_BOOT_DIR" status --porcelain -- loader/spring-boot-loader)" ]]; then
		echo "with uncommitted changes in loader/spring-boot-loader"
	fi
} > "$LOADERS/pr.txt"
echo "==> pr.jar built from: $(paste -sd ' ' "$LOADERS/pr.txt")"
