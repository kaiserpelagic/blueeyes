package blueeyes.actor

import scalaz._
import scalaz.Scalaz._

import blueeyes.concurrent.Future

trait ActorPimps {
  import ActorHelpers._
  import ActorTypeclasses._

  implicit def ActorPimpToMAB[A, B](pimp: ActorPimp[A, B]) = ActorToMAB(pimp.value)

  implicit def ToActorPimp[A, B](actor: Actor[A, B]) = ActorPimp(actor)

  implicit def ToTupledOutputPimp[A, B1, B2, B](actor: Actor[A, (B1, B2)]) = TupledOutputPimp(actor)
  implicit def ToHigherKindedPimp[T[_], A, B](actor: Actor[A, T[B]]) = HigherKindedPimp(actor)
  implicit def ToValidatedPimp[A, E, B](actor: Actor[A, Validation[E, B]]) = ValidatedPimp(actor)
  implicit def ToHigherOrderPimp[A, B, C, D](actor: Actor[(A, Actor[C, D]), (B, Actor[C, D])]) = HigherOrderPimp(actor)

  implicit def ActorStateToActorStatePimp[A, B](value: ActorState[A, B]): ActorStatePimp[A, B] = ActorStatePimp(value)

  sealed case class ActorPimp[A, B](value: Actor[A, B]) extends NewType[Actor[A, B]] {
    /** Maps the input values sent to the actor.
     */
    def premap[AA](f: AA => A): Actor[AA, B] = receive[AA, B] { aa: AA =>
      (value ! f(aa)).mapElements[B, Actor[AA, B]](identity, _.premap[AA](f))
    }

    /** 
     * @see premap
     */
    def -<- [AA](f: AA => A): Actor[AA, B] = premap(f)

    /** Maps the output values produced by the 
     */
    def postmap[BB](f: B => BB): Actor[A, BB] = value.map(f)

    /** 
     * @see postmap
     */
    def ->- [BB](f: B => BB): Actor[A, BB] = value.map(f)

    /** Maps both input and output values in one step.
     */
    def bimap[AA, BB](f: AA => A, g: B => BB): Actor[AA, BB] = premap(f).postmap(g)

    /** Returns a new actor, which given an input value, produces a tuple 
     * holding the outputs for this actor AND the specified 
     */
    def & [AA >: A, C](that: Actor[AA, C]): Actor[A, (B, C)] = receive { a: A =>
      val (b, selfNext) = value ! a
      val (c, thatNext) = that ! a

      ((b, c), selfNext & thatNext)
    }

    /** Accepts one or the other input type and produces the corresponding 
     * output type.
     */
    def ^ [C, D] (that: Actor[C, D]): Actor[Either[A, C], Either[B, D]] = {
      receive { 
        case Left(a)  => (value ! a).mapElements(Left.apply  _, _ ^ that)
        case Right(c) => (that  ! c).mapElements(Right.apply _, value ^ _)
      }
    }

    /** Joins a higher order actor with its dependency to produce a lower 
     * order actor.
     */
    def ~> [C, D] (that: Actor[(C, Actor[A, B]), (D, Actor[A, B])]): Actor[C, D] = {
      receive { c: C =>
        val ((d, this2), that2) = that ! ((c, value))

        (d, this2 ~> that2)
      }
    }

    /** Returns a new actor, which accepts a tuple and produces a tuple, which 
     * are formed from the input and output values for this actor and the 
     * specified actor, respectively. This operation is called "cross".
     */
    def * [AA, BB](that: Actor[AA, BB]): Actor[(A, AA), (B, BB)] = receive {
      case (a, aa) =>
        val (b,  next1) = value ! a
        val (bb, next2) = that ! aa

        ((b, bb), next1 * next2)
    }

    /** Splits the output into a tuple of the output.
     */
    def split: Actor[A, (B, B)] = {
      lazy val lazySelf: Actor[A, (B, B)] = receive { a: A =>
        val (b, next) = value ! a

        ((b, b), lazySelf)
      }

      lazySelf
    }

    /** Classic scan ("fold" with the output being the intermediate values).
     */
    def scan[Z](z: Z)(f: (Z, B) => Z): Actor[A, Z] = receive { a: A =>
      val (b, next) = value ! a

      val z2 = f(z, b)

      (z2, next.scan(z2)(f))
    }

    /** Folds over the output values generated by the specified sequence of 
     * input values, to generate a single final result, together with the
     * continuation of this actor.
     */
    def fold[Z](z: Z, as: Seq[A])(f: (Z, B) => Z): (Z, Actor[A, B]) = as.foldLeft[(Z, Actor[A, B])]((z, value)) {
      case ((z, actor), a) =>
        val (b, actor2) = actor ! a

        (f(z, b), actor2)
    }

    /** Switches between two actors based on a boolean predicate.
     * {{{
     * a.ifTrue(_ % 2 == 0)(then = multiply, orELse = divide)
     * }}}
     */
    def ifTrue[C](f: B => Boolean)(then: Actor[B, C], orElse: Actor[B, C]): Actor[A, C] = switch(orElse)(f -> then)

    /** Switches between multiple actors based on boolean predicates.
     */
    def switch[C](defaultCase: Actor[B, C])(cases: (B => Boolean, Actor[B, C])*): Actor[A, C] = {
      def reduce(t: (B => Boolean, Actor[B, C]), orElse: Actor[B, C]): Actor[B, C] = {
        val (p1, a1) = t

        receive { (b: B) =>
          if (p1(b)) {
            val (c, a2) = a1 ! b

            (c, reduce((p1, a2), orElse))
          }
          else orElse ! b
        }
      }

      value >>> (cases.foldRight[Actor[B, C]](defaultCase)(reduce))
    }

    /** Lifts the output values into a monad.
     */
    def lift[M[_]](implicit monad: Monad[M]): ActorM[M, A, B] = ActorMHelpers.receive[M, A, B] { a: A =>
      monad.pure(value ! a) map {
        case (b, next) =>
          (b, next.lift[M])
      }
    }

    /** Converts a synchronous actor into an aynchronous actor.
     */
    def async: ActorAsync[A, B] = ActorMHelpers.receive[Future, A, B] { a: A =>
      Future.async(value ! a) map {
        case (b, next) =>
          (b, next.async)
      }
    }

    /** Workaround invariance of actor.
     */
    def variant[AA <: A, BB >: B]: Actor[AA, BB] = premap[AA](aa => (aa: A)).postmap[BB](b => (b: BB))
  }

