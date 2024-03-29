package synthesis.rulebuilder

import synthesis._

/** Functors */
sealed abstract class AbstractFunctorSpec {
  def name: String
  def signature: List[Type]

  def literalToString(literal: Literal): String
  def makeLiteral(inputs: List[Parameter], output: Parameter): FunctorLiteral

  def getRelation: Relation = Relation(name, signature)

  def isLiteralValid(literal: FunctorLiteral): Boolean = true
}
object AbstractFunctorSpec {
  def isListType(_type: Type): Boolean = _type.name.endsWith("List")
  def getNodeName(_type: Type): String = {
    require(isListType(_type))
    _type.name.take(_type.name.size-4)
  }
}

case class FunctorLiteral(relation: Relation, fields: List[Parameter],
                          abstractFunctorSpec: AbstractFunctorSpec) extends Literal {
  override def toString: String = abstractFunctorSpec.literalToString(this)

  def rename(binding: Map[Parameter, Parameter]): Literal = {
    val newFields = _rename(binding)
    this.copy(fields=newFields)
  }

  def renameRelation(newRels: Map[Relation, Relation]): Literal = {
    val newRel = newRels.getOrElse(this.relation, this.relation)
    this.copy(relation=newRel)
  }

  def isValid: Boolean = abstractFunctorSpec.isLiteralValid(this)
}

abstract class FunctorSpec() extends AbstractFunctorSpec {
  def inputIndices: List[Int]
  def outputIndex: Int

  /** no out of bound indices */
  require(inputIndices.forall(i => i < signature.size))
  require(outputIndex < signature.size)
  /** no duplicated indices */
  require((inputIndices :+ outputIndex).toSet.size == inputIndices.size+1)
  /** length of signature is the same as input + output */
  require(inputIndices.size + 1 == signature.size)

  def inputSig: List[Type] = inputIndices.map(i => signature(i))
  def outputSig: Type = signature(outputIndex)

  def getInputs(literal: Literal): List[Parameter] = inputIndices.map(i => literal.fields(i))
  def getOutput(literal: Literal): Parameter = literal.fields(outputIndex)

  def makeLiteral(inputs: List[Parameter], output: Parameter): FunctorLiteral = {
    val fields: List[Parameter] = signature.zipWithIndex.map { case (t, i) => {
      val p: Parameter = if (inputIndices.contains(i)) {
        val idx = inputIndices.indexOf(i)
        inputs(idx)
        }
        else output
      assert(p._type == t)
      p
      }
    }
    rulebuilder.FunctorLiteral(getRelation, fields, this)
  }

}

abstract class FilterSpec() extends AbstractFunctorSpec {
  def makeLiteral(inputs: List[Parameter], output: Parameter): FunctorLiteral = {
    require(inputs.size == 2)
    val rel: Relation = Relation(this.name, this.signature)
    FunctorLiteral(rel, inputs, this)
  }
}

case class Greater(signature: List[Type]) extends FilterSpec {
  def name: String = "Greater"
  require(signature.forall(_.isInstanceOf[NumberType]))
  require(signature.size == 2)

  def literalToString(literal: Literal): String = {
    require(literal.fields.size == 2)
    val a = literal.fields(0)
    val b = literal.fields(1)
    s"${a} > ${b}"
  }
}
object Greater {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for Number types */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes

    val numberTypes: Set[Type] = outputTypes.filter(_.isInstanceOf[NumberType])
    numberTypes.map { nt =>
      require(inputTypes.contains(nt))
      Greater(List(nt, nt))
    }
  }
}

case class UnEqual(signature: List[Type]) extends FilterSpec {
  def name: String = s"UnEqual"
  require(signature.size==2)
  require(signature.head == signature(1))

  def literalToString(literal: Literal): String = {
    require(literal.fields.size == 2)
    val a = literal.fields(0)
    val b = literal.fields(1)
    s"${a} != ${b}"
  }
}
object UnEqual {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for Number types */
    val inputTypes = problem.inputTypes

    inputTypes.map { t => UnEqual(List(t, t)) }
  }
}

case class Quorum() extends FilterSpec {
  def name: String = "Quorum"
  def signature: List[Type] = List(AggCount.countType, AggCount.countType)

  def literalToString(literal: Literal): String = {
    require(literal.fields.size == 2)
    val a = literal.fields(0)
    val b = literal.fields(1)
    s"${a} > ${b} / 2"
  }
}
object Quorum {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = Set(Quorum())
}

