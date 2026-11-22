package org.llm4s.ragbox.testkit

/**
 * Finds `NaN` and infinite numbers in a response model before it is encoded (#66). circe encodes
 * a non-finite Double as `null`, so one reaching a response breaks a `number`-typed field of the
 * admin UI without a type error anywhere.
 */
object NonFinite {

  /** The paths of the non-finite Doubles and Floats in `value` (case classes, Options, collections). */
  def paths(value: Any, path: String = "$"): Seq[String] = value match {
    case d: Double => if (d.isNaN || d.isInfinite) Seq(s"$path = $d") else Nil
    case f: Float => if (f.isNaN || f.isInfinite) Seq(s"$path = $f") else Nil
    case o: Option[?] => o.toList.flatMap(paths(_, path))
    case m: scala.collection.Map[?, ?] => m.toList.flatMap { case (k, v) => paths(v, s"$path.$k") }
    case it: Iterable[?] => it.toList.zipWithIndex.flatMap { case (v, i) => paths(v, s"$path[$i]") }
    case p: Product =>
      p.productElementNames.zip(p.productIterator).toList.flatMap { case (name, v) => paths(v, s"$path.$name") }
    case _ => Nil
  }
}
