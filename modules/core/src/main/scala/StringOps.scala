package skunk

// import cats.Semigroup
import cats.data._
import cats.implicits._
import scala.language.experimental.macros
import scala.reflect.macros.whitebox
// import skunk.util.Interleaved

class StringOps private[skunk] (sc: StringContext) {

  void(sc)

  // Encoder[A], Encoder[B], ... => Fragment[A ~ B ~ ...]
  def sql(argSeq: Any*): Any =
    macro StringOpsMacros.sql_impl

  def id(): Identifier =
    macro StringOpsMacros.identifier_impl

}

class StringOpsMacros(val c: whitebox.Context) {
  import c.universe._

  def sql_impl(argSeq: Tree*): Tree = {

    // Our prefix looks like this, and the stringy parts of the interpolation will be a non-empty
    // list of string literal trees. We just know this because of the way interpolator desugaring
    // works. If it doesn't work something bad has happened.
    val parts: List[Tree] =
      c.prefix.tree match {
        case Apply(_, List(Apply(_, ts))) => ts
        case _ => c.abort(c.prefix.tree.pos, "Unexpected tree, oops. See StringOps.scala")
      }

    // The interpolated args are a list of size `parts.length - 1`. We also just know this.
    val args = argSeq.toList

    // Every arg must conform with Encoder[_] or String
    val EncoderType = typeOf[Encoder[_]]
    val StringType  = typeOf[String]

    // Assemble a single list of Either[string tree, encoder tree] by interleaving the stringy parts
    // and the args. If the arg is an interpolated string we reinterpret it as a stringy part.
    val stuff: List[Either[Tree /* of string */, Tree /* of Encoder */]] =
      (parts zip args).foldRight(List(parts.last.asLeft[Tree])) {

        // The stringy part had better be a string literal. If we got here via the interpolator it
        // always will be. If not we punt (below).
        case ((part @ Literal(Constant(str: String)), arg), tail) =>

          // The arg had better have a type conforming with Encoder[_] or String
          val argType = c.typecheck(arg, c.TYPEmode).tpe

          if (str.endsWith("#")) {

            // The stringy part ends in a `#` so the following arg must typecheck as a String.
            // Assuming it does, turn it into a string and "emit" two `Left`s.
            if (argType <:< StringType)
              Left(Literal(Constant(str.init))) :: Left(arg) :: tail
            else
              c.abort(arg.pos, s"type mismatch;\n  found   : $argType\n  required: $StringType")

          } else {

            // We have a "normal" part + arg so "emit" them as a `Left` and a `Right`, assuming the
            // arg typechecks as an Encoder.
            if (argType <:< EncoderType)
              Left(part) :: Right(arg) :: tail
            else
              c.abort(arg.pos, s"type mismatch;\n  found   : $argType\n  required: $EncoderType")

          }

        // Otherwise the stringy part isn't a string literal, which means someone has gotten here
        // through nefarious means, like constructing a StringContext by hand.
        case ((p, _), _) =>
          c.abort(p.pos, s"StringContext parts must be string literals.")

      }

    // The final encoder is either `Encoder.void` or `a ~ b ~ ...`
    val finalEncoder: Tree = {
      val trees = stuff.collect { case Right(t) => t }
      if (trees.isEmpty) q"skunk.Encoder.void"
      else trees.reduceLeft((a, b) => q"$a ~ $b") // note: must be left-associated
    }

    // The final parts are a List[Either[tree of string, tree of int]] where the int is the number
    // of placeholders to insert.
    val finalParts: List[Either[Tree, Tree]] = stuff.map {
      case Left(a)  => Left(a)
      case Right(t) => Right(q"$t.oids.length")
    }

    // We now have what we need to construct a fragment.
    q"skunk.StringOps.mkFragment($finalParts, $finalEncoder)"

  }

  def identifier_impl(): Tree = {
    val Apply(_, List(Apply(_, List(s @ Literal(Constant(part: String)))))) = c.prefix.tree
    Identifier.fromString(part) match {
      case Left(s) => c.abort(c.enclosingPosition, s)
      case Right(Identifier(s)) => q"skunk.Identifier.unsafeFromString($s)"
    }
  }

}

object StringOps {

  def mkFragment[A](parts: List[Either[String, Int]], enc: Encoder[A]): Fragment[A] = {
    val sql: String = parts.traverse {
      case Left(s)  => s.pure[State[Int, ?]]
      case Right(n) => State((i: Int) => (i + n, (i until i + n).map(j => s"$$${ j + 1 }").mkString(", ") ))
    } .runEmptyA.value.combineAll
    Fragment(sql, enc)
  }

  def placeholders(n: Int, i: Int): String =
    List.fill(n)(State((x: Int) => (x + 1, s"$$$x"))).sequence.runA(i).value.mkString(", ")

  def mkSql(parts: List[String], placeholderCounts: List[Int]): String =
    (parts zip placeholderCounts).traverse { case (s, n) =>
      State((i: Int) => (i + n, s + placeholders(n, i)))
    } .runA(1).value.mkString

}

trait ToStringOps {
  implicit def toStringOps(sc: StringContext): StringOps =
    new StringOps(sc)
}