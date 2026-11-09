#!/usr/bin/env bash
# Compares work/apps/app-rc2.jar (before) with work/apps/app-pr.jar (after):
#  1. micro    InflaterAllocBench: exact bytes allocated reading every nested class entry
#  2. startup  RUNS plain launches per variant (no JFR), interleaved
#  3. jfr      JFR_RUNS startups per variant with allocation profiling, summarised by JfrAnalyze
#
# Env: RUNS (10), JFR_RUNS (5), JVM_OPTS ("-Xms256m -Xmx256m")
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/work"
RUNS="${RUNS:-10}"
JFR_RUNS="${JFR_RUNS:-5}"
JVM_OPTS="${JVM_OPTS:--Xms256m -Xmx256m}"
VARIANTS="rc2 pr"
OUT="$ROOT/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

{
	echo "date: $(date)"
	echo "java: $(java -version 2>&1 | head -1)"
	echo "jvm opts: $JVM_OPTS"
	echo "app: sample-app/$(cat "$WORK/apps/pom.txt")"
	echo "pr: $(paste -sd ' ' "$WORK/loaders/pr.txt")"
} | tee "$OUT/env.txt"

# shellcheck disable=SC2086
launch() { # <variant> <extra jvm opts...>
	local v="$1"; shift
	java $JVM_OPTS "$@" -Dbench.exit=true -jar "$WORK/apps/app-$v.jar" 2>&1 | grep '^BENCH' || true
}

echo; echo "==> [micro] exact allocation reading every nested class"
for v in $VARIANTS; do
	java -Xmx512m -cp "$WORK/loaders/$v.jar" "$ROOT/alloc-bench/InflaterAllocBench.java" \
		"$WORK/apps/app-rc2.jar" classes 5 | tee "$OUT/micro-$v.txt" | grep -E '^RESULT' | sed "s/^/$v /"
done

echo; echo "==> [startup] $RUNS interleaved runs per variant"
for v in $VARIANTS; do launch "$v" >/dev/null; done # warm the OS file cache
for ((i = 1; i <= RUNS; i++)); do
	for v in $VARIANTS; do echo "$v $(launch "$v" | head -1)" >> "$OUT/startup.txt"; done
	printf '.'
done
echo
for v in $VARIANTS; do
	grep "^$v " "$OUT/startup.txt" | sed -E 's/.*startup-ms=([0-9]+).*classes=([0-9]+).*/\1 \2/' | sort -n \
		| awk -v v="$v" '{a[NR]=$1; s+=$1; c=$2} END {
			printf "%-4s startup-ms: median=%d mean=%.0f min=%d max=%d | classes=%d (n=%d)\n",
				v, a[int((NR+1)/2)], s/NR, a[1], a[NR], c, NR }'
done | tee "$OUT/startup-summary.txt"

echo; echo "==> [jfr] $JFR_RUNS startups per variant"
jfr configure --input profile 'jdk.ObjectAllocationSample#throttle=off' --output "$OUT/alloc.jfc" >/dev/null
for ((i = 1; i <= JFR_RUNS; i++)); do
	for v in $VARIANTS; do
		launch "$v" "-XX:StartFlightRecording:settings=$OUT/alloc.jfc,filename=$OUT/$v-$i.jfr,dumponexit=true" >/dev/null
	done
	printf '.'
done
echo
printf 'variant\trecording\ttotal-MB\tloader-MB\tZipInflaterInputStream.<init>-MB\tGCs\tGC-pause-ms\n' > "$OUT/jfr-summary.tsv"
for v in $VARIANTS; do
	java "$ROOT/alloc-bench/JfrAnalyze.java" "$v" "$OUT/$v"-[0-9]*.jfr
done >> "$OUT/jfr-summary.tsv"
grep -E '^variant|AVERAGE' "$OUT/jfr-summary.tsv" | column -t -s $'\t'

echo; echo "Results in $OUT"
