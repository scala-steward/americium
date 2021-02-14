package com.sageserpent.americium.java

import _root_.com.sageserpent.americium.{
  TrialsJavaScalaFusionApi,
  Trials => ScalaTrials
}
import cats.free.Free
import com.sageserpent.americium.java.Trials.GenerationSupport

import _root_.java.util.function.{Consumer, Predicate}
import java.util.function

object Trials extends TrialsJavaScalaFusionApi {
  override def api(): TrialsApi = this

  sealed trait GenerationOperation[Case]

  case class Choice[Case](choices: Iterable[Case])
      extends GenerationOperation[Case]

  case class Alternation[Case](alternatives: Iterable[Trials[Case]])
      extends GenerationOperation[Case]

  // NASTY HACK: as `Free` does not support `filter/withFilter`, reify
  // the optional results of a flat-mapped filtration; the interpreter
  // will deal with these.
  case class FiltrationResult[Case](result: Option[Case])
      extends GenerationOperation[Case]

  type Generation[Case] = Free[GenerationOperation, Case]

  private[americium] trait GenerationSupport[+Case] {
    val generation: Generation[_ <: Case]
  }
}

trait Trials[+Case] extends GenerationSupport[Case] {
  def map[TransformedCase](
      transform: function.Function[_ >: Case, TransformedCase])
    : Trials[TransformedCase]

  def flatMap[TransformedCase](
      step: function.Function[_ >: Case, Trials[TransformedCase]])
    : Trials[TransformedCase]

  def filter(predicate: Predicate[_ >: Case]): Trials[Case]

  /**
    * Consume trial cases until either there are no more or an exception is thrown by {@code consumer}.
    * If an exception is thrown, attempts will be made to shrink the trial case that caused the
    * exception to a simpler case that throws an exception - the specific kind of exception isn't
    * necessarily the same between the first exceptional case and the final simplified one. The exception
    * from the simplified case (or the original exceptional case if it could not be simplified) is wrapped
    * in an instance of {@link TrialException} which also contains the case that provoked the exception.
    *
    * @param consumer An operation that consumes a 'Case', and may throw an exception.
    */
  def supplyTo(consumer: Consumer[_ >: Case]): Unit

  /**
    * Consume the single trial case reproduced by a recipe. This is intended
    * to repeatedly run a test against a known failing case when debugging, so
    * the expectation is for this to *eventually* not throw an exception after
    * code changes are made in the system under test.
    *
    * @param recipe   This encodes a specific case and will only be understood by the
    *                 same *value* of trials instance that was used to obtain it.
    * @param consumer An operation that consumes a 'Case', and may throw an exception.
    * @throws RuntimeException if the recipe is not one corresponding to the receiver,
    *                          either due to it being created by a different flavour
    *                          of trials instance.
    */
  def supplyTo(recipe: String, consumer: Consumer[_ >: Case]): Unit

  // Scala and Java API ...

  abstract class TrialException(cause: Throwable)
      extends RuntimeException(cause) {

    /**
      * @return The case that provoked the exception.
      */
    def provokingCase: Case

    /**
      * @return A recipe that can be used to reproduce the provoking case
      *         when supplied to the corresponding trials instance.
      */
    def recipe: String
  }

  private[americium] val scalaTrials: ScalaTrials[Case]
}