# scala-opt-ab

Tools for asking two questions about the Scala 3 backend optimizer (`-opt`, `-opt-inline`):

1. **What does it cost to compile?** Per-method, per-pass CPU and allocation of `LocalOptimizer` (`localopt/`).
2. **What does it buy at runtime?** An A/B/A/B benchmark of the same library sources compiled two ways (`bin/ab`).

They came out of [scala/scala3#27289](https://github.com/scala/scala3/issues/27289): a few large standard library methods (`Using.resources`, `Vector{3,4,5,6}.map`) take hundreds of milliseconds each in local optimization. The A/B rig showed that the optimized versions of those methods are no faster at runtime, and `Using.resources` is ~18% slower, because the inlined method (734 bytes) exceeds HotSpot's hot-method inlining limit while the unoptimized chain of small methods is inlined by C2.

## A/B benchmark

```bash
bin/ab --scala3 ~/code/scala/scala3 \
  --variant 'opt=-opt -opt-inline:**,!java.**' \
  --variant 'noopt=' \
  -- --rounds 10 --ms 300
```

What it does:

- Compiles `--sources` (paths relative to the checkout's `library/src`, or absolute) twice with the checkout's non-bootstrapped compiler, once per `--variant label=flags`. Common flags (`--flags`, default `-Yexplicit-nulls -release 17`) match how the library is built.
- Compiles the workloads in `src/workloads` against the unmodified library.
- Creates one class loader per variant over *[variant classes, library, workloads]* sharing only the JDK. A child-first overlay on a shared library does not work: library classes outside the overlay refer to the overlaid types and the JVM reports loader-constraint violations.
- Warms up both variants, then samples them alternately (A B B A A B …) for `--rounds`, each sample running the workload for `--ms`. Reports the median wall time, thread CPU time and thread allocation per operation, the wall-time range, and the A/B ratios.

Requirements: a scala3 checkout where `scala3-compiler-nonbootstrapped` and `scala-library-nonbootstrapped` have been built, `sbt` on the path (used once to export the compiler classpath; pass `--compiler-cp`/`--lib-cp` to avoid it), and `JAVA_HOME` or `java` on the path. JVM options for the benchmark run come from `AB_JAVA_OPTS` (default `-Xms4g -Xmx4g -XX:+UseParallelGC`). `--skip-build` reruns without recompiling.

### Writing workloads

`src/workloads/Workloads.scala` is plain Scala compiled against the library. It must define:

```scala
object Workloads {
  def names: java.util.List[String]
  def make(name: String): java.util.function.Supplier[Object] // set up once; the supplier is one operation
  def describe(name: String): String                         // optional label
}
```

Point `--workloads` at another directory to benchmark something else. Return the operation's result from the supplier so it is not dead code.

### Interpreting results

Run-to-run variation between JVM forks is larger than within a fork (JIT decisions differ), so rerun the whole command a few times before trusting a difference under ~5%. `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` in `AB_JAVA_OPTS` is the quickest way to see why a variant differs.

Results on JDK 21 / Apple Silicon, 5 forks, opt/noopt wall time:

| workload | opt/noopt |
|---|---|
| `Using.resources` (4 resources) | 1.17–1.21 |
| `Vector.map` 20k (`Vector3`) | 1.04–1.17 |
| `Vector.map` 500k / 2M (`Vector4`/`Vector5`) | ~1.00 ± 0.03 |
| `Sorting.stableSort` 100k | ~1.00 ± 0.02 |

Allocation was identical in every case.

## Local optimizer cost (`localopt/`)

- `localopt-timing.patch`: instrumentation for `LocalOptimizer` and `ProdConsAnalyzer`, inert unless the system property `dotty.opt.timing` names an output file. It appends one CSV line per optimized method: owner, name, descriptor, instructions before/after, max locals, wall/CPU nanos, allocated bytes, `removalRound` count, CPU and allocation per pass (between `traceIfChanged` checkpoints), try-catch block count, handler coverage, and the number and total set size of `SourceValue` merges. The property is read on every method, so it can be toggled in a running sbt server with `eval sys.props("dotty.opt.timing") = "/tmp/opt.csv"`. Made against scala/scala3 `main` at bb20dff9cb.
- `LocalOptPathologyTest.scala`: JUnit harness (goes in `compiler/test/dotty/tools/backend/jvm/`, needs the patch). One suite compiles synthetic variants of `Using.resource` at increasing nesting depth to isolate which constructs blow up; another compiles real library files and reports the slowest methods.
- `localopt_report.py`: summary of a CSV: percentiles, concentration, CPU by pass, pivots by size and rounds, slowest methods.
- `predictor.py`: how well static and dynamic cost predictors rank methods by actual analysis cost (rank correlation, CPU saved by skipping the top N). Merge work counted inside the analyzer tracks cost almost as well as an oracle; static size measures do not.

To collect library-wide data, in the scala3 build: `scala3-compiler-nonbootstrapped/compile`, set the property, then `scala-library-bootstrapped/clean; scala-library-bootstrapped/compile` twice and keep the second (JIT-warm) file.

## LLM disclosure

This repository was written with Claude Code (Anthropic) during an investigation I directed; I reviewed the method and results.
