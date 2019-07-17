package lambdanet

import ammonite.ops._
import funcdiff.SimpleMath
import lambdanet.translation.ImportsResolution.{ErrorHandler, ModuleExports}
import lambdanet.translation._
import lambdanet.translation.PredicateGraph.{
  PNode,
  PNodeAllocator,
  PType,
  ProjNode,
  TyPredicate,
}
import lambdanet.translation.QLang.QModule
import lambdanet.utils.ProgramParsing
import lambdanet.utils.ProgramParsing.GProject

import scala.collection.mutable
import scala.util.Random

@SerialVersionUID(2)
case class LibDefs(
    nodeForAny: PNode,
    baseCtx: ModuleExports,
    nodeMapping: Map[PNode, PAnnot],
    libExports: Map[ProjectPath, ModuleExports],
)

object PrepareRepos {
  val libDefsFile: Path = pwd / up / "lambda-repos" / "libDefs.serialized"
  val parsedRepoPath: Path = pwd / "data" / "predicateGraphs.serialized"

  def parseRepos(
      trainSetDir: Path,
      devSetDir: Path,
      loadFromFile: Boolean = true,
  ): ParsedRepos = {
    lambdanet.shouldWarn = false

    val libDefs = if (loadFromFile) {
      announced(s"loading library definitions from $libDefsFile...") {
        SimpleMath.readObjectFromFile[LibDefs](libDefsFile.toIO)
      }
    } else {
      val defs = parseLibDefs()
      SimpleMath.saveObjectToFile(libDefsFile.toIO)(defs)
      println(s"library definitions saved to $libDefsFile")
      defs
    }

    val random = new Random(1)
    def fromDir(dir: Path, maxNum: Int) =
      (ls ! dir)
        .filter(f => f.isDir)
        .pipe(random.shuffle(_))
        .take(maxNum)
        .par
        .map { f =>
          val (a, b, c) = prepareProject(libDefs, f)
          ParsedProject(f.relativeTo(dir), a, b, c)
        }
        .toList

    ParsedRepos(libDefs, fromDir(trainSetDir, 1000), fromDir(devSetDir, 1000))
  }

  def main(args: Array[String]): Unit = {
    val trainSetDir: Path = pwd / up / "lambda-repos" / "trainSet"
    val devSetDir: Path = pwd / up / "lambda-repos" / "devSet"
    val parsed = announced("parsePredGraphs")(
      parseRepos(trainSetDir, devSetDir, loadFromFile = false),
    )
    val stats = repoStatistics(parsed.trainSet ++ parsed.devSet)
    printResult(stats.headers.zip(stats.average).toString())

    announced(s"save data set to file: $parsedRepoPath") {
      SM.saveObjectToFile(parsedRepoPath.toIO)(parsed)
    }
  }

  @SerialVersionUID(0)
  case class ParsedProject(
      path: ProjectPath,
      pGraph: PredicateGraph,
      qModules: Vector[QModule],
      userAnnots: Map[ProjNode, PType],
  )

  @SerialVersionUID(1)
  case class ParsedRepos(
      libDefs: LibDefs,
      trainSet: List[ParsedProject],
      devSet: List[ParsedProject],
  )

  case class RepoStats(
      average: Vector[Double],
      data: Map[ProjectPath, Vector[Int]],
  ) {
    val libNodes = 1
    val projNodes = 2
    val annotations = 3
    val predicates = 4

    val headers: Vector[String] =
      Vector("libNodes", "projNodes", "annotations", "predicates")
  }

  def repoStatistics(results: Seq[ParsedProject]): RepoStats = {
    require(results.nonEmpty)

    val rows = results
      .map {
        case ParsedProject(path, graph, _, annots) =>
          val nLib = graph.nodes.count(_.fromLib)
          val nProj = graph.nodes.count(!_.fromLib)
          val nPred = graph.predicates.size
          val annotations = annots.size
          path -> Vector(nLib, nProj, annotations, nPred)
      }
      .sortBy(_._2.last)

    val (paths, vec) = rows.unzip
    val average = vec
      .reduce(_.zip(_).map { case (x, y) => x + y })
      .map(_.toDouble / paths.length)

    val data = rows.toMap
    RepoStats(average, data)
  }