case class TimeOut(signature: List[Type], timeOut: Int = 20) extends FilterSpec {
  def name: String = "TimeOut"
  require(signature.forall(_.name == TimeOut.timeType.name))
  require(signature.size == 2)

  def literalToString(literal: Literal): String = {
    require(literal.fields.size == 2)
    val a = literal.fields(0)
    val b = literal.fields(1)
    s"${a} - ${b} > ${timeOut}"
  }
}
object TimeOut {
  val timeType = NumberType(s"Time")
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for Number types */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes

    val timeTypes: Set[Type] = outputTypes.filter(_.name == TimeOut.timeType.name)
    timeTypes.map { nt =>
      require(inputTypes.contains(nt))
      TimeOut(List(nt, nt))
    }
  }
}

case class ListContain(signature: List[Type]) extends FilterSpec {
  def name: String = "ListContain"
  require(signature.size==2)
  require(AbstractFunctorSpec.isListType(signature(1)))

  def literalToString(literal: Literal): String = {
    require(literal.relation == this.getRelation)
    require(literal.fields.size == 2)

    val h = literal.fields.head
    val tail = literal.fields(1)

    s"contains(as(${h},symbol), as(${tail},symbol))"
  }
}
object ListContain {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for type x and type xList */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes
    val allTypes = inputTypes ++ outputTypes

    val listTypes: Set[Type] = outputTypes.filter(AbstractFunctorSpec.isListType)
    listTypes.map { lt =>
      val nodeName = AbstractFunctorSpec.getNodeName(lt)
      val _nodeTypes: Set[Type] = allTypes.filter(_.name == nodeName)
      require(_nodeTypes.size==1)
      val nodeType: Type = _nodeTypes.toList.head
      ListContain(List(nodeType, lt))
    }
  }

}

case class MakeList(signature: List[Type]) extends FunctorSpec {
  def name : String = "makeList"
  def inputIndices: List[Int] = List(0,1)
  def outputIndex: Int = 2
  require(signature.size == 3)
  require(signature(0)==signature(1))

  def literalToString(literal: Literal): String = {
    require(literal.relation == this.getRelation)
    val output = getOutput(literal)
    val inputs = getInputs(literal)
    s"${output}=cat(${inputs.mkString(",")})"
  }

}
object MakeList {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for type x and type xList */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes
    val allTypes = inputTypes ++ outputTypes

    val listTypes: Set[Type] = outputTypes.filter(AbstractFunctorSpec.isListType)
    listTypes.map { lt =>
      val nodeName = AbstractFunctorSpec.getNodeName(lt)
      val _nodeTypes: Set[Type] = allTypes.filter(_.name == nodeName)
      require(_nodeTypes.size==1)
      val nodeType: Type = _nodeTypes.toList.head
      MakeList(List(nodeType, nodeType, lt))
    }
  }
}

case class PrependList(signature: List[Type]) extends FunctorSpec {
  def name: String = "safePrepend"
  def inputIndices: List[Int] = List(0,1)
  def outputIndex: Int = 2

  require(signature.size == 3)
  require(signature(1)==signature(2))
  require(AbstractFunctorSpec.isListType(signature(1)))
  require(AbstractFunctorSpec.isListType(signature(2)))

  def literalToString(literal: Literal): String = {
    require(literal.relation == this.getRelation)
    val output = getOutput(literal)
    val inputs = getInputs(literal)

    val h = inputs(0)
    val tail = inputs(1)

    s"${output}=cat(${h}, ${tail}), !contains(as(${h},symbol), as(${tail},symbol))"
  }
}
object PrependList {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for type x and type xList */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes
    val allTypes = inputTypes ++ outputTypes

    val listTypes: Set[Type] = outputTypes.filter(AbstractFunctorSpec.isListType)
    listTypes.map { lt =>
      val nodeName = AbstractFunctorSpec.getNodeName(lt)
      val _nodeTypes: Set[Type] = allTypes.filter(_.name == nodeName)
      require(_nodeTypes.size==1)
      val nodeType: Type = _nodeTypes.toList.head
      PrependList(List(nodeType, lt, lt))
    }
  }
}

case class Add(signature: List[Type]) extends FunctorSpec {
  def name: String = "add"
  def inputIndices: List[Int] = List(0,1)
  def outputIndex: Int = 2

  require(signature.size == 3)
  require(signature.toSet.size == 1)
  require(signature.forall(_.isInstanceOf[NumberType]))

  def literalToString(literal: Literal): String = {
    require(literal.relation == this.getRelation)
    val output = getOutput(literal)
    val inputs = getInputs(literal)
    require(inputs.size==2)
    s"${output}=${inputs(0)}+${inputs(1)}"
  }
}
object Add {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for Number types */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes

