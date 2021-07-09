package lambdanet

import ammonite.{ops => amm}
import amm.{Path, RelPath}
import lambdanet.Annot.{Fixed, Missing, User}
import lambdanet.TypeInferenceService.ModelConfig

import scala.io.StdIn

object JavaAPI {
  def pwd: Path = amm.pwd

  def relPath(path: String): RelPath =
    RelPath(path)

  def absPath(path: String): Path =
    Path(path)

  def joinPath(head: Path, tail: String): Path = head / RelPath(tail)

  def defaultModelConfig: ModelConfig = ModelConfig()

  def predictionService(model: Model, numOfThreads: Int, predictTopK: Int) =
    model.PredictionService(numOfThreads, predictTopK)

  def readLine(): String = StdIn.readLine()

  def main(args: Array[String]): Unit = {
    println("This is a test main function.")
  }

  def tuplet[X, Y, Z](x: X, y: Y, z: Z): (X, Y, Z) =
    (x, y, z)

  def pair[X, Y](x: X, y: Y): (X, Y) =
    (x, y)

  def srcSpan(startLine: Int, startIndex: Int, endLine: Int, endIndex: Int, sourcePath: RelPath): SrcSpan =
    SrcSpan((startLine, startIndex),(endLine, endIndex), sourcePath)

  def userAnnotation[T](ty: T, inferred: Boolean): Annot[T] =
    User[T](ty, inferred)

  def fixed[T](ty: T): Annot[T] =
    Fixed[T](ty)

  def missing: Annot[GType] = Missing
}
