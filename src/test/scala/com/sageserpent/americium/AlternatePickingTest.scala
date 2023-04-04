package com.sageserpent.americium
import com.sageserpent.americium.Trials.api
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, times, verify}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inside, Inspectors}

import scala.collection.immutable.Seq
import scala.util.Random

object AlternatePickingTest {
  def factorial(value: BigInt): BigInt = {
    require(0 <= value)

    if (1 >= value) 1 else value * factorial(value - 1)
  }

  def provideSequencesTo(test: Seq[Seq[(Int, Int)]] => Unit) = {
    val randomBehaviour = new Random(83489L)

    for {
      numberOfSequences <- Seq(0, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4,
        4, 4, 5, 5, 6, 6, 7, 7, 8)
    } {
      val maximumSequenceLength = 8 - numberOfSequences

      val sequences = Seq.tabulate(numberOfSequences)(distinguishingMark =>
        Seq.fill(randomBehaviour.nextInt(1 + maximumSequenceLength))(
          distinguishingMark -> randomBehaviour.nextInt(5)
        )
      )

      test(sequences)
    }
  }
}

class AlternatePickingTest
    extends AnyFlatSpec
    with Matchers
    with Inside
    with Inspectors
    with MockitoSessionSupport {
  import AlternatePickingTest.*

  behavior of "pickAlternatelyFrom"

  it should "preserve the elements in the sequences" in {
    provideSequencesTo { sequences =>
      val expectedElements = sequences.flatten

      api.pickAlternatelyFrom(sequences: _*).withLimit(10).supplyTo { picked =>
        picked should contain theSameElementsAs expectedElements
      }
    }
  }

  it should "preserve the order of elements contributed from each sequence" in {
    provideSequencesTo { sequences =>
      api.pickAlternatelyFrom(sequences: _*).withLimit(10).supplyTo { picked =>
        for (distinguishingMark <- 0 until sequences.size) {
          picked.filter(
            distinguishingMark == _._1
          ) should contain theSameElementsInOrderAs sequences(
            distinguishingMark
          )
        }
      }
    }
  }

  it should "exhibit variation in how it picks from the same sequences" in {
    provideSequencesTo { sequences =>
      val sizes = sequences.map(_.size)

      val totalNumberOfElements = sizes.sum

      // NOTE: think of each sequence is being a combination of elements
      // selected out of the *result* - so the multinomial of selecting the
      // original sequence sizes from the result size is the number of ways of
      // splicing the sequences into the result.
      val numberOfWaysOfDistributingTheSequencesIntoTheResult =
        sizes
          .map(BigInt.apply)
          .map(factorial)
          .foldLeft(factorial(totalNumberOfElements))(_ / _)

      println(
        s"sizes: $sizes, number of possible interleaved results: $numberOfWaysOfDistributingTheSequencesIntoTheResult"
      )

      inMockitoSession {
        val consumer: Seq[(Int, Int)] => Unit =
          mock(classOf[Seq[(Int, Int)] => Unit])

        api
          .pickAlternatelyFrom(sequences: _*)
          .withLimit(numberOfWaysOfDistributingTheSequencesIntoTheResult.toInt)
          .supplyTo(consumer)

        verify(
          consumer,
          times(numberOfWaysOfDistributingTheSequencesIntoTheResult.toInt)
        ).apply(any[Seq[(Int, Int)]])
      }
    }
  }

  it should "cope with having many elements to pick from" in {
    val odds = 1 to 20001 by 2

    val evens = 0 to 16000 by 2

    inMockitoSession {
      val consumer: Seq[Int] => Unit =
        mock(classOf[Seq[Int] => Unit])

      val limit = 100
      
      api
        .pickAlternatelyFrom(odds, evens)
        .withLimit(limit)
        .supplyTo(consumer)

      verify(
        consumer,
        times(limit)
      ).apply(any[Seq[Int]])
    }
  }
}
