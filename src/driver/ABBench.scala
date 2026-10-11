import java.lang.management.ManagementFactory
import java.net.{URL, URLClassLoader}
import java.nio.file.Paths
import java.util.function.Supplier

/** A/B/A/B benchmark of two builds of the same library classes.
 *
 *  Each build gets its own class loader over [build classes, library classpath, workload classes] with only the
 *  JDK shared, so the workloads bind to that build. The two builds are then sampled alternately (A B B A A B ...)
 *  measuring wall time, thread CPU time and thread allocation per operation.
 *
 *  The workload classes must define `object Workloads { def names: java.util.List[String]; def make(name: String): Supplier[Object] }`
 *  and optionally `def describe(name: String): String`.
 */
object ABBench {
  private val mx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  final case class Sample(wallNs: Double, cpuNs: Double, allocB: Double)

  /** Run `op` repeatedly for about `targetMs`; per-op averages. */
  def sample(op: Supplier[Object], targetMs: Int): Sample = {
    var ops = 0L
    var sink: Object = null
    val w0 = System.nanoTime(); val c0 = mx.getCurrentThreadCpuTime; val a0 = mx.getCurrentThreadAllocatedBytes
    val deadline = w0 + targetMs * 1000000L
    while (System.nanoTime() < deadline) { sink = op.get(); ops += 1 }
    if (sink eq ABBench) println() // keep results observable
    Sample((System.nanoTime() - w0).toDouble / ops, (mx.getCurrentThreadCpuTime - c0).toDouble / ops,
           (mx.getCurrentThreadAllocatedBytes - a0).toDouble / ops)
  }

  private def median(xs: Seq[Double]) = { val s = xs.sorted; s(s.size / 2) }

  final case class Build(label: String, loader: ClassLoader) {
    private lazy val workloads = loader.loadClass("Workloads$").getField("MODULE$").get(null)
    private def call(name: String, args: Object*): Object =
      workloads.getClass.getMethods.find(m => m.getName == name && m.getParameterCount == args.length)
        .getOrElse(throw new NoSuchMethodException(s"Workloads.$name")).invoke(workloads, args*)
    def names: List[String] = { import scala.jdk.CollectionConverters.*; call("names").asInstanceOf[java.util.List[String]].asScala.toList }
    def make(name: String): Supplier[Object] = call("make", name).asInstanceOf[Supplier[Object]]
    def describe(name: String): String = scala.util.Try(call("describe", name).toString).getOrElse(name)
  }

  final case class Config(builds: List[(String, String)] = Nil, libCp: String = "", workloadsDir: String = "",
                          rounds: Int = 10, targetMs: Int = 300, only: Option[String] = None)

  def parse(args: List[String], c: Config = Config()): Config = args match {
    case "--build" :: spec :: rest => val Array(l, d) = spec.split("=", 2); parse(rest, c.copy(builds = c.builds :+ (l -> d)))
    case "--lib" :: cp :: rest => parse(rest, c.copy(libCp = cp))
    case "--workloads" :: d :: rest => parse(rest, c.copy(workloadsDir = d))
    case "--rounds" :: n :: rest => parse(rest, c.copy(rounds = n.toInt))
    case "--ms" :: n :: rest => parse(rest, c.copy(targetMs = n.toInt))
    case "--only" :: re :: rest => parse(rest, c.copy(only = Some(re)))
    case Nil => c
    case other => sys.error(s"unexpected arguments: $other")
  }

  def main(args: Array[String]): Unit = {
    val cfg = parse(args.toList)
    require(cfg.builds.size == 2, "need exactly two --build label=dir")
    def urls(overlay: String): Array[URL] =
      (overlay :: cfg.libCp.split(java.io.File.pathSeparator).toList.filter(_.nonEmpty) ::: List(cfg.workloadsDir))
        .map(p => Paths.get(p).toUri.toURL).toArray
    val builds = cfg.builds.map((l, d) => Build(l, new URLClassLoader(urls(d), ClassLoader.getPlatformClassLoader)))
    val List(a, b) = builds
    val names = a.names.filter(n => cfg.only.forall(n.matches))

    println(f"${"workload"}%-40s ${"build"}%-8s ${"wall/op"}%12s ${"cpu/op"}%12s ${"alloc/op"}%12s  wall min-max (us)")
    for (w <- names) {
      val ops = builds.map(bd => bd.label -> bd.make(w))
      for ((_, op) <- ops) sample(op, cfg.targetMs * 3) // warm up both
      val samples = scala.collection.mutable.Map.empty[String, List[Sample]].withDefaultValue(Nil)
      for (r <- 0 until cfg.rounds; (l, op) <- (if (r % 2 == 0) ops else ops.reverse))
        samples(l) = sample(op, cfg.targetMs) :: samples(l)
      val stats = ops.map((l, _) => l -> {
        val ss = samples(l)
        (median(ss.map(_.wallNs)), median(ss.map(_.cpuNs)), median(ss.map(_.allocB)), ss.map(_.wallNs).min, ss.map(_.wallNs).max)
      }).toMap
      val label = a.describe(w)
      for ((l, _) <- ops) {
        val (wl, c, al, lo, hi) = stats(l)
        println(f"$label%-40s $l%-8s ${wl / 1e3}%10.1fus ${c / 1e3}%10.1fus ${al / 1024}%10.1fKB  ${lo / 1e3}%.1f-${hi / 1e3}%.1f")
      }
      val (wa, ca, aa, _, _) = stats(a.label); val (wb, cb, ab, _, _) = stats(b.label)
      println(f"${""}%-40s ${a.label}/${b.label}: wall ${wa / wb}%.3f  cpu ${ca / cb}%.3f  alloc ${if (ab == 0) 1.0 else aa / ab}%.3f")
    }
  }
}
