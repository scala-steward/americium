package com.sageserpent.americium.java

import cats.data.{OptionT, State, StateT}
import cats.free.Free
import cats.free.Free.liftF
import cats.implicits._
import cats.{Eval, ~>}
import com.google.common.collect.{Ordering => _, _}
import com.sageserpent.americium.Trials.RejectionByInlineFilter
import com.sageserpent.americium.java.TrialsApiImplementation.scalaApi
import com.sageserpent.americium.java.TrialsScaffolding.OptionalLimits
import com.sageserpent.americium.java.tupleTrials.{
  Tuple2Trials => JavaTuple2Trials
}
import com.sageserpent.americium.java.{
  Trials => JavaTrials,
  TrialsScaffolding => JavaTrialsScaffolding,
  TrialsSkeletalImplementation => JavaTrialsSkeletalImplementation
}
import com.sageserpent.americium.randomEnrichment.RichRandom
import com.sageserpent.americium.tupleTrials.{Tuple2Trials => ScalaTuple2Trials}
import com.sageserpent.americium.{
  Trials => ScalaTrials,
  TrialsScaffolding => ScalaTrialsScaffolding
}
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

import _root_.java.util.function.{Consumer, Predicate, Function => JavaFunction}
import _root_.java.util.{Optional, Iterator => JavaIterator}
import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Random

object TrialsImplementation {
  val maximumScaleDeflationLevel = 50

  type DecisionStages   = List[Decision]
  type Generation[Case] = Free[GenerationOperation, Case]

  sealed trait Decision

  // Java and Scala API ...

  sealed trait GenerationOperation[Case]

  // Java-only API ...

  private[americium] trait GenerationSupport[+Case] {
    val generation: Generation[_ <: Case]
  }

  // Scala-only API ...

  case class ChoiceOf(index: Int) extends Decision

  case class FactoryInputOf(input: Long) extends Decision

  // Use a sorted map keyed by cumulative frequency to implement weighted
  // choices. That idea is inspired by Scalacheck's `Gen.frequency`.
  case class Choice[Case](choicesByCumulativeFrequency: SortedMap[Int, Case])
      extends GenerationOperation[Case]

  case class Factory[Case](factory: CaseFactory[Case])
      extends GenerationOperation[Case]

  // NASTY HACK: as `Free` does not support `filter/withFilter`, reify
  // the optional results of a flat-mapped filtration; the interpreter
  // will deal with these.
  case class FiltrationResult[Case](result: Option[Case])
      extends GenerationOperation[Case]

  case object NoteComplexity extends GenerationOperation[Int]

  case class ResetComplexity[Case](complexity: Int)
      extends GenerationOperation[Unit]
}

