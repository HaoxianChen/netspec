package synthesis

case class Examples(elems: Map[Relation, Set[List[Constant]]]) {
  def addTuples(relation: Relation, tuples: Set[List[Constant]]): Examples = {
    val newMap = {
      val oldValue = elems.get(relation)
      val newValue: Set[List[Constant]] = if (oldValue.isDefined) oldValue.get ++ tuples else tuples
      elems + (relation -> newValue)
    }
    Examples(newMap)
  }

  def toTuples(): Set[Tuple] = {
    def toTuples(relation: Relation, tuples: Set[List[Constant]]): Set[Tuple] = tuples.map(fields => Tuple(relation, fields))
    elems.flatMap {
      case (rel, _tuples) => toTuples(rel, _tuples)
    }.toSet
  }

  def toFileStr(relation: Relation): String = {
    val facts = elems(relation)
    facts.map(l => l.mkString("\t")).mkString("\n")
  }

  def getConstantSet: Set[Constant] = {
    def _getConstants(constantListSet: Set[List[Constant]]): Set[Constant] = constantListSet.flatMap(_.toSet)

    var constants: Set[Constant] = Set()
    for ((_, v) <- elems) {
      constants ++= _getConstants(v)
    }
    constants
  }
}
object Examples {
  def apply(): Examples = new Examples(Map())
  def empty(): Examples = new Examples(Map())


  def strToTuple(rel: Relation, str: List[String]): List[Constant] = {
    require(rel.signature.size == str.size, s"${rel}, ${str}, szie: ${str.size}")
    val fields: List[Constant] = for ((_type, s) <- rel.signature zip str) yield Constant(s, _type)
    fields
  }
}

case class Problem(name: String, domain: String, types: Set[Type], inputRels: Set[Relation], outputRels: Set[Relation],
                   edb: Examples , idb: Examples) {

  private val typeMap: Map[String,Type] = (for (t<-types) yield t.name -> t).toMap

  def addType(_type: Type): Problem = {
    val newTypes = types + _type
    this.copy(types=newTypes)
  }
  def addInputRelation(relation: Relation): Problem = {
    val newRels  = inputRels + relation
    this.copy(inputRels=newRels)
  }
  def addInputRelation(relName: String, typeNames: List[String]): Problem = {
    val types: List[Type] = typeNames.map(typeMap(_))
    val relation: Relation = Relation(relName, types)
    addInputRelation(relation)
  }
  def addOutputRelation(relation: Relation): Problem = {
    val newRels  = outputRels + relation
    this.copy(outputRels=newRels)
  }
  def addOutputRelation(relName: String, typeNames: List[String]): Problem = {
    val types: List[Type] = typeNames.map(typeMap(_))
    val relation: Relation = Relation(relName, types)
    addOutputRelation(relation)
  }

  def addEdb(relation: Relation, facts: List[List[String]]): Problem = {
    require(inputRels.contains(relation))
    val newTuples = facts.map(Examples.strToTuple(relation, _)).toSet
    this.copy(edb=edb.addTuples(relation, newTuples))
  }
  def addIdb(relation: Relation, facts: List[List[String]]): Problem = {
    require(outputRels.contains(relation))
    val newTuples = facts.map(Examples.strToTuple(relation, _)).toSet
    this.copy(idb=idb.addTuples(relation, newTuples))
  }

  def rename(newName: String): Problem = this.copy(name=newName)
  def addDomain(domain: String): Problem = this.copy(domain=domain)

  def getType(name: String): Option[Type] = typeMap.get(name)
}

object Problem {
  def apply(): Problem = Problem("new_problem", "new_domain",Set(), Set(), Set(), Examples(), Examples())
}