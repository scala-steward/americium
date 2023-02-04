package com.sageserpent.americium

import cats.data.State
import cats.effect.SyncIO
import cats.effect.kernel.Resource
import cats.free.Free
import cats.free.Free.liftF
import cats.implicits.*
import cats.~>
import com.google.common.collect.{Ordering as _, *}
import com.google.common.hash
import com.sageserpent.americium.TrialsApis.{javaApi, scalaApi}
import com.sageserpent.americium.TrialsScaffolding.ShrinkageStop
import com.sageserpent.americium.java.TrialsScaffolding.OptionalLimits
import com.sageserpent.americium.java.{
  Builder,
  CaseFactory,
  CaseSupplyCycle,
  CasesLimitStrategy,
  TestIntegrationContext,
  TrialsScaffolding as JavaTrialsScaffolding,
  TrialsSkeletalImplementation as JavaTrialsSkeletalImplementation
}
import com.sageserpent.americium.{
  Trials as ScalaTrials,
  TrialsScaffolding as ScalaTrialsScaffolding,
  TrialsSkeletalImplementation as ScalaTrialsSkeletalImplementation
}
import cyclops.control.Either as JavaEither
import fs2.Stream as Fs2Stream
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import org.rocksdb.*

import _root_.java.nio.file.Path
import _root_.java.util.function.{Consumer, Predicate, Function as JavaFunction}
import _root_.java.util.{
  ArrayList as JavaArrayList,
  Iterator as JavaIterator,
  Optional as JavaOptional
}
import scala.collection.immutable.SortedMap
import scala.jdk.CollectionConverters.*

object TrialsImplementation {
  val temporaryDirectoryJavaProperty = "java.io.tmpdir"

  val runDatabaseJavaPropertyName = "trials.runDatabase"

  val recipeHashJavaPropertyName = "trials.recipeHash"

  val runDatabaseDefault = "trialsRunDatabase"

  val minimumScaleDeflationLevel = 0

  val maximumScaleDeflationLevel = 50

  val rocksDbOptions = new DBOptions()
    .optimizeForSmallDb()
    .setCreateIfMissing(true)
    .setCreateMissingColumnFamilies(true)

  val columnFamilyOptions = new ColumnFamilyOptions()
    .setCompressionType(CompressionType.LZ4_COMPRESSION)
    .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)

  val defaultColumnFamilyDescriptor = new ColumnFamilyDescriptor(
    RocksDB.DEFAULT_COLUMN_FAMILY,
    columnFamilyOptions
  )

  val columnFamilyDescriptorForRecipeHashes = new ColumnFamilyDescriptor(
    "RecipeHashKeyRecipeValue".getBytes(),
    columnFamilyOptions
  )

  type DecisionStages   = List[Decision]
  type Generation[Case] = Free[GenerationOperation, Case]

  sealed trait Decision

  sealed trait GenerationOperation[Case]

  private[americium] trait GenerationSupport[+Case] {
    val generation: Generation[_ <: Case]
  }

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

  def rocksDbResource(
      readOnly: Boolean = false
  ): Resource[SyncIO, (RocksDB, ColumnFamilyHandle)] =
    Resource.make(acquire = SyncIO {
      Option(System.getProperty(temporaryDirectoryJavaProperty)).fold(ifEmpty =
        throw new RuntimeException(
          s"No definition of Java property: `$temporaryDirectoryJavaProperty`"
        )
      ) { directory =>
        val runDatabase = Option(
          System.getProperty(runDatabaseJavaPropertyName)
        ).getOrElse(runDatabaseDefault)

        val columnFamilyDescriptors =
          ImmutableList.of(
            defaultColumnFamilyDescriptor,
            columnFamilyDescriptorForRecipeHashes
          )

        val columnFamilyHandles = new JavaArrayList[ColumnFamilyHandle]()

        val rocksDB =
          if (readOnly)
            RocksDB.openReadOnly(
              rocksDbOptions,
              Path
                .of(directory)
                .resolve(runDatabase)
                .toString,
              columnFamilyDescriptors,
              columnFamilyHandles
            )
          else
            RocksDB.open(
              rocksDbOptions,
              Path
                .of(directory)
                .resolve(runDatabase)
                .toString,
              columnFamilyDescriptors,
              columnFamilyHandles
            )

        rocksDB -> columnFamilyHandles.get(1)
      }
    })(release = { case (rocksDB, columnFamilyForRecipeHashes) =>
      SyncIO {
        columnFamilyForRecipeHashes.close()
        rocksDB.close()
      }
    })

  def parseDecisionIndices(recipe: String): DecisionStages = {
    decode[DecisionStages](
      recipe
    ).toTry.get // Just throw the exception, the callers are written in Java style.
  }
}

case class TrialsImplementation[Case](
    override val generation: TrialsImplementation.Generation[_ <: Case]
) extends ScalaTrialsSkeletalImplementation[Case]
    with JavaTrialsSkeletalImplementation[Case] {
  thisTrialsImplementation =>

  override type SupplySyntaxType = ScalaTrialsScaffolding.SupplyToSyntax[Case]

  import TrialsImplementation.*

  override def trials: TrialsImplementation[Case] = this

  override def scalaTrials: TrialsImplementation[Case] = this

  override def javaTrials: TrialsImplementation[Case] = this

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

  override def withLimit(
      limit: Int
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withLimits(casesLimit = limit)

  override def withLimits(
      casesLimit: Int,
      optionalLimits: OptionalLimits
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withLimits(
      casesLimit = casesLimit,
      complexityLimit = optionalLimits.complexity,
      shrinkageAttemptsLimit = optionalLimits.shrinkageAttempts,
      shrinkageStop = { () =>
        val predicate: Predicate[_ >: Case] =
          JavaTrialsScaffolding.noStopping.build()

        predicate.test _
      }
    )

  override def withStrategy(
      casesLimitStrategyFactory: JavaFunction[
        CaseSupplyCycle,
        CasesLimitStrategy
      ],
      optionalLimits: OptionalLimits
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withStrategy(
      casesLimitStrategyFactory = casesLimitStrategyFactory.apply,
      complexityLimit = optionalLimits.complexity,
      shrinkageAttemptsLimit = optionalLimits.shrinkageAttempts,
      shrinkageStop = { () =>
        val predicate: Predicate[_ >: Case] =
          JavaTrialsScaffolding.noStopping.build()

        predicate.test _
      }
    )

  override def withLimits(
      casesLimit: Int,
      optionalLimits: OptionalLimits,
      shrinkageStop: JavaTrialsScaffolding.ShrinkageStop[_ >: Case]
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withLimits(
      casesLimit = casesLimit,
      complexityLimit = optionalLimits.complexity,
      shrinkageAttemptsLimit = optionalLimits.shrinkageAttempts,
      shrinkageStop = { () =>
        val predicate = shrinkageStop.build()

        predicate.test _
      }
    )

  override def withStrategy(
      casesLimitStrategyFactory: JavaFunction[
        CaseSupplyCycle,
        CasesLimitStrategy
      ],
      optionalLimits: OptionalLimits,
      shrinkageStop: JavaTrialsScaffolding.ShrinkageStop[_ >: Case]
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    withStrategy(
      casesLimitStrategyFactory = casesLimitStrategyFactory.apply,
      complexityLimit = optionalLimits.complexity,
      shrinkageAttemptsLimit = optionalLimits.shrinkageAttempts,
      shrinkageStop = { () =>
        val predicate = shrinkageStop.build()

        predicate.test _
      }
    )

  override def withLimits(
      casesLimit: Int,
      complexityLimit: Int,
      shrinkageAttemptsLimit: Int,
      shrinkageStop: ScalaTrialsScaffolding.ShrinkageStop[Case]
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] = {
    val simpleStrategyFactory: CaseSupplyCycle => CasesLimitStrategy = _ =>
      new CasesLimitStrategy {
        private var starvationCountdown: Int         = casesLimit
        private var backupOfStarvationCountdown      = 0
        private var numberOfUniqueCasesProduced: Int = 0

        override def moreToDo() =
          0 < remainingGap() && 0 < starvationCountdown

        override def noteRejectionOfCase() = {
          numberOfUniqueCasesProduced -= 1
          starvationCountdown = backupOfStarvationCountdown - 1
        }

        override def noteEmissionOfCase(): Unit = {
          backupOfStarvationCountdown = starvationCountdown
          starvationCountdown = Math
            .round(
              Math.sqrt(
                casesLimit.toDouble * remainingGap()
              )
            )
            .toInt
          numberOfUniqueCasesProduced += 1
        }

        override def noteStarvation(): Unit = {
          starvationCountdown -= 1
        }

        private def remainingGap() =
          casesLimit - numberOfUniqueCasesProduced
      }

    withStrategy(
      simpleStrategyFactory,
      complexityLimit,
      shrinkageAttemptsLimit,
      shrinkageStop
    )
  }

  override def withStrategy(
      casesLimitStrategyFactory: CaseSupplyCycle => CasesLimitStrategy,
      complexityLimit: Int,
      shrinkageAttemptsLimit: Int,
      shrinkageStop: ShrinkageStop[
        Case
      ]
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] = {
    case class SupplyToSyntaxImplementation(
        casesLimitStrategyFactory: CaseSupplyCycle => CasesLimitStrategy,
        complexityLimit: Int,
        shrinkageAttemptsLimit: Int,
        seed: Long,
        shrinkageStop: ShrinkageStop[Case],
        generation: Generation[_ <: Case]
    ) extends SupplyToSyntaxSkeletalImplementation[Case] {
      override protected def reproduce(
          decisionStages: DecisionStages
      ): Case = thisTrialsImplementation.reproduce(decisionStages)

      protected override def raiseTrialException(
          rocksDb: Option[(RocksDB, ColumnFamilyHandle)]
      )(
          throwable: Throwable,
          caze: Case,
          decisionStages: DecisionStages
      ): StreamedCases = {
        val json = decisionStages.asJson.spaces4
        val jsonHashInHexadecimal = hash.Hashing
          .murmur3_128()
          .hashUnencodedChars(json)
          .toString

        // TODO: suppose this throws an exception? Probably best to
        // just log it and carry on, as the user wants to see a test
        // failure rather than an issue with the database.
        rocksDb.foreach { case (rocksDb, columnFamilyForRecipeHashes) =>
          rocksDb.put(
            columnFamilyForRecipeHashes,
            jsonHashInHexadecimal.map(_.toByte).toArray,
            json.map(_.toByte).toArray
          )
        }

        Fs2Stream.raiseError[SyncIO](new TrialException(throwable) {
          override def provokingCase: Case = caze

          override def recipe: String = json

          override def recipeHash: String = jsonHashInHexadecimal
        })
      }

      override def withSeed(
          seed: Long
      ): JavaTrialsScaffolding.SupplyToSyntax[
        Case
      ] with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
        copy(seed = seed)

      // Java-only API ...
      override def withStoppingCondition(
          shrinkageStop: JavaTrialsScaffolding.ShrinkageStop[
            _ >: Case
          ]
      ): JavaTrialsScaffolding.SupplyToSyntax[
        Case
      ] with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
        copy(shrinkageStop = { () =>
          val predicate = shrinkageStop.build()

          predicate.test _
        })

      // Scala-only API ...
      override def withShrinkageStop(
          shrinkageStop: ShrinkageStop[Case]
      ): JavaTrialsScaffolding.SupplyToSyntax[Case]
        with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
        copy(shrinkageStop = shrinkageStop)

    }

    SupplyToSyntaxImplementation(
      casesLimitStrategyFactory = casesLimitStrategyFactory,
      complexityLimit = complexityLimit,
      shrinkageAttemptsLimit = shrinkageAttemptsLimit,
      seed = 734874L,
      shrinkageStop = shrinkageStop,
      generation = generation
    )
  }

  def this(
      generationOperation: TrialsImplementation.GenerationOperation[Case]
  ) = {
    this(liftF(generationOperation))
  }

  def withRecipe(
      recipe: String
  ): JavaTrialsScaffolding.SupplyToSyntax[Case]
    with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
    new JavaTrialsScaffolding.SupplyToSyntax[Case]
      with ScalaTrialsScaffolding.SupplyToSyntax[Case] {
      override def withSeed(
          seed: Long
      ): JavaTrialsScaffolding.SupplyToSyntax[
        Case
      ] with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
        this // Seeding has no effect, as the reproduction is deterministic according to `recipe`.

      // Java-only API ...
      override def withStoppingCondition(
          shrinkageStop: JavaTrialsScaffolding.ShrinkageStop[
            _ >: Case
          ]
      ): JavaTrialsScaffolding.SupplyToSyntax[
        Case
      ] with ScalaTrialsScaffolding.SupplyToSyntax[Case] =
        this // Shrinkage does not take place when reproducing a test case.

      override def supplyTo(consumer: Consumer[Case]): Unit =
        supplyTo(consumer.accept)

      override def asIterator(): JavaIterator[Case] = Seq {
        val decisionStages = parseDecisionIndices(recipe)
        reproduce(decisionStages)
      }.asJava.iterator()

      override def testIntegrationContexts()
          : JavaIterator[TestIntegrationContext[Case]] =
        Seq({
          val decisionStages = parseDecisionIndices(recipe)
          val caze           = reproduce(decisionStages)

          TestIntegrationContextImplementation[Case](
            caze = caze,
            caseFailureReporting = { (throwable: Throwable) => },
            inlinedCaseFiltration = {
              (
                  runnable: Runnable,
                  additionalExceptionsToHandleAsFiltration: Array[
                    Class[_ <: Throwable]
                  ]
              ) =>
                runnable.run()
                true
            },
            isPartOfShrinkage = false
          )
        }: TestIntegrationContext[Case]).asJava.iterator()

      // Scala-only API ...
      override def withShrinkageStop(
          shrinkageStop: ShrinkageStop[
            Case
          ]
      ): JavaTrialsScaffolding.SupplyToSyntax[
        Case
      ] with ScalaTrialsScaffolding.SupplyToSyntax[Case] = this

      override def supplyTo(consumer: Case => Unit): Unit = {
        val decisionStages = parseDecisionIndices(recipe)
        val reproducedCase = reproduce(decisionStages)

        try {
          consumer(reproducedCase)
        } catch {
          case exception: Throwable =>
            val json = decisionStages.asJson.spaces4

            throw new TrialException(exception) {
              override def provokingCase: Case = reproducedCase

              override def recipe: String = json

              // NOTE: as we are reproducing using a recipe, we can assume that
              // the recipe hash is already stored from back when the recipe was
              // generated.
              override def recipeHash: String = json.hashCode().toHexString
            }
        }
      }
    }

  // Scala-only API ...
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

  protected def lotsOfSize[Collection](
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

  override def or[Case2](
      alternativeTrials: ScalaTrials[Case2]
  ): TrialsImplementation[Either[Case, Case2]] = scalaApi.alternate(
    this.map(Either.left[Case, Case2]),
    alternativeTrials.map(Either.right)
  )

  override def or[Case2](
      alternativeTrials: java.Trials[Case2]
  ): TrialsImplementation[
    JavaEither[Case, Case2]
  ] = javaApi.alternate(
    this.map(JavaEither.left[Case, Case2]),
    alternativeTrials.map(JavaEither.right[Case, Case2])
  )

  override def options: TrialsImplementation[Option[Case]] =
    scalaApi.alternate(scalaApi.only(None), this.map(Some.apply[Case]))

  override def optionals(): TrialsImplementation[
    JavaOptional[Case]
  ] = javaApi.alternate(
    javaApi.only(JavaOptional.empty()),
    this.map(JavaOptional.of[Case])
  )
}
