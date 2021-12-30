package synthesis.activelearning

import com.typesafe.scalalogging.Logger
import synthesis._
import synthesis.rulebuilder.InputAggregator
import synthesis.search.{Synthesis, SynthesisConfigSpace}
import synthesis.util.Misc

import java.nio.file.{Path, Paths}
import scala.math.log

case class TupleInstance(tuples: Set[Tuple], instanceId: Int)
object TupleInstance {
  def apply(instanceId: Int): TupleInstance = new TupleInstance(Set(), instanceId)
}
case class ExampleInstance(input: Set[Tuple], output: Set[Tuple], instanceId: Int) {
  def nonEmpty: Boolean = input.nonEmpty
  def getConstants: Set[Constant] = (input++output).flatMap(_.fields)
  def outRels: Set[Relation] = output.map(_.relation)
}
object ExampleInstance{
  def apply(instanceId: Int): ExampleInstance = new ExampleInstance(Set(), Set(), instanceId)
  def apply(): ExampleInstance = new ExampleInstance(Set(), Set(), -1)
  def fromEdbIdb(edb: Examples, idb: Examples): Set[ExampleInstance] = {
    val translator = new ExampleTranslator(edb.elems.keySet, idb.elems.keySet)

    val edbMap = edb.toTuples().groupBy(translator.getInstanceId)
    val idbMap = idb.toTuples().groupBy(translator.getInstanceId)

    edbMap.keySet.map(i => {
      val edb: Set[Tuple] = edbMap(i)
      val idb: Set[Tuple] = idbMap.getOrElse(i, Set())
      ExampleInstance(edb, idb, i)
    })
  }


  def toEdbIdb(instances: Set[ExampleInstance]): (Examples, Examples) = {
    val inputTuples: Set[Tuple] = instances.flatMap(_.input)
    val outputTuples: Set[Tuple] = instances.flatMap(_.output)

    def tupleToMap(tuples: Set[Tuple]): Map[Relation, Set[List[Constant]]]= tuples.groupBy(t => t.relation) map {
        case (rel, tuples) => (rel, tuples.map(_.fields))
      }

    val edb = Examples(tupleToMap(inputTuples))
    val idb = Examples(tupleToMap(outputTuples))
    (edb, idb)
  }
}

case class ExamplePool(instances: Set[TupleInstance]) {
  require(noDuplicateId())
  def noDuplicateId(): Boolean = {
    val idList = instances.toList.map(_.instanceId)
    idList.distinct.size == idList.size
  }

  def toExampleMap: Examples = {
    val allTuples: Set[Tuple] = instances.flatMap(_.tuples)
    val map: Map[Relation, Set[List[Constant]]] = allTuples.groupBy(_.relation).map {
      case (rel, ts) => rel -> ts.map(_.fields)
    }
    Examples(map)
  }
}

case class EvaluatorWrapper (problem: Problem)  {
  private val preprocessors: Set[InputAggregator] = Synthesis.getPreprocessors(problem)
  private val newProblem = preprocessors.foldLeft(problem)((p1, agg) => agg.preprocess(p1))
  private val evaluator = Evaluator(newProblem)
  def eval(program: Program): Set[Tuple] = evaluator.eval(program)
  def eval(program: Program, outRel: Relation): Set[Tuple] = eval(program, Set(outRel))
  def eval(program: Program, outRels: Set[Relation]): Set[Tuple] = evaluator.eval(program).filter(
    t => outRels.contains(t.relation)
  )
  def eval(programSpec: String, outRels: Set[Relation]): Set[Tuple] = evaluator.eval(programSpec, outRels)
}

