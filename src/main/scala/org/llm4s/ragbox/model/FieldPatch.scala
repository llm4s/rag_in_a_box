package org.llm4s.ragbox.model

import io.circe.{ACursor, Decoder, DecodingFailure, HCursor, Json}

/**
 * One field of a partial update (`PATCH`) that can be cleared: left out (unchanged), `null`
 * (cleared) or a value (set). circe's `Option` decoder reads a missing field and `null` alike,
 * as `None`, so it cannot tell "leave it" from "clear it".
 */
enum FieldPatch[+A] {

  /** The field was left out: keep the current value. */
  case Unchanged

  /** The field was `null`: clear the value. */
  case Clear

  /** The field was given: replace the value. */
  case Set(value: A)

  /** The value after applying this patch to `current`. */
  def applyTo[B >: A](current: Option[B]): Option[B] = this match {
    case Unchanged => current
    case Clear => None
    case Set(value) => Some(value)
  }

  /** The JSON a request with this patch sends for the field, if it sends the field at all. */
  def toJson[B >: A](encode: B => Json): Option[Json] = this match {
    case Unchanged => None
    case Clear => Some(Json.Null)
    case Set(value) => Some(encode(value))
  }
}

object FieldPatch {

  /**
   * Decodes a field as a [[FieldPatch]]. A missing field is [[Unchanged]], `null` is [[Clear]].
   * It works by overriding `tryDecode`, which `ACursor.as` and circe's derived decoders call with
   * the field's cursor, failed when the field is missing.
   */
  given [A](using decodeA: Decoder[A]): Decoder[FieldPatch[A]] with {
    def apply(c: HCursor): Decoder.Result[FieldPatch[A]] = tryDecode(c)

    override def tryDecode(c: ACursor): Decoder.Result[FieldPatch[A]] = c match {
      case h: HCursor if h.value.isNull => Right(Clear)
      case h: HCursor => decodeA(h).map(Set(_))
      case _ => Right(Unchanged)
    }
  }

  /**
   * Decodes an optional field that cannot be cleared: missing is `None`, a value is `Some`, and
   * `null` fails, naming the field, instead of reading as "unchanged".
   */
  def notNull[A](c: ACursor, field: String)(using decodeA: Decoder[A]): Decoder.Result[Option[A]] = {
    val f = c.downField(field)
    f.focus match {
      case None => Right(None)
      case Some(json) if json.isNull =>
        Left(DecodingFailure(s"$field cannot be cleared (null); leave it out to keep it", f.history))
      case Some(_) => f.as[A].map(Some(_))
    }
  }
}