case class TrialsImplementation[Case](
    override val generation: TrialsImplementation.Generation[_ <: Case]
) extends JavaTrialsSkeletalImplementation[Case]
    with ScalaTrials[Case] {
  thisTrialsImplementation =>

  override type SupplySyntaxType = ScalaTrialsScaffolding.SupplyToSyntax[Case]

  import TrialsImplementation._

  override val scalaTrials = this

  // Java and Scala API ...
  override def reproduce(recipe: String): Case =
    reproduce(parseDecisionIndices(recipe))

  private def reproduce(decisionStages: DecisionStages): Case = {

    type DecisionIndicesContext[Caze] = State[DecisionStages, Caze]

    // NOTE: unlike the companion interpreter in `cases`,
    // this one has a relatively sane implementation.
    def interpreter: GenerationOperation ~> DecisionIndicesContext =
      new (GenerationOperation ~> DecisionIndicesContext) {
        override def apply[Case](
            generationOperation: GenerationOperation[Case]
        ): DecisionIndicesContext[Case] = {
          generationOperation match {
            case Choice(choicesByCumulativeFrequency) =>
              for {
                decisionStages <- State.get[DecisionStages]
                ChoiceOf(decisionIndex) :: remainingDecisionStages =
                  decisionStages
                _ <- State.set(remainingDecisionStages)
              } yield choicesByCumulativeFrequency
                .minAfter(1 + decisionIndex)
                .get
                ._2

            case Factory(factory) =>
              for {
                decisionStages <- State.get[DecisionStages]
                FactoryInputOf(input) :: remainingDecisionStages =
                  decisionStages
                _ <- State.set(remainingDecisionStages)
              } yield factory(input.toInt)

            // NOTE: pattern-match only on `Some`, as we are reproducing a case
            // that by dint of being reproduced, must have passed filtration the
            // first time around.
            case FiltrationResult(Some(result)) =>
              result.pure[DecisionIndicesContext]

            case NoteComplexity =>
              0.pure[DecisionIndicesContext]

            case ResetComplexity(_) =>
              ().pure[DecisionIndicesContext]
          }
        }
      }

    generation
      .foldMap(interpreter)
      .runA(decisionStages)
      .value
  }

  private def parseDecisionIndices(recipe: String): DecisionStages = {
    decode[DecisionStages](
      recipe
    ).toTry.get // Just throw the exception, the callers are written in Java style.
  }

  override def withLimit(
      limit: Int
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withLimits(casesLimit = limit)

  override def withLimit(
      limit: Int,
      complexityLimit: Int
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withLimits(casesLimit = limit, complexityLimit = complexityLimit)

  def withLimits(
      casesLimit: Int,
      additionalLimits: OptionalLimits
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withLimits(
      casesLimit = casesLimit,
      complexityLimit = additionalLimits.complexity,
      shrinkageAttemptsLimit = additionalLimits.shrinkageAttempts,
      shrinkageStop = { () =>
        val predicate: Predicate[_ >: Case] =
          JavaTrialsScaffolding.noStopping.build()

        predicate.test _
      }
    )

  def withLimits(
      casesLimit: Int,
      additionalLimits: OptionalLimits,
      shrinkageStop: JavaTrialsScaffolding.ShrinkageStop[_ >: Case]
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withLimits(
      casesLimit = casesLimit,
      complexityLimit = additionalLimits.complexity,
      shrinkageAttemptsLimit = additionalLimits.shrinkageAttempts,
      shrinkageStop = { () =>
        val predicate = shrinkageStop.build()

        predicate.test _
      }
    )

  override def withLimits(
      casesLimit: Int,
      complexityLimit: Int,
      shrinkageAttemptsLimit: Int,
      shrinkageStop: ScalaTrialsScaffolding.ShrinkageStop[_ >: Case]
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    new JavaTrialsScaffolding.SupplyToSyntax[Case]
      with ScalaTrialsScaffolding.SupplyToSyntax[Case] {
      final case class NonEmptyDecisionStages(
          latestDecision: Decision,
          previousDecisions: DecisionStagesInReverseOrder
      )

      final case object NoDecisionStages extends DecisionStagesInReverseOrder

      final case class InternedDecisionStages(index: Int)
          extends DecisionStagesInReverseOrder

      private val nonEmptyToAndFromInternedDecisionStages
          : BiMap[NonEmptyDecisionStages, InternedDecisionStages] =
        HashBiMap.create()

      private def interned(
          nonEmptyDecisionStages: NonEmptyDecisionStages
      ): InternedDecisionStages =
        Option(
          nonEmptyToAndFromInternedDecisionStages.computeIfAbsent(
            nonEmptyDecisionStages,
            _ => {
              val freshIndex = nonEmptyToAndFromInternedDecisionStages.size
              InternedDecisionStages(freshIndex)
            }
          )
        ).get

      sealed trait DecisionStagesInReverseOrder {
        def reverse: DecisionStages = appendInReverseOnTo(List.empty)

        @tailrec
        final def appendInReverseOnTo(
            partialResult: DecisionStages
        ): DecisionStages = this match {
          case NoDecisionStages => partialResult
          case _: InternedDecisionStages =>
            Option(
              nonEmptyToAndFromInternedDecisionStages.inverse().get(this)
            ) match {
              case Some(
                    NonEmptyDecisionStages(latestDecision, previousDecisions)
                  ) =>
                previousDecisions.appendInReverseOnTo(
                  latestDecision :: partialResult
                )
            }
        }

        def addLatest(decision: Decision): DecisionStagesInReverseOrder =
          interned(
            NonEmptyDecisionStages(decision, this)
          )
      }

      private def cases(
          limit: Int,
          complexityLimit: Int,
          randomBehaviour: Random,
          scaleDeflationLevel: Option[Int],
          decisionStagesToGuideShrinkage: Option[DecisionStages]
      ): Iterator[(DecisionStagesInReverseOrder, Case)] = {
        scaleDeflationLevel.foreach(level =>
          require((0 to maximumScaleDeflationLevel).contains(level))
        )

        // This is used instead of a straight `Option[Case]` to avoid stack
        // overflow when interpreting `this.generation`. We need to do this
        // because a) we have to support recursively flat-mapped trials and b)
        // even non-recursive trials can bring in a lot of nested flat-maps. Of
        // course, in the recursive case we merely convert the possibility of
        // infinite recursion into infinite looping through the `Eval`
        // trampolining mechanism, so we still have to guard against that and
        // terminate at some point.
        type DeferredOption[Case] = OptionT[Eval, Case]

        case class State(
            decisionStagesToGuideShrinkage: Option[DecisionStages],
            decisionStagesInReverseOrder: DecisionStagesInReverseOrder,
            complexity: Int
        ) {
          def update(
              remainingGuidance: Option[DecisionStages],
              decision: Decision
          ): State = copy(
            decisionStagesToGuideShrinkage = remainingGuidance,
            decisionStagesInReverseOrder =
              decisionStagesInReverseOrder.addLatest(decision),
            complexity = 1 + complexity
          )
        }

        object State {
          val initial = new State(
            decisionStagesToGuideShrinkage = decisionStagesToGuideShrinkage,
            decisionStagesInReverseOrder = NoDecisionStages,
            complexity = 0
          )
        }

        type StateUpdating[Case] =
          StateT[DeferredOption, State, Case]

        // NASTY HACK: what follows is a hacked alternative to using the reader
        // monad whereby the injected context is *mutable*, but at least it's
        // buried in the interpreter for `GenerationOperation`, expressed as a
        // closure over `randomBehaviour`. The reified `FiltrationResult` values
        // are also handled by the interpreter too. Read 'em and weep!

        sealed trait Possibilities

        case class Choices(possibleIndices: LazyList[Int]) extends Possibilities

        val possibilitiesThatFollowSomeChoiceOfDecisionStages =
          mutable.Map.empty[DecisionStagesInReverseOrder, Possibilities]

        def interpreter(depth: Int): GenerationOperation ~> StateUpdating =
          new (GenerationOperation ~> StateUpdating) {
            override def apply[Case](
                generationOperation: GenerationOperation[Case]
            ): StateUpdating[Case] =
              generationOperation match {
                case Choice(choicesByCumulativeFrequency) =>
                  val numberOfChoices =
                    choicesByCumulativeFrequency.keys.lastOption.getOrElse(0)
                  if (0 < numberOfChoices)
                    StateT
                      .get[DeferredOption, State]
                      .flatMap(state =>
                        state.decisionStagesToGuideShrinkage match {
                          case Some(ChoiceOf(guideIndex) :: remainingGuidance)
                              if guideIndex < numberOfChoices =>
                            for {
                              _ <- StateT
                                .set[DeferredOption, State](
                                  state.update(
                                    Some(remainingGuidance),
                                    ChoiceOf(guideIndex)
                                  )
                                )
                            } yield choicesByCumulativeFrequency
                              .minAfter(1 + guideIndex)
                              .get
                              ._2
                          case _ =>
                            for {
                              _ <- liftUnitIfTheComplexityIsNotTooLarge(state)
                              index #:: remainingPossibleIndices =
                                possibilitiesThatFollowSomeChoiceOfDecisionStages
                                  .get(
                                    state.decisionStagesInReverseOrder
                                  ) match {
                                  case Some(Choices(possibleIndices))
                                      if possibleIndices.nonEmpty =>
                                    possibleIndices
                                  case _ =>
                                    randomBehaviour
                                      .buildRandomSequenceOfDistinctIntegersFromZeroToOneLessThan(
                                        numberOfChoices
                                      )
                                }

                              _ <- StateT
                                .set[DeferredOption, State](
                                  state.update(None, ChoiceOf(index))
                                )
                            } yield {
                              possibilitiesThatFollowSomeChoiceOfDecisionStages(
                                state.decisionStagesInReverseOrder
                              ) = Choices(remainingPossibleIndices)
                              choicesByCumulativeFrequency
                                .minAfter(1 + index)
                                .get
                                ._2
                            }
                        }
                      )
                  else StateT.liftF(OptionT.none)

                case Factory(factory) =>
                  StateT
                    .get[DeferredOption, State]
                    .flatMap(state =>
                      state.decisionStagesToGuideShrinkage match {
                        case Some(
                              FactoryInputOf(guideIndex) :: remainingGuidance
                            )
                            if factory
                              .lowerBoundInput() <= guideIndex && factory
                              .upperBoundInput() >= guideIndex =>
                          val index = Math
                            .round(
                              factory.maximallyShrunkInput() + randomBehaviour
                                .nextDouble() * (guideIndex - factory
                                .maximallyShrunkInput())
                            )

                          for {
                            _ <- StateT.set[DeferredOption, State](
                              state.update(
                                Some(remainingGuidance),
                                FactoryInputOf(index)
                              )
                            )
                          } yield factory(index)
                        case _ =>
                          for {
                            _ <- liftUnitIfTheComplexityIsNotTooLarge(state)
                            input = {
                              val upperBoundInput: BigDecimal =
                                factory.upperBoundInput()
                              val lowerBoundInput: BigDecimal =
                                factory.lowerBoundInput()
                              val maximallyShrunkInput: BigDecimal =
                                factory.maximallyShrunkInput()

                              val maximumScale: BigDecimal =
                                upperBoundInput - lowerBoundInput

                              if (
                                scaleDeflationLevel.fold(true)(
                                  maximumScaleDeflationLevel > _
                                ) && 0 < maximumScale
                              ) {
                                // Calibrate the scale to come out at around one
                                // at maximum shrinkage, even though the guard
                                // clause above handles maximum shrinkage
                                // explicitly.
                                val scale: BigDecimal =
                                  scaleDeflationLevel.fold(maximumScale)(
                                    level =>
                                      maximumScale / Math.pow(
                                        maximumScale.toDouble,
                                        level.toDouble / maximumScaleDeflationLevel
                                      )
                                  )
                                val blend: BigDecimal = scale / maximumScale

                                val midPoint: BigDecimal =
                                  blend * (upperBoundInput + lowerBoundInput) / 2 + (1 - blend) * maximallyShrunkInput

                                val sign =
                                  if (randomBehaviour.nextBoolean()) 1 else -1

                                val delta: BigDecimal =
                                  sign * scale * randomBehaviour
                                    .nextDouble() / 2

                                (midPoint + delta)
                                  .setScale(
                                    0,
                                    BigDecimal.RoundingMode.HALF_EVEN
                                  )
                                  .rounded
                                  .toLong
                              } else { maximallyShrunkInput.toLong }
                            }
                            _ <- StateT.set[DeferredOption, State](
                              state.update(None, FactoryInputOf(input))
                            )
                          } yield factory(input)
                      }
                    )

                case FiltrationResult(result) =>
                  StateT.liftF(OptionT.fromOption(result))

                case NoteComplexity =>
                  for {
                    state <- StateT.get[DeferredOption, State]
                  } yield state.complexity

                case ResetComplexity(complexity)
                    if scaleDeflationLevel.isEmpty =>
                  for {
                    _ <- StateT.modify[DeferredOption, State](
                      _.copy(complexity = complexity)
                    )
                  } yield ()

                case ResetComplexity(_) =>
                  StateT.pure(())
              }

            private def liftUnitIfTheComplexityIsNotTooLarge[Case](
                state: State
            ): StateUpdating[Unit] = {
              // NOTE: this is called *prior* to the complexity being
              // potentially increased by one, hence the strong inequality
              // below; `complexityLimit` *is* inclusive.
              if (state.complexity < complexityLimit)
                StateT.pure(())
              else
                StateT.liftF[DeferredOption, State, Unit](
                  OptionT.none
                )
            }
          }

        {
          // NASTY HACK: what was previously a Java-style imperative iterator
          // implementation has, ahem, 'matured' into an overall imperative
          // iterator forwarding to another with a `collect` to flatten out the
          // `Option` part of the latter's output. Both co-operate by sharing
          // mutable state used to determine when the overall iterator should
          // stop yielding output. This in turn allows another hack, namely to
          // intercept calls to `forEach` on the overall iterator so that it can
          // monitor cases that don't pass inline filtration.
          var starvationCountdown: Int         = limit
          var backupOfStarvationCountdown      = 0
          var numberOfUniqueCasesProduced: Int = 0
          val potentialDuplicates =
            mutable.Set.empty[DecisionStagesInReverseOrder]

          val coreIterator =
            new Iterator[Option[(DecisionStagesInReverseOrder, Case)]] {
              private def remainingGap = limit - numberOfUniqueCasesProduced

              override def hasNext: Boolean =
                0 < remainingGap && 0 < starvationCountdown

              override def next()
                  : Option[(DecisionStagesInReverseOrder, Case)] =
                generation
                  .foldMap(interpreter(depth = 0))
                  .run(State.initial)
                  .value
                  .value match {
                  case Some((State(_, decisionStages, _), caze))
                      if potentialDuplicates.add(decisionStages) => {
                    {
                      numberOfUniqueCasesProduced += 1
                      backupOfStarvationCountdown = starvationCountdown
                      starvationCountdown = Math
                        .round(Math.sqrt(limit * remainingGap))
                        .toInt
                    }

                    Some(decisionStages -> caze)
                  }
                  case _ =>
                    { starvationCountdown -= 1 }

                    None
                }
            }.collect { case Some(caze) => caze }

          new Iterator[(DecisionStagesInReverseOrder, Case)] {
            override def hasNext: Boolean = coreIterator.hasNext

            override def next(): (DecisionStagesInReverseOrder, Case) =
              coreIterator.next()

            override def foreach[U](
                f: ((DecisionStagesInReverseOrder, Case)) => U
            ): Unit = {
              super.foreach { input =>
                try {
                  f(input)
                } catch {
                  case _: RejectionByInlineFilter =>
                    numberOfUniqueCasesProduced -= 1
                    starvationCountdown = backupOfStarvationCountdown - 1
                }
              }
            }
          }
        }
      }

      // Java-only API ...
      override def supplyTo(consumer: Consumer[Case]): Unit =
        supplyTo(consumer.accept)

      override def asIterator(): JavaIterator[Case] = {
        val randomBehaviour = new Random(734874)

        cases(
          casesLimit,
          complexityLimit,
          randomBehaviour,
          None,
          decisionStagesToGuideShrinkage = None
        )
          .map(_._2)
          .asJava
      }

      // Scala-only API ...
      override def supplyTo(consumer: Case => Unit): Unit = {

        val randomBehaviour = new Random(734874)

        // NOTE: prior to the commit when this comment was introduced, the
        // `shrink` function returned an `EitherT[Eval, TrialException, Unit]`
        // instead of throwing the final `TrialException`. That approach was
        // certainly more flexible - it permits exploring multiple shrinkages
        // and taking the best one, but for now we just go with the first shrunk
        // case from the calling step we find that hasn't been beaten by a
        // recursive call, and throw there and then.
        def shrink(
            caze: Case,
            throwable: Throwable,
            decisionStages: DecisionStages,
            shrinkageAttemptIndex: Int,
            scaleDeflationLevel: Int,
            casesLimit: Int,
            numberOfShrinksInPanicModeIncludingThisOne: Int,
            externalStoppingCondition: Case => Boolean
        ): Eval[Unit] = {
          require(0 <= numberOfShrinksInPanicModeIncludingThisOne)
          require(0 <= shrinkageAttemptIndex)

          if (
            shrinkageAttemptsLimit == shrinkageAttemptIndex || externalStoppingCondition(
              caze
            )
          )
            throw new TrialException(throwable) {
              override def provokingCase: Case = caze

              override def recipe: String = decisionStages.asJson.spaces4
            }

          require(shrinkageAttemptsLimit > shrinkageAttemptIndex)

          val potentialShrunkExceptionalOutcome: Eval[Unit] = {
            val numberOfDecisionStages = decisionStages.size

            if (0 < numberOfDecisionStages) {
              // NOTE: there's some voodoo in choosing the exponential scaling
              // factor - if it's too high, say 2, then the solutions are hardly
              // shrunk at all. If it is unity, then the solutions are shrunk a
              // bit but can be still involve overly 'large' values, in the
              // sense that the factory input values are large. This needs
              // finessing, but will do for now...
              val limitWithExtraLeewayThatHasBeenObservedToFindBetterShrunkCases =
                (100 * casesLimit / 99) max casesLimit

              val outcomes: LazyList[Eval[Unit]] =
                LazyList
                  .from(
                    cases(
                      limitWithExtraLeewayThatHasBeenObservedToFindBetterShrunkCases,
                      numberOfDecisionStages,
                      randomBehaviour,
                      scaleDeflationLevel = Some(scaleDeflationLevel),
                      decisionStagesToGuideShrinkage = Option.when(
                        0 < numberOfShrinksInPanicModeIncludingThisOne
                      )(decisionStages)
                    )
                  )
                  .collect {
                    case (
                          _,
                          potentialShrunkCase
                        )
                        // NOTE: we have to make sure that the calling
                        // invocation of `shrink` was also in panic more, as it
                        // is legitimate for the first panic shrinkage to arrive
                        // at the same result as a non-panic calling invocation,
                        // and this does indeed occur for some non-trivial panic
                        // mode shrinkage sequences at the start of panic mode.
                        // Otherwise if we were already in panic mode in the
                        // calling invocation, this is as sign that there is
                        // nothing left to usefully shrink down, as otherwise
                        // the failure won't be provoked at all.
                        if 1 < numberOfShrinksInPanicModeIncludingThisOne && caze == potentialShrunkCase =>
                      throw new TrialException(throwable) {
                        override def provokingCase: Case = caze

                        override def recipe: String =
                          decisionStages.asJson.spaces4
                      }
                    case (
                          decisionStagesForPotentialShrunkCaseInReverseOrder,
                          potentialShrunkCase
                        ) if caze != potentialShrunkCase =>
                      try {
                        Eval.now(consumer(potentialShrunkCase))
                      } catch {
                        case rejection: RejectionByInlineFilter =>
                          throw rejection
                        case throwableFromPotentialShrunkCase: Throwable =>
                          val decisionStagesForPotentialShrunkCase =
                            decisionStagesForPotentialShrunkCaseInReverseOrder.reverse

                          assert(
                            decisionStagesForPotentialShrunkCase.size <= numberOfDecisionStages
                          )

                          val lessComplex =
                            decisionStagesForPotentialShrunkCase.size < numberOfDecisionStages

                          val stillEnoughRoomToDecreaseScale =
                            scaleDeflationLevel < maximumScaleDeflationLevel

                          if (lessComplex || stillEnoughRoomToDecreaseScale) {
                            val scaleDeflationLevelForRecursion =
                              if (!lessComplex)
                                1 + scaleDeflationLevel
                              else scaleDeflationLevel

                            Eval.defer {
                              shrink(
                                caze = potentialShrunkCase,
                                throwable = throwableFromPotentialShrunkCase,
                                decisionStages =
                                  decisionStagesForPotentialShrunkCase,
                                shrinkageAttemptIndex =
                                  1 + shrinkageAttemptIndex,
                                scaleDeflationLevel =
                                  scaleDeflationLevelForRecursion,
                                casesLimit =
                                  limitWithExtraLeewayThatHasBeenObservedToFindBetterShrunkCases,
                                numberOfShrinksInPanicModeIncludingThisOne = 0,
                                externalStoppingCondition =
                                  externalStoppingCondition
                              )
                            }
                          } else
                            throw new TrialException(
                              throwableFromPotentialShrunkCase
                            ) {
                              override def provokingCase: Case =
                                potentialShrunkCase

                              override def recipe: String =
                                decisionStagesForPotentialShrunkCase.asJson.spaces4
                            }
                      }
                  }

              val potentialExceptionalOutcome: Eval[Unit] = {
                def yieldTheFirstExceptionalOutcomeIfPossible(
                    outcomes: LazyList[Eval[Unit]]
                ): Eval[Unit] =
                  if (outcomes.nonEmpty)
                    outcomes.head.flatMap(_ =>
                      yieldTheFirstExceptionalOutcomeIfPossible(outcomes.tail)
                    )
                  else Eval.now(())

                yieldTheFirstExceptionalOutcomeIfPossible(outcomes)
              }

              potentialExceptionalOutcome.flatMap(_ =>
                // At this point, slogging through the potential shrunk cases
                // failed to find any failures; go into (or remain in) panic
                // mode...
                shrink(
                  caze = caze,
                  throwable = throwable,
                  decisionStages = decisionStages,
                  shrinkageAttemptIndex = 1 + shrinkageAttemptIndex,
                  scaleDeflationLevel = scaleDeflationLevel,
                  casesLimit =
                    limitWithExtraLeewayThatHasBeenObservedToFindBetterShrunkCases,
                  numberOfShrinksInPanicModeIncludingThisOne =
                    1 + numberOfShrinksInPanicModeIncludingThisOne,
                  externalStoppingCondition = externalStoppingCondition
                )
              )
            } else
              Eval.now(())
          }

          potentialShrunkExceptionalOutcome.flatMap(_ =>
            // At this point the recursion hasn't found a failing case, so we
            // call it a day and go with the best we've got from further up the
            // call chain...

            throw new TrialException(throwable) {
              override def provokingCase: Case = caze

              override def recipe: String = decisionStages.asJson.spaces4
            }
          )
        }

        cases(
          casesLimit,
          complexityLimit,
          randomBehaviour,
          None,
          decisionStagesToGuideShrinkage = None
        )
          .foreach { case (decisionStagesInReverseOrder, caze) =>
            try {
              consumer(caze)
            } catch {
              case rejection: RejectionByInlineFilter => throw rejection
              case throwable: Throwable =>
                shrink(
                  caze = caze,
                  throwable = throwable,
                  decisionStages = decisionStagesInReverseOrder.reverse,
                  shrinkageAttemptIndex = 0,
                  scaleDeflationLevel = 0,
                  casesLimit = casesLimit,
                  numberOfShrinksInPanicModeIncludingThisOne = 0,
                  externalStoppingCondition = shrinkageStop()
                ).value // Evaluating the nominal `Unit` result will throw a `TrialsException`.
            }
          }
      }
    }

  // Java-only API ...
  override def map[TransformedCase](
      transform: JavaFunction[Case, TransformedCase]
  ): TrialsImplementation[TransformedCase] = map(transform.apply)

  override def flatMap[TransformedCase](
      step: JavaFunction[Case, JavaTrials[TransformedCase]]
  ): TrialsImplementation[TransformedCase] =
    flatMap(step.apply _ andThen (_.scalaTrials))

  override def filter(
      predicate: Predicate[Case]
  ): TrialsImplementation[Case] =
    filter(predicate.test)

  def mapFilter[TransformedCase](
      filteringTransform: JavaFunction[Case, Optional[TransformedCase]]
  ): TrialsImplementation[TransformedCase] =
    mapFilter(filteringTransform.apply _ andThen {
      case withPayload if withPayload.isPresent => Some(withPayload.get())
      case _                                    => None
    })

  // Scala-only API ...
  override def map[TransformedCase](
      transform: Case => TransformedCase
  ): TrialsImplementation[TransformedCase] =
    TrialsImplementation(generation map transform)

  override def filter(
      predicate: Case => Boolean
  ): TrialsImplementation[Case] = {
    flatMap(caze =>
      new TrialsImplementation(
        FiltrationResult(Some(caze).filter(predicate))
      ): ScalaTrials[Case]
    )
  }

  override def mapFilter[TransformedCase](
      filteringTransform: Case => Option[TransformedCase]
  ): TrialsImplementation[TransformedCase] =
    flatMap(caze =>
      new TrialsImplementation(
        FiltrationResult(filteringTransform(caze))
      ): ScalaTrials[TransformedCase]
    )

  def this(
      generationOperation: TrialsImplementation.GenerationOperation[Case]
  ) = {
    this(liftF(generationOperation))
  }

  override def flatMap[TransformedCase](
      step: Case => ScalaTrials[TransformedCase]
  ): TrialsImplementation[TransformedCase] = {
    val adaptedStep = (step andThen (_.generation))
      .asInstanceOf[Case => Generation[TransformedCase]]
    TrialsImplementation(generation flatMap adaptedStep)
  }

  def withRecipe(
      recipe: String
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    new JavaTrialsScaffolding.SupplyToSyntax[Case]
      with ScalaTrialsScaffolding.SupplyToSyntax[Case] {
      // Java-only API ...
      override def supplyTo(consumer: Consumer[Case]): Unit =
        supplyTo(consumer.accept)

      override def asIterator(): JavaIterator[Case] = Seq {
        val decisionStages = parseDecisionIndices(recipe)
        reproduce(decisionStages)
      }.asJava.iterator()

      // Scala-only API ...
      override def supplyTo(consumer: Case => Unit): Unit = {
        val decisionStages = parseDecisionIndices(recipe)
        val reproducedCase = reproduce(decisionStages)

        try {
          consumer(reproducedCase)
        } catch {
          case exception: Throwable =>
            throw new TrialException(exception) {
              override def provokingCase: Case = reproducedCase

              override def recipe: String = decisionStages.asJson.spaces4
            }
        }
      }
    }

  override def and[Case2](
      secondTrials: JavaTrials[Case2]
  ): JavaTrialsScaffolding.Tuple2Trials[Case, Case2] =
    new JavaTuple2Trials(this, secondTrials)

  override def and[Case2](
      secondTrials: ScalaTrials[Case2]
  ): ScalaTrialsScaffolding.Tuple2Trials[Case, Case2] =
    new ScalaTuple2Trials(this, secondTrials)

  protected override def several[Collection](
      builderFactory: => Builder[Case, Collection]
  ): TrialsImplementation[Collection] = {
    def addItems(partialResult: List[Case]): TrialsImplementation[Collection] =
      scalaApi.alternate(
        scalaApi.only {
          val builder = builderFactory
          partialResult.foreach(builder add _)
          builder.build()
        },
        flatMap(item =>
          addItems(item :: partialResult): ScalaTrials[Collection]
        )
      )

    addItems(Nil)
  }

  override def several[Collection](implicit
      factory: scala.collection.Factory[Case, Collection]
  ): TrialsImplementation[Collection] = several(new Builder[Case, Collection] {
    private val underlyingBuilder = factory.newBuilder

    override def add(caze: Case): Unit = {
      underlyingBuilder += caze
    }

    override def build(): Collection = underlyingBuilder.result()
  })

  override def lists: TrialsImplementation[List[Case]] = several

  override def sets: TrialsImplementation[Set[_ <: Case]] = several

  override def sortedSets(implicit
      ordering: Ordering[_ >: Case]
  ): TrialsImplementation[SortedSet[_ <: Case]] = several(
    new Builder[Case, SortedSet[_ <: Case]] {
      val underlyingBuilder: mutable.Builder[Case, SortedSet[Case]] =
        SortedSet.newBuilder(ordering.asInstanceOf[Ordering[Case]])

      override def add(caze: Case): Unit = {
        underlyingBuilder += caze
      }

      override def build(): SortedSet[_ <: Case] = underlyingBuilder.result()
    }
  )

  override def maps[Value](
      values: ScalaTrials[Value]
  ): TrialsImplementation[Map[Case, Value]] =
    flatMap(key => values.map(key -> _)).several[Map[Case, Value]]

  override def sortedMaps[Value](values: ScalaTrials[Value])(implicit
      ordering: Ordering[_ >: Case]
  ): TrialsImplementation[SortedMap[Case, Value]] = {
    flatMap(key => values.map(key -> _)).lists
      .map[SortedMap[Case, Value]](
        SortedMap
          .from[Case, Value](_)(
            ordering.asInstanceOf[Ordering[Case]]
          ): SortedMap[Case, Value]
      )
  }

  protected override def lotsOfSize[Collection](
      size: Int,
      builderFactory: => Builder[Case, Collection]
  ): TrialsImplementation[Collection] =
    scalaApi.complexities.flatMap(complexity => {
      def addItems(
          numberOfItems: Int,
          partialResult: List[Case]
      ): ScalaTrials[Collection] =
        if (0 >= numberOfItems)
          scalaApi.only {
            val builder = builderFactory
            partialResult.foreach(builder add _)
            builder.build()
          }
        else
          flatMap(item =>
            (scalaApi
              .resetComplexity(complexity): ScalaTrials[Unit])
              .flatMap(_ =>
                addItems(
                  numberOfItems - 1,
                  item :: partialResult
                )
              )
          )

      addItems(size, Nil)
    })

  override def lotsOfSize[Collection](size: Int)(implicit
      factory: collection.Factory[Case, Collection]
  ): TrialsImplementation[Collection] = lotsOfSize(
    size,
    new Builder[Case, Collection] {
      private val underlyingBuilder = factory.newBuilder

      override def add(caze: Case): Unit = {
        underlyingBuilder += caze
      }

      override def build(): Collection = underlyingBuilder.result()
    }
  )

  override def listsOfSize(size: Int): ScalaTrials[List[Case]] = lotsOfSize(
    size
  )
}