class ActiveLearning(p0: Problem, staticConfigRelations: Set[Relation], numNewExamples: Int = 20,
                     logDir: String = s"/var/tmp/netspec") {
  private val logger = Logger("Active-learning")
  private val logRootDir: Path = Paths.get(logDir)
  Misc.makeDir(logRootDir)

  private val exampleTranslator = new ExampleTranslator(p0.inputRels, p0.outputRels)

  private val exampleGenerator = new ExampleGenerator(p0.inputRels, staticConfigRelations, p0.edb, p0.idb)
  private val edbPool: ExamplePool = exampleGenerator.generateRandomInputs(numNewExamples)
  logger.info(s"Example pool size: ${edbPool.instances.size}.")

  private val oracle = p0.oracleSpec.get
  private var configSpace = SynthesisConfigSpace.getConfigSpace(p0)

  def go(): (Program, Int, Boolean) = {
    /** Handle one output relation at a time */
    var solutions: Set[Program] = Set()
    var problem: Problem = p0
    var nQueries: Int = 0
    for (rel <- p0.outputRels) {
      val (sol, newExamples) = interactiveLearning(rel, problem)
      solutions += sol
      problem = newExamples.foldLeft(problem)(exampleTranslator.updateProblem)
      nQueries += newExamples.size
    }
    /** Merge solutions altogether */
    val finalSolution = solutions.foldLeft(Program())((p1,p2)=>Program(p1.rules++p2.rules))
    (finalSolution, nQueries, differentiateFromOracle(finalSolution))
  }

  def interactiveLearning(outRel: Relation, initProblem: Problem): (Program, Set[ExampleInstance]) = {
    require(initProblem.outputRels.contains(outRel))
    logger.info(s"Solve ${outRel}")
    /** Only do synthesis on one output relation. */
    val relevantIdb: Examples = initProblem.idb.filterByRelation(outRel)
    var problem = initProblem.copy(outputRels=Set(outRel), idb=relevantIdb)

    var candidates: List[Program] = List()
    var nextExample: Option[ExampleInstance] = None
    var newExamples: Set[ExampleInstance] = Set()

    do {
      // add new examples
      if (nextExample.isDefined) {
        problem = exampleTranslator.updateProblem(problem, nextExample.get)
        newExamples += nextExample.get
      }

      logProblem(problem)
      candidates = synthesize(problem, outRel, candidates)
      logger.debug(s"${candidates.size} candidate programs")

      if (candidates.size > 1) {
        nextExample = disambiguate(candidates, outRel)
        if (nextExample.isEmpty) logger.debug(s"Failed to differentiate candidate programs.")
        else logger.debug(s"new example: ${nextExample.get}")
      }
    }
    while (candidates.size > 1 && nextExample.isDefined)

    val bestProgram = candidates.maxBy(scoreProgram)
    logger.info(s"Solution: $bestProgram")
    (bestProgram, newExamples)
  }

  def logProblem(problem: Problem): Path = {
    val evaluator = Evaluator(problem)
    val timestamp = Misc.getTimeStamp()
    val _logDir: Path = Paths.get(logRootDir.toString, timestamp)
    Misc.makeDir(_logDir)
    evaluator.dumpProgram(problem.oracleSpec.get, _logDir)
    logger.info(s"Problem log at ${_logDir}")
    _logDir
  }

  def synthesize(problem: Problem, outRel: Relation, candidates: List[Program], minPrograms: Int = 50): List[Program] = {
    val evaluator = EvaluatorWrapper(problem)

    def isProgramValid(program: Program, problem: Problem): Boolean = {
      val idb = evaluator.eval(program,outRel)
      val refIdb = problem.idb.toTuples()

      val allOutRels = program.rules.map(_.head.relation)
      val relevantIdb = refIdb.filter(t => allOutRels.contains(t.relation))

      val isValid = idb == relevantIdb
      isValid
    }

    val validCandidates: List[Program] = candidates.filter(p=>isProgramValid(p, problem))
    require(validCandidates.size < candidates.size || candidates.isEmpty, s"${validCandidates.size}")

    val inValidCandidates = candidates.diff(validCandidates)
    assert(inValidCandidates.nonEmpty || candidates.isEmpty)

    if (validCandidates.size < minPrograms) {
      // val synthesizer = SynthesisAllPrograms(problem, initConfigSpace = configSpace)
      val synthesizer = Synthesis(problem, initConfigSpace = configSpace)
      val newCandidates = synthesizer.go()(outRel)
      configSpace = synthesizer.getConfigSpace
      require(newCandidates.forall(p=>isProgramValid(p, problem)))
      validCandidates ++ newCandidates
    }
    else {
      validCandidates
    }
  }

  def differentiateFromOracle(solution: Program, outRels: Set[Relation]= p0.outputRels): Boolean = {
    val newProblem = p0.copy(edb = edbPool.toExampleMap, idb=Examples())
    val evaluator = EvaluatorWrapper(newProblem)
    val idb = evaluator.eval(solution, outRels)
    val refIdb = evaluator.eval(oracle, outRels)

    /** The differentiating examples */
    if (idb!=refIdb) {
      logger.debug(s"Static relations: $staticConfigRelations.")
      val idbById: Map[Int,Set[Tuple]] = idb.groupBy(_.fields.last.name.toInt)
      val refById: Map[Int,Set[Tuple]] = refIdb.groupBy(_.fields.last.name.toInt)
      val edbById: Map[Int,Set[Tuple]] = newProblem.edb.toTuples().groupBy(_.fields.last.name.toInt)
      for ((i,out) <- idb.diff(refIdb).groupBy(_.fields.last.name.toInt) ) {
        val ref = refById.getOrElse(i, Set())
        val in = edbById.get(i)
        logger.debug(s"Differentiating example: $in, $ref, $out.")
      }
      for ((i,ref) <- refIdb.diff(idb).groupBy(_.fields.last.name.toInt) ) {
        val out = idbById.getOrElse(i, Set())
        val in = edbById.get(i)
        logger.debug(s"Differentiating example: $in, $ref, $out.")
      }
    }
    idb==refIdb
  }

  def differentiate(candidates: List[Program], outRel: Relation): Option[TupleInstance] = {
    require(candidates.size > 1)
    def entropy(tuples: List[TupleInstance]): Double = {
      val counts: List[Int] = tuples.groupBy(identity).map{
        case (_, l) => l.size
      }.toList
      val hist: List[Double] = counts.map(c => 1.0 * c / counts.sum)
      hist.map(p => - p * log(p)).sum
    }
    assert(edbPool.instances.size == numNewExamples)

    val evalResults: Set[(TupleInstance, List[TupleInstance])] = evalCandidatePrograms(edbPool, candidates, outRel)
    val edbEntropy: Set[(TupleInstance, Double)] = evalResults.map {
      case (t1, ts) => (t1, entropy(ts))
    }
    val bestEdb = edbEntropy.maxBy(_._2)

    if (bestEdb._2 > 0) {
      logger.debug(s"New edb: $bestEdb")
      Some(bestEdb._1)
    }
    else None
  }

  def evalCandidatePrograms(edbPool: ExamplePool, candidates: List[Program], outRel:Relation): Set[(TupleInstance, List[TupleInstance])] = {
    val newProblem = p0.copy(edb = edbPool.toExampleMap, idb=Examples())
    def evalProgram(program: Program): Map[Int, TupleInstance] = {
      // val evaluator = Evaluator(newProblem)
      val evaluator = EvaluatorWrapper(newProblem)
      // val idb = evaluator.eval(program)
      val idb = evaluator.eval(program,outRel = outRel)
      idb.groupBy(exampleTranslator.getInstanceId) map {
        case (i,ts) => i -> TupleInstance(ts, i)
      }
    }

    val evalResults: List[Map[Int, TupleInstance]] = candidates.map(evalProgram)

    def getResults(instance: TupleInstance): List[TupleInstance] = {
      val tid = instance.instanceId
      evalResults.map(m => m.getOrElse(tid, TupleInstance(tid)))
    }
    edbPool.instances.map(t => (t, getResults(t)))
  }

  def disambiguate(candidates: List[Program], outRel: Relation): Option[ExampleInstance] = {
    require(candidates.size > 1)
    val nextEdb = differentiate(candidates,outRel)
    if (nextEdb.isDefined) {
      val edb = nextEdb.get
      val edbPool = ExamplePool(Set(edb))
      val problem = p0.copy(edb=edbPool.toExampleMap)

      // val evaluator = Evaluator(problem)
      val evaluator = EvaluatorWrapper(problem)
      // val nextIdb = evaluator.eval(oracle, Set(outRel))
      val nextIdb = evaluator.eval(oracle, p0.outputRels)

      Some(new ExampleInstance(edb.tuples, nextIdb, edb.instanceId))
    }
    else {
      None
    }
  }

  def scoreProgram(program: Program): Int = {
    /** todo: use better metric. */
    -program.rules.size
  }

}