  def parseLibDefs(): LibDefs = {
    import cats.implicits._

    val declarationsDir = pwd / up / "lambda-repos" / "declarations"

    println("parsing default module...")
    val (baseCtx, libAllocator, defaultMapping) =
      QLangTranslation.parseDefaultModule()
    println("default module parsed")

    println("parsing library modules...")
    val GProject(_, modules, mapping, subProjects, devDependencies) =
      ProgramParsing
        .parseGProjectFromRoot(declarationsDir, declarationFileMod = true)

    println("parsing PModules...")
    val pModules =
      modules.map(m => PLangTranslation.fromGModule(m, libAllocator))

    println("imports resolution...")
    val handler =
      ErrorHandler(ErrorHandler.StoreError, ErrorHandler.StoreError)

    val resolved1 = baseCtx.publicNamespaces.map {
      case (k, m) => (k: RelPath) -> m
    }
    val exports = ImportsResolution.resolveExports(
      ImportsResolution.ProjectInfo(
        pModules,
        baseCtx,
        resolved1,
        mapping,
        defaultPublicMode = true,
        devDependencies,
      ),
      errorHandler = handler,
      libAllocator.newDef,
      maxIterations = 5,
    )

    val baseCtx1 = baseCtx
    // todo: check if need to handle public modules (also the missing lodash)
//    val publicNamespaces = exports.values.flatMap {
//      _.publicNamespaces.map {
//        case (k, m) => k -> NameDef.namespaceDef(m)
//      }
//    }.toMap
//    val baseCtx1 = baseCtx |+| ModuleExports(publicSymbols = publicNamespaces)

    val namedExports = subProjects.map {
      case (name, path) =>
        name -> exports.getOrElse(
          path,
          exports.getOrElse(
            path / "index", {
              Console.err.println(
                s"Couldn't find Exports located at $path for $name, ignore this named project.",
              )
              ModuleExports.empty
            },
          ),
        )
    }
    handler.warnErrors()
    val libExports = exports ++ namedExports

    val qModules =
      pModules.map { m =>
        QLangTranslation.fromPModule(m, baseCtx1 |+| exports(m.path))
      }

    val anyNode = libAllocator.newNode(None, isType = true)

    val nodeMapping = defaultMapping ++
      qModules.flatMap(_.mapping) + (anyNode -> Annot.Missing)

    println("Declaration files parsed.")
    LibDefs(anyNode, baseCtx1, nodeMapping, libExports)
  }

  def pruneGraph(
      graph: PredicateGraph,
      needed: Set[ProjNode],
  ): PredicateGraph = {
    val predicates: Map[PNode, Set[TyPredicate]] = {
      graph.predicates
        .flatMap(p => p.allNodes.map(n => n -> p))
        .groupBy(_._1)
        .map { case (k, v) => k -> v.map(_._2) }
    }

    def neighbours(n: PNode): (Set[ProjNode], Set[TyPredicate]) = {
      val ps = predicates.getOrElse(n, Set())
      (ps.flatMap(_.allNodes) - n).filterNot(_.fromLib).map(ProjNode) -> ps
    }

    val toVisit = mutable.Queue(needed.toSeq: _*)
    var activeNodes = Set[ProjNode]()
    var newPredicates = Set[TyPredicate]()
    while (toVisit.nonEmpty) {
      val n = toVisit.dequeue()
      activeNodes += n
      val (ns, ps) = neighbours(n.n)
      newPredicates ++= ps
      toVisit.enqueue(ns.diff(activeNodes).toSeq: _*)
    }

    val newNodes = activeNodes.map(_.n) ++ graph.nodes.filter(_.fromLib)
    printResult(s"Before pruning: ${graph.nodes.size}")
    PredicateGraph(newNodes, newPredicates).tap { g =>
      printResult(s"After pruning: ${g.nodes.size}")
    }
  }

  def prepareProject(
      libDefs: LibDefs,
      root: Path,
      skipSet: Set[String] = Set("dist", "__tests__", "test", "tests"),
  ): (PredicateGraph, Vector[QModule], Map[ProjNode, PType]) =
    SimpleMath.withErrorMessage(s"In project: $root") {
      import libDefs._

      def filterTests(path: Path): Boolean = {
        path.segments.forall(!skipSet.contains(_))
      }

      val p = ProgramParsing.parseGProjectFromRoot(root, filter = filterTests)
      val allocator = new PNodeAllocator(forLib = false)
      val irTranslator = new IRTranslation(allocator)

      val errorHandler =
        ErrorHandler(ErrorHandler.ThrowError, ErrorHandler.StoreError)

//    println(s"LibExports key set: ${libExports.keySet}")
      val qModules = QLangTranslation
        .fromProject(
          p.modules,
          baseCtx,
          libExports,
          allocator,
          p.pathMapping,
          p.devDependencies,
          errorHandler,
        )
      val irModules = qModules.map(irTranslator.fromQModule)
      val allAnnots = irModules.flatMap(_.mapping).toMap
      val fixedAnnots = allAnnots.collect { case (n, Annot.Fixed(t)) => n -> t }
      val userAnnots = allAnnots.collect {
        case (n, Annot.User(t)) => ProjNode(n) -> t
      }

      val graph0 =
        PredicateGraphTranslation.fromIRModules(
          fixedAnnots,
          allocator,
          nodeForAny,
          irModules,
        )
      val userTypes =
        graph0.nodes.filter(n => !n.fromLib && n.isType).map(ProjNode)
      val graph = pruneGraph(graph0, userAnnots.keySet ++ userTypes)

      errorHandler.warnErrors()
      printResult(s"Project parsed: '$root'")

      (graph, qModules, userAnnots)
    }

}
