package synthesis.experiment

import com.typesafe.scalalogging.Logger
import synthesis.{ActiveLearning, ExampleInstance, Problem}

class DebloatingExperiment(maxExamples: Int =100, outDir: String = "results/debloat") extends Experiment {
  private val logger = Logger("Debloating")
  def go(problem: Problem, repeats: Int = 1): Unit = {
    for (i <- 1 to repeats) {
      logger.info(s"iteration $i")
      debloat(problem)
    }
  }

  def debloat(problem: Problem): Unit = {
    val t1 = System.nanoTime
    val learner = new ActiveLearning(problem, maxExamples)
    val (program, nQueries) = learner.go()

    val duration = (System.nanoTime - t1) / 1e9d
    println(s"Finished in ${duration}s, ${nQueries} queries.")

    val exampleInstances = ExampleInstance.fromEdbIdb(problem.edb, problem.idb)

    val record = ExperimentRecord(Map("problem"->problem.name,
      "exp_name" -> s"random_trace",
      "trace_length" -> exampleInstances.size,
      "numQuereis" -> nQueries,
      "time"->duration),
      program
    )
    record.dump(outDir)
    logger.info(s"Finished in ${duration}s, trace length ${exampleInstances.size}, ${nQueries} queries.")
  }

}
