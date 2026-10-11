import java.util.function.Supplier
import scala.util.Using

/** Example workloads exercising library methods that are expensive for the Scala 3 backend optimizer.
 *  Written as plain Scala: compiled once against the library, loaded once per build.
 */
object Workloads {
  final class Res extends AutoCloseable { def close(): Unit = () }

  def names: java.util.List[String] = java.util.List.of(
    "vectorMap:20000", "vectorMap:500000", "vectorMap:2000000", "using4", "stableSort:100000")

  def describe(name: String): String = name match {
    case s"vectorMap:$n" => s"$name (${Vector.from(0 until n.toInt).getClass.getSimpleName})"
    case _ => name
  }

  def make(name: String): Supplier[Object] = name match {
    case s"vectorMap:$n" =>
      val v = Vector.from(0 until n.toInt)
      val f: Int => Int = _ + 1
      () => v.map(f)
    case "using4" => // 1000 calls of the 4-resource overload per op
      val r = new Res
      () => { var i = 0; var acc = 0; while (i < 1000) { acc += Using.resources(r, r, r, r)((a, b, c, d) => i); i += 1 }; Integer.valueOf(acc) }
    case s"stableSort:$n" =>
      val rnd = new scala.util.Random(42)
      val data = Seq.fill(n.toInt)(rnd.nextInt())
      () => scala.util.Sorting.stableSort(data)
  }
}