    val numberTypes: Set[Type] = outputTypes.filter(_.isInstanceOf[NumberType])
    numberTypes.map { nt =>
      require(inputTypes.contains(nt))
      Add(List(nt, nt, nt))
    }
  }
}

case class Min(signature: List[Type]) extends FunctorSpec {
  def name: String = "min"
  def inputIndices: List[Int] = List(0,1)
  def outputIndex: Int = 2

  require(signature.size == 3)
  require(signature.toSet.size == 1)
  require(signature.forall(_.isInstanceOf[NumberType]))

  def literalToString(literal: Literal): String = {
    require(literal.relation == this.getRelation)
    val output = getOutput(literal)
    val inputs = getInputs(literal)
    require(inputs.size==2)
    s"${output}=min(${inputs(0)},${inputs(1)})"
  }

  override def isLiteralValid(literal: FunctorLiteral): Boolean = {
    this.getInputs(literal).toSet.size == 2
  }
}
object Min {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for Number types */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes
    val allTypes = inputTypes ++ outputTypes

    val numberTypes: Set[Type] = outputTypes.filter(_.isInstanceOf[NumberType])
    numberTypes.map { nt =>
      require(inputTypes.contains(nt))
      Min(List(nt, nt, nt))
    }
  }
}

case class Max(signature: List[Type]) extends FunctorSpec {
  def name: String = "max"
  def inputIndices: List[Int] = List(0,1)
  def outputIndex: Int = 2

  require(signature.size == 3)
  require(signature.toSet.size == 1)
  require(signature.forall(_.isInstanceOf[NumberType]))

  def literalToString(literal: Literal): String = {
    require(literal.relation == this.getRelation)
    val output = getOutput(literal)
    val inputs = getInputs(literal)
    require(inputs.size==2)
    s"${output}=max(${inputs(0)},${inputs(1)})"
  }

  override def isLiteralValid(literal: FunctorLiteral): Boolean = {
    this.getInputs(literal).toSet.size == 2
  }
}
object Max {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for Number types */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes
    val allTypes = inputTypes ++ outputTypes

    val numberTypes: Set[Type] = outputTypes.filter(_.isInstanceOf[NumberType])
    numberTypes.map { nt =>
      require(inputTypes.contains(nt))
      Max(List(nt, nt, nt))
    }
  }
}

case class AppendList(signature: List[Type]) extends FunctorSpec {
  def name: String = "safeAppend"
  def inputIndices: List[Int] = List(0,1)
  def outputIndex: Int = 2

  require(signature.size == 3)
  require(signature(0)==signature(2))
  require(AbstractFunctorSpec.isListType(signature(0)))
  require(AbstractFunctorSpec.isListType(signature(2)))

  def literalToString(literal: Literal): String = {
    require(literal.relation == this.getRelation)
    val output = getOutput(literal)
    val inputs = getInputs(literal)

    val h = inputs(0)
    val tail = inputs(1)

    s"${output}=cat(${h}, ${tail}), !contains(as(${tail},symbol), as(${h},symbol))"
  }
}
object AppendList {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for type x and type xList */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes
    val allTypes = inputTypes ++ outputTypes

    val listTypes: Set[Type] = outputTypes.filter(AbstractFunctorSpec.isListType)
    listTypes.map { lt =>
      val nodeName = AbstractFunctorSpec.getNodeName(lt)
      val _nodeTypes: Set[Type] = allTypes.filter(_.name == nodeName)
      require(_nodeTypes.size==1)
      val nodeType: Type = _nodeTypes.toList.head
      AppendList(List(lt, nodeType, lt))
    }
  }
}
case class Increment(signature: List[Type]) extends FunctorSpec {
  def name: String = "increment"
  def inputIndices: List[Int] = List(0)
  def outputIndex: Int = 1

  require(signature.size == 2)
  require(signature.toSet.size == 1)
  require(signature.forall(_.isInstanceOf[NumberType]))

  def literalToString(literal: Literal): String = {
    require(literal.relation == this.getRelation)
    val output = getOutput(literal)
    val inputs = getInputs(literal)
    require(inputs.size==1)
    s"${output}=${inputs.head}+1"
  }
}
object Increment {
  def allInstances(problem: Problem): Set[AbstractFunctorSpec] = {
    /** Look for Number types */
    val inputTypes = problem.inputTypes
    val outputTypes = problem.outputTypes

    val numberTypes: Set[Type] = outputTypes.filter(_.isInstanceOf[NumberType])
    numberTypes.map { nt =>
      require(inputTypes.contains(nt))
      Increment(List(nt, nt))
    }
  }
}
