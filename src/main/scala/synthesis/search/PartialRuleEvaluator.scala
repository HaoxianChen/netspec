package synthesis.search

import synthesis.{Constant, Evaluator, Literal, Problem, Program, Relation, Rule, SimpleLiteral, Tuple, Variable}

import scala.collection.mutable

case class PartialRuleEvaluator(problem: Problem) {
  private val evaluator = Evaluator(problem)
  private val MAXRULES: Int = 10

  def getRefIdb(rule: Rule, idb: Set[Tuple]): Set[Tuple] = {
    val relevantIdb = idb.filter(_.relation == rule.head.relation)
    if (rule.isHeadBounded()){
      relevantIdb
    }
    else {
      val newRel = _getStrippedRelation(rule)
      val indices = _getBoundedIndices(rule)

      def getStripedTuple(tuple: Tuple): Tuple = {
        val newFields = indices map tuple.fields
        Tuple(newRel, newFields)
      }

      relevantIdb.map(getStripedTuple)
    }
  }

  def evalRule(rule: Rule, learnedRules: Set[Rule], isRecursive: Boolean): Set[Tuple] = {
    val newRule = if (rule.isHeadBounded()) rule else getStripedRule(rule)
    evalRules(Set(newRule), learnedRules, isRecursive)
  }

  def evalRules(rules: Set[Rule], learnedRules: Set[Rule], isRecursive: Boolean): Set[Tuple] = {
    /** Evaluator can handle at most MAXRULES at a time. */
    rules.grouped(MAXRULES).toSet.flatMap(rs => _evalRules(rs, learnedRules, isRecursive))
  }

  def _evalRules(rules: Set[Rule], learnedRules: Set[Rule], isRecursive: Boolean): Set[Tuple] = {
    require(rules.forall(_.isHeadBounded()))
    val oldIdb = if (isRecursive) {
      evaluator.eval(Program(learnedRules))
    }
    else {
      learnedRules.grouped(MAXRULES).toSet.flatMap((rs: Set[Rule]) => evaluator.eval(Program(rs)))
    }
    val newIdb = if (isRecursive) {
      evaluator.eval(Program(learnedRules++rules))
    }
    else {
      evaluator.eval(Program(rules))
    }
    newIdb.diff(oldIdb)
  }

  def _getBoundedIndices(rule: Rule): List[Int] = {
    val freeVars = rule.getUngroundHeadVariables()
    val indices = mutable.ListBuffer.empty[Int]
    for ((f,i) <- rule.head.fields.zipWithIndex) {
      f match {
        case v: Variable => if (!freeVars.contains(v)) indices.append(i)
        case _: Constant => indices.append(i)
      }
    }
    require(indices.nonEmpty, s"$rule")
    require(indices.max < rule.head.fields.size, s"indices: $indices,\n rule: $rule")
    indices.toList
  }

  def getStripedRule(unBoundRule: Rule): Rule = {
    val indices = _getBoundedIndices(unBoundRule)
    assert(indices.nonEmpty)
    val newFields = indices map unBoundRule.head.fields

    val newOutRel = _getStrippedRelation(unBoundRule)
    assert(newOutRel.signature.size == newFields.size)
    val newHead = SimpleLiteral(newOutRel, newFields)
    Rule(newHead, unBoundRule.body)
  }

  def _getStrippedRelName(unBoundRule: Rule, indices: List[Int]): String = s"${unBoundRule.head.relation.name}_${indices.mkString("")}"

  def _getStrippedRelation(unBoundRule: Rule): Relation = {
    require(!unBoundRule.isHeadBounded())
    val indices = _getBoundedIndices(unBoundRule)
    val newSig = indices map unBoundRule.head.relation.signature
    val newRelName = _getStrippedRelName(unBoundRule, indices)
    Relation(newRelName, newSig)
  }
}

object PartialRuleEvaluator {

}