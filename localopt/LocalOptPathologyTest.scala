package dotty.tools.backend.jvm

import org.junit.Test
import dotty.tools.backend.jvm.opt.LocalOptTiming

/** Ad-hoc harness for LocalOptimizer cost pathologies. Needs the LocalOptTiming patch; prints, asserts nothing. */
trait LocalOptPathologyHarness extends OptimizationBytecodeTest {
  override def initCtx = {
    val ctx = super.initCtx
    ctx.setSetting(ctx.settings.opt, true)
    ctx.setSetting(ctx.settings.optInline, List("**", "!java.**"))
  }

  case class Row(owner: String, method: String, insns: Int, locals: Int, rounds: Int, cpuMs: Double, allocMB: Double,
                 tcbs: Int, handlerCover: Int, passes: String) {
    def show(label: String): String =
      f"$label%-34s ${owner.split('/').last}.$method%-44s insns=$insns%5d locals=$locals%4d tcb=$tcbs%3d cover=$handlerCover%6d rounds=$rounds cpu=$cpuMs%8.1fms alloc=$allocMB%7.1fMB  $passes"
  }

  /** Compile `source` `reps` times, return per-method rows from the fastest run. */
  def measure(source: String, reps: Int = 3): Seq[Row] = {
    val k = LocalOptTiming.passes.length
    val runs = (1 to reps).map { _ =>
      val f = java.io.File.createTempFile("opt", ".csv")
      f.delete()
      System.setProperty("dotty.opt.timing", f.getPath)
      try checkBCode(source) { _ => () } finally System.clearProperty("dotty.opt.timing")
      val lines = if (f.exists) scala.io.Source.fromFile(f).getLines().toList else Nil
      f.delete()
      lines.map(_.split(",")).map { c =>
        val pc = c.slice(11, 11 + k).map(_.toLong)
        val top = pc.zipWithIndex.sortBy(-_._1).take(3).map((v, i) => f"${LocalOptTiming.passes(i)}=${v / 1e6}%.1f").mkString(" ")
        Row(c(0), c(1), c(3).toInt, c(5).toInt, c(10).toInt, c(8).toLong / 1e6, c(9).toLong / 1048576.0,
            c(11 + 2 * k).toInt, c(12 + 2 * k).toInt, top)
      }
    }
    runs.minBy(_.map(_.cpuMs).sum)
  }
}

/** Synthetic variants of `scala.util.Using.resource`/`resources`. */
class LocalOptPathologyTest extends LocalOptPathologyHarness {
  val prelude =
    """trait Releasable[-R] { def release(resource: R): Unit }
      |object Supp {
      |  def preferentiallySuppress(primary: Throwable, secondary: Throwable): Throwable = { primary.addSuppressed(secondary); primary }
      |}
      |""".stripMargin

  /** `resource` from scala.util.Using, verbatim modulo names. */
  val fullResource =
    """def resource[R, A](resource: R)(body: R => A)(implicit releasable: Releasable[R]): A = {
      |  if (resource == null) throw new NullPointerException("null resource")
      |  var toThrow: Throwable | Null = null
      |  try {
      |    body(resource)
      |  } catch {
      |    case t: Throwable =>
      |      toThrow = t
      |      null.asInstanceOf[A]
      |  } finally {
      |    if (toThrow eq null) releasable.release(resource)
      |    else {
      |      try releasable.release(resource)
      |      catch { case other: Throwable => toThrow = Supp.preferentiallySuppress(toThrow, other) }
      |      finally throw toThrow
      |    }
      |  }
      |}""".stripMargin

  /** `resources` with `depth` nested `resource` calls. */
  def resources(depth: Int): String = {
    val tparams = (1 to depth).map(i => s"R$i: Releasable").mkString(", ")
    val params = (1 to depth).map(i => if (i == 1) s"resource$i: R$i" else s"resource$i: => R$i").mkString(", ")
    val bodyType = (1 to depth).map(i => s"R$i").mkString("(", ", ", ") => A")
    val call = (1 to depth).map(i => s"r$i").mkString("body(", ", ", ")")
    val nested = (1 to depth).foldRight(call)((i, inner) => s"resource(resource$i) { r$i =>\n $inner \n}")
    s"def resources[$tparams, A]($params)(body: $bodyType): A =\n$nested"
  }

  def variant(body: String) = s"def resource[R, A](resource: R)(body: R => A)(implicit releasable: Releasable[R]): A = {\n$body\n}"

  val variants = List(
    "full" -> fullResource,
    "full, no catch in finally" -> fullResource.replace("catch { case other: Throwable => toThrow = Supp.preferentiallySuppress(toThrow, other) }", ""),
    "no nested try in finally" -> variant(
      """var toThrow: Throwable | Null = null
        |try body(resource)
        |catch { case t: Throwable => toThrow = t; null.asInstanceOf[A] }
        |finally { releasable.release(resource); if (toThrow ne null) throw toThrow }""".stripMargin),
    "explicit java-style (no finally)" -> variant(
      """val a = try body(resource) catch { case t: Throwable =>
        |  try releasable.release(resource)
        |  catch { case other: Throwable => throw Supp.preferentiallySuppress(t, other) }
        |  throw t
        |}
        |releasable.release(resource); a""".stripMargin),
    "try/finally only" -> variant("try body(resource) finally releasable.release(resource)"),
  )

  @Test def usingVariants(): Unit =
    for ((label, res) <- variants; d <- 1 to 4) {
      val src = prelude + "object U {\n" + res + "\n" + resources(d) + "\n}\n"
      measure(src).filter(_.method == "resources").foreach(r => println(r.show(s"$label d=$d")))
    }
}

/** The real library sources behind the slowest methods of the library build, compiled like the library. */
class LocalOptPathologyLibraryTest extends LocalOptPathologyHarness {
  override def initCtx = {
    val ctx = super.initCtx
    ctx.setSetting(ctx.settings.YexplicitNulls, true)
  }

  val libDir: java.nio.file.Path = java.nio.file.Paths.get(sys.props.getOrElse("user.dir", "."), "library", "src").normalize

  def file(rel: String): String = new String(java.nio.file.Files.readAllBytes(libDir.resolve(rel)))

  val files = List(
    "scala/util/Using.scala",
    "scala/collection/immutable/Vector.scala",
    "scala/util/Sorting.scala",
    "scala/collection/mutable/CollisionProofHashMap.scala",
    "scala/collection/immutable/HashMap.scala",
    "scala/quoted/FromExpr.scala",
  )

  @Test def libraryFiles(): Unit =
    for (f <- files) {
      val rows = scala.util.Try(measure(file(f), reps = 2)).fold(e => { println(s"FAILED $f: $e"); Nil }, identity)
      val total = rows.map(_.cpuMs).sum
      println(f"FILE $f%-55s methods=${rows.size}%5d cpu=$total%8.1fms")
      rows.sortBy(-_.cpuMs).take(5).foreach(r => println(r.show("  ")))
    }
}
