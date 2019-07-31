package lambdanet.architecture

import botkop.numsca.Tensor
import funcdiff._

import scala.collection.GenSeq
import lambdanet._
import lambdanet.PrepareRepos.ParsedRepos
import lambdanet.translation.PredicateGraph._

trait LabelEncoder {
  def name: String

  def encode(labels: GenSeq[Symbol]): Symbol => CompNode
}

object LabelEncoder {

  case class RandomLabelEncoder(architecture: NNArchitecture)
      extends LabelEncoder {
    def name: String = "RandomLabelEncoder"

    def encode(labels: GenSeq[Symbol]): Symbol => CompNode = {
      val map = labels.map { l =>
        l -> const(architecture.randomUnitVec())
      }.toMap
      map.apply
    }
  }

  case class ConstantLabelEncoder(architecture: NNArchitecture)
      extends LabelEncoder {
    def name: String = "ConstLabelEncoder"

    private val zeroVec: Tensor = architecture.zeroVec()

    def encode(labels: GenSeq[Symbol]): Symbol => CompNode = s => {
      zeroVec
    }
  }

  import scala.collection.GenSeq

  case class TrainableLabelEncoder(
      repos: ParsedRepos,
      coverageGoal: Double,
      architecture: NNArchitecture,
  ) extends LabelEncoder {
    import cats.implicits._
    import repos._

    def name: String = "TrainableLabelEncoder"

    private val labelsMap: Map[Symbol, CompNode] = {
      val totalUsages = trainSet.foldMap { p =>
        val predsUsage = p.pGraph.predicates.toVector.collect {
          case DefineRel(_, expr) =>
            expr.allLabels.toVector.foldMap(nameUsages)
          case HasName(_, name) =>
            nameUsages(name)
        }.combineAll

        val annotsUsage = p.userAnnots.toVector.foldMap {
          case (_, t) => t.allLabels.toVector.foldMap(nameUsages)
        }

        Vector(predsUsage, annotsUsage).combineAll
      }

      val (labels, achieved) =
        SM.selectBasedOnFrequency(totalUsages.toSeq, coverageGoal)
      printResult(s"number of labels selected: ${labels.length}")
      printResult(s"coverage achieved: $achieved")
      printResult(s"Fist 100 labels: ${labels.take(100)}")

      labels.map {
        case (s, _) =>
          s -> architecture.randomVar('label / s)
      }.toMap
    }

    def isLibLabel(label: Symbol): Boolean = labelsMap.contains(label)

    def encode(labels: GenSeq[Symbol]): Symbol => CompNode =
      DebugTime.logTime("encode labels") {
        val unknownLabel = architecture.randomVar('label / '?)
        labels
          .map { l =>
            l -> labelsMap.getOrElse(l, unknownLabel)
          }
          .toMap
          .apply
      }

    private def nameUsages(name: Symbol): Map[Symbol, Int] = {
      Map(name -> 1)
    }
  }

  case class SegmentedLabelEncoder(
      repos: ParsedRepos,
      coverageGoal: Double,
      architecture: NNArchitecture,
  ) extends LabelEncoder {
    import cats.implicits._
    import repos._

    def name: String = "SegmentedLabelEncoder"

    private val segmentsMap: Map[Segment, CompNode] = {

      val totalUsages = trainSet.foldMap { p =>
        val predsUsage = p.pGraph.predicates.toVector.collect {
          case DefineRel(_, expr) =>
            expr.allLabels.toVector.foldMap(nameUsages)
          case HasName(_, name) =>
            nameUsages(name)
        }.combineAll

        val annotsUsage = p.userAnnots.toVector.foldMap {
          case (_, t) => t.allLabels.toVector.foldMap(nameUsages)
        }

        Vector(predsUsage, annotsUsage).combineAll
      }

      val (segments, achieved) =
        SM.selectBasedOnFrequency(totalUsages.toSeq, coverageGoal)
      printResult(s"number of segments selected: ${segments.length}")
      printResult(s"coverage achieved: $achieved")
      printResult(s"Fist 100 segs: ${segments.take(100)}")

      segments.map {
        case (s, _) =>
          s -> architecture.randomUnitVar("segments" / s.symbol)
      }.toMap
    }

    def encode(labels: GenSeq[Symbol]): Symbol => CompNode =
      DebugTime.logTime("encode labels") {
        val zeroVec = architecture.zeroVec()
        val unknownSegment = architecture.randomVar('segments / 'UNKNOWN)

        def encodeSeg(seg: Segment): CompNode = {
          segmentsMap.getOrElse(seg, unknownSegment)
        }

        labels
          .map { l =>
            l -> segmentName(l)
              .map(encodeSeg)
              .pipe(totalSafe(_, zeroVec))
          }
          .toMap
          .apply
      }

    case class Segment(symbol: Symbol)
    def segmentName(symbol: Symbol): Vector[Segment] = {
      def splitCamelCase(s: String): Vector[Segment] = {
        s.replaceAll(
            String.format(
              "%s|%s|%s",
              "(?<=[A-Z])(?=[A-Z][a-z])",
              "(?<=[^A-Z])(?=[A-Z])",
              "(?<=[A-Za-z])(?=[^A-Za-z])",
            ),
            " ",
          )
          .split("\\s+")
          .map(s => Segment(Symbol(s.toLowerCase.replaceAll("[0-9]", ""))))
          .toVector
      }

      symbol.name.split("_+").toVector.flatMap(splitCamelCase)
    }

    def nameUsages(name: Symbol): Map[Segment, Int] = {
      segmentName(name).foldMap(s => Map(s -> 1))
    }
  }
}
