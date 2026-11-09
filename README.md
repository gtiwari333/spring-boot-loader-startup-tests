# spring-boot-loader startup tests

Reproduces the before/after behaviour of
[spring-projects/spring-boot#52395](https://github.com/spring-projects/spring-boot/pull/52395)
("Size the nested jar inflater buffer from the compressed size").

| Variant | Loader |
|---|---|
| `rc2` | `spring-boot-loader-4.2.0-RC2.jar` from Maven Central (**before**) |
| `pr`  | `loader/spring-boot-loader` built from your spring-boot clone (**after**) |

The sample app (`sample-app/`) is built **once** with the 4.2.0-RC2 Maven plugin. Each variant is
that same fat jar with only `org/springframework/boot/loader/**` replaced, so the application,
its dependencies and the nested-jar layout are identical.

## Setup

Requirements: JDK 25, Maven, `zip`/`unzip`.

Clone spring-boot yourself and check out the PR:

```bash
git clone https://github.com/spring-projects/spring-boot.git
cd spring-boot
git fetch origin pull/52395/head:pr-52395
git checkout pr-52395
```

The scripts build the clone **as it is**: whatever branch is checked out, including uncommitted
changes. `work/loaders/pr.txt` records the commit that was built (and whether there were local
changes). Gradle build output goes to the clone's own `build/` directories.

## Run

```bash
./run-all.sh /path/to/spring-boot          # small app: sample-app/pom.xml (default)
./run-all.sh /path/to/spring-boot big      # larger app: sample-app/pom-big.xml

# or step by step
./scripts/build-loaders.sh /path/to/spring-boot   # work/loaders/{rc2,pr}.jar
./scripts/build-apps.sh [pom-big.xml]             # work/apps/app-{rc2,pr}.jar (default pom.xml)
RUNS=20 JFR_RUNS=5 JVM_OPTS="-Xms256m -Xmx256m" \
  ./scripts/run-benchmark.sh                      # results/<timestamp>/
```

## What is measured

1. **micro** – `alloc-bench/InflaterAllocBench.java` opens every `BOOT-INF/lib/*.jar` with the
   loader's `NestedJarFile` and `readAllBytes()` every deflated `.class` entry. Allocation is
   read from `ThreadMXBean.getCurrentThreadAllocatedBytes()`, so it is **exact and
   deterministic** (no sampling). It also prints the buffer bytes predicted by the old/new rule.
2. **startup** – `RUNS` interleaved plain launches (no JFR) per variant; JVM uptime at
   `ApplicationReadyEvent`.
3. **jfr** – `JFR_RUNS` startups per variant with `jdk.ObjectAllocationSample#throttle=off`, exiting
   at `ApplicationReadyEvent`; `alloc-bench/JfrAnalyze.java` then sums sample weights for: everything, stacks containing `org.springframework.boot.loader.*`, and stacks containing
   `ZipInflaterInputStream.<init>`; plus GC count/pause.

## Results (2026-11-09, Corretto 25.0.2, macOS arm64, `-Xms256m -Xmx256m`)

`rc2` = spring-boot-loader 4.2.0-RC2, `pr` = loader built from `startup-experiments` at
`68a41206a95`. JFR runs stop at `ApplicationReadyEvent`, so they measure startup only.

### Small app (`pom.xml`, `./run-all.sh <clone>`)

55 nested jars, 16,052 deflated class entries (60.6 MB uncompressed / 24.1 MB compressed),
~9,700 classes loaded at startup. Raw output: `results/20261109-114710`.

**micro (exact)**

| | rc2 | pr | change |
|---|---|---|---|
| bytes allocated reading all classes | 147.1 MB | 111.7 MB | **-35.4 MB (-24%)** |
| predicted inflater input buffers | 59.8 MB | 24.1 MB | -35.7 MB (-60%) |

Only 9 of 16,052 entries get a *larger* buffer with the PR (uncompressed > 64 KiB but
compressed <= 64 KiB: 8 KiB -> up to 64 KiB).

**jfr (avg of 5 startups)**

| | rc2 | pr | change |
|---|---|---|---|
| `ZipInflaterInputStream.<init>` | 25.7 MB | 10.4 MB | **-60%** |
| stacks in `org.springframework.boot.loader` | 183.0 MB | 167.6 MB | -8% |
| total sampled | 244.6 MB | 229.8 MB | -6% |
| young GCs | 7 | 7 | same |
| GC pause (sum) | 30.8 ms | 27.0 ms | noisy |

**startup (10 runs, no JFR)** – median 1618 ms (rc2) vs 1637 ms (pr): no measurable difference.

### Larger app (`pom-big.xml`, `./run-all.sh <clone> big`: adds data-jpa, data-rest, thymeleaf, brave tracing, prometheus, restclient, datasource-micrometer)

116 nested jars, 36,359 deflated class entries (148.1 MB uncompressed / 54.5 MB compressed),
~15,190 classes loaded at startup. Raw output: `results/20261109-114411`.

**micro (exact)**

| | rc2 | pr | change |
|---|---|---|---|
| bytes allocated reading all classes | 347.1 MB | 269.0 MB | **-78.1 MB (-23%)** |
| predicted inflater input buffers | 139.9 MB | 53.9 MB | -86.0 MB (-61%) |

65 of 36,359 entries get a larger buffer. Between JVM runs this figure varies by a few MB
(earlier runs: 355 MB vs 259–269 MB).

**jfr (avg of 5 startups)**

| | rc2 | pr | change |
|---|---|---|---|
| `ZipInflaterInputStream.<init>` | 41.0 MB | 17.5 MB | **-57%** |
| stacks in `org.springframework.boot.loader` | 302.3 MB | 274.9 MB | -9% |
| total sampled | 386.8 MB | 360.2 MB | -7% |
| young GCs | 11 | 11 | same |
| GC pause (sum) | 37.8 ms | 45.2 ms | noisy |

**startup (10 runs, no JFR)** – median 2621 ms (rc2) vs 2613 ms (pr): no measurable difference.