  case class HigherKindedPimp[T[_], A, B](value: Actor[A, T[B]]) extends NewType[Actor[A, T[B]]] {
    def filter(f: T[B] => Boolean)(implicit empty: Empty[T]): Actor[A, T[B]] = receive { a: A =>
      val (tb, next) = value ! a

      (if (f(tb)) tb else implicitly[Empty[T]].empty[B], next.filter(f))
    }
  }

  case class TupledOutputPimp[A, B1, B2](value: Actor[A, (B1, B2)]) extends NewType[Actor[A, (B1, B2)]] {
    def >- [B](f: (B1, B2) => B): Actor[A, B] = receive { a: A =>
      val ((b1, b2), next) = value ! a
       
      val c = f(b1, b2)

      (c, next >- (f))
    }
  }

  case class ValidatedPimp[A, E, B](value: Actor[A, Validation[E, B]]) extends NewType[Actor[A, Validation[E, B]]] {
    def | [AA >: A, BB <: B, EE <: E](that: Actor[AA, Validation[EE, BB]]): Actor[A, Validation[E, B]] = {
      receive { a: A =>
        try {
          val result = value ! a

          result.mapElements(identity, _ | that.variant[A, Validation[E, B]]) match {
            case (Failure(_), next) => (that ! a).variant[A, Validation[E, B]].mapElements(identity, next | _)

            case x => x
          }
        }
        catch {
          case e: MatchError => (that ! a).variant[A, Validation[E, B]].mapElements(identity, this | _)
        }
      }
    }
  }

  case class HigherOrderPimp[A, B, C, D](value: Actor[(A, Actor[C, D]), (B, Actor[C, D])]) extends NewType[Actor[(A, Actor[C, D]), (B, Actor[C, D])]] {
    /** Joins a higher order actor with its dependency to produce a lower 
     * order actor.
     */
    def <~ (that: Actor[C, D]): Actor[A, B] = {
      receive { a: A =>
        val ((b, that2), self2) = value ! ((a, that))

        (b, self2 <~ that2)
      }
    }
  }

  case class ActorStatePimp[A, B](value: ActorState[A, B]) extends NewType[ActorState[A, B]] {
    def premap[AA](f: AA => A): (B, Actor[AA, B]) = value.mapElements[B, Actor[AA, B]](identity, _.premap[AA](f))
    
    def postmap[BB](f: B => BB): (BB, Actor[A, BB]) = value.mapElements[BB, Actor[A, BB]](f, _.postmap[BB](f))

    def variant[AA <: A, BB >: B]: (BB, Actor[AA, BB]) = value.mapElements((b: B) => (b : BB), _.variant[AA, BB])
  }
}
object ActorPimps extends ActorPimps