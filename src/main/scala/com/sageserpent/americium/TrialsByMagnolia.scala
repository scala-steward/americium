package com.sageserpent.americium

import magnolia.{CaseClass, Magnolia, SealedTrait}
import mercator.Monadic

import scala.language.experimental.macros

trait TrialsByMagnolia {
  trait Factory[Case] {
    def trials: Trials[Case]
  }

  def lift[Case](unlifted: Trials[Case]): Factory[Case] = new Factory[Case] {
    override def trials: Trials[Case] = unlifted
  }

  implicit val intFactory: Factory[Int]         = lift(Trials.api.integers)
  implicit val doubleFactory: Factory[Double]   = lift(Trials.api.doubles)
  implicit val longFactory: Factory[Long]       = lift(Trials.api.longs)
  implicit val booleanFactory: Factory[Boolean] = lift(Trials.api.trueOrFalse)

  type Typeclass[Case] = Factory[Case]

  // HACK: had to write an explicit implicit implementation
  // as Mercator would need an `apply` method to implement
  // `point`, which `Trials` does not provide.
  implicit def evidence: Monadic[Trials] = new Monadic[Trials] {
    override def flatMap[A, B](from: Trials[A])(fn: A => Trials[B]): Trials[B] =
      from.flatMap(fn)

    override def point[A](value: A): Trials[A] = Trials.api.only(value)

    override def map[A, B](from: Trials[A])(fn: A => B): Trials[B] =
      from.map(fn)
  }

  def combine[Case](caseClass: CaseClass[Typeclass, Case]): Typeclass[Case] =
    lift(caseClass.constructMonadic(_.typeclass.trials))

  def dispatch[Case](
      sealedTrait: SealedTrait[Typeclass, Case]): Typeclass[Case] = {
    val Seq(leadingSubtype, remainingSubtypes @ _*) = sealedTrait.subtypes
    val subtypeGenerators: Seq[Trials[Case]] =
      Trials.api.delay(leadingSubtype.typeclass.trials) +: remainingSubtypes
        .map(subtype => subtype.typeclass.trials)
    lift(Trials.api.alternate(subtypeGenerators))
  }

  implicit def gen[Case]: Typeclass[Case] = macro Magnolia.gen[Case]
}
