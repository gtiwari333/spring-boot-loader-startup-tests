#!/usr/bin/env bash
# Builds both loaders and app variants, then runs the benchmark.
# Usage: ./run-all.sh <path-to-spring-boot-clone> [small|big]
#   small  sample-app/pom.xml      (default)
#   big    sample-app/pom-big.xml  (more starters, ~2x the nested jars)
set -euo pipefail
cd "$(dirname "$0")"
usage() { echo "usage: $0 <path-to-spring-boot-clone> [small|big]" >&2; exit 1; }
[[ $# -ge 1 && $# -le 2 ]] || usage
case "${2:-small}" in
	small) POM=pom.xml ;;
	big) POM=pom-big.xml ;;
	*) usage ;;
esac
./scripts/build-loaders.sh "$1"
./scripts/build-apps.sh "$POM"
./scripts/run-benchmark.sh
