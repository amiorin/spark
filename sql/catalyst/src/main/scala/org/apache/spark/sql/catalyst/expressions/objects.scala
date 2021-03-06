/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import scala.language.existentials

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.{GeneratedExpressionCode, CodeGenContext}
import org.apache.spark.sql.types._

/**
 * Invokes a static function, returning the result.  By default, any of the arguments being null
 * will result in returning null instead of calling the function.
 *
 * @param staticObject The target of the static call.  This can either be the object itself
 *                     (methods defined on scala objects), or the class object
 *                     (static methods defined in java).
 * @param dataType The expected return type of the function call
 * @param functionName The name of the method to call.
 * @param arguments An optional list of expressions to pass as arguments to the function.
 * @param propagateNull When true, and any of the arguments is null, null will be returned instead
 *                      of calling the function.
 */
case class StaticInvoke(
    staticObject: Any,
    dataType: DataType,
    functionName: String,
    arguments: Seq[Expression] = Nil,
    propagateNull: Boolean = true) extends Expression {

  val objectName = staticObject match {
    case c: Class[_] => c.getName
    case other => other.getClass.getName.stripSuffix("$")
  }
  override def nullable: Boolean = true
  override def children: Seq[Expression] = Nil

  override def eval(input: InternalRow): Any =
    throw new UnsupportedOperationException("Only code-generated evaluation is supported.")

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val javaType = ctx.javaType(dataType)
    val argGen = arguments.map(_.gen(ctx))
    val argString = argGen.map(_.value).mkString(", ")

    if (propagateNull) {
      val objNullCheck = if (ctx.defaultValue(dataType) == "null") {
        s"${ev.isNull} = ${ev.value} == null;"
      } else {
        ""
      }

      val argsNonNull = s"!(${argGen.map(_.isNull).mkString(" || ")})"
      s"""
        ${argGen.map(_.code).mkString("\n")}

        boolean ${ev.isNull} = true;
        $javaType ${ev.value} = ${ctx.defaultValue(dataType)};

        if ($argsNonNull) {
          ${ev.value} = $objectName.$functionName($argString);
          $objNullCheck
        }
       """
    } else {
      s"""
        ${argGen.map(_.code).mkString("\n")}

        final boolean ${ev.isNull} = ${ev.value} == null;
        $javaType ${ev.value} = $objectName.$functionName($argString);
      """
    }
  }
}

/**
 * Calls the specified function on an object, optionally passing arguments.  If the `targetObject`
 * expression evaluates to null then null will be returned.
 *
 * @param targetObject An expression that will return the object to call the method on.
 * @param functionName The name of the method to call.
 * @param dataType The expected return type of the function.
 * @param arguments An optional list of expressions, whos evaluation will be passed to the function.
 */
case class Invoke(
    targetObject: Expression,
    functionName: String,
    dataType: DataType,
    arguments: Seq[Expression] = Nil) extends Expression {

  override def nullable: Boolean = true
  override def children: Seq[Expression] = targetObject :: Nil

  override def eval(input: InternalRow): Any =
    throw new UnsupportedOperationException("Only code-generated evaluation is supported.")

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val javaType = ctx.javaType(dataType)
    val obj = targetObject.gen(ctx)
    val argGen = arguments.map(_.gen(ctx))
    val argString = argGen.map(_.value).mkString(", ")

    // If the function can return null, we do an extra check to make sure our null bit is still set
    // correctly.
    val objNullCheck = if (ctx.defaultValue(dataType) == "null") {
      s"${ev.isNull} = ${ev.value} == null;"
    } else {
      ""
    }

    s"""
      ${obj.code}
      ${argGen.map(_.code).mkString("\n")}

      boolean ${ev.isNull} = ${obj.value} == null;
      $javaType ${ev.value} =
        ${ev.isNull} ?
        ${ctx.defaultValue(dataType)} : ($javaType) ${obj.value}.$functionName($argString);
      $objNullCheck
    """
  }
}

/**
 * Constructs a new instance of the given class, using the result of evaluating the specified
 * expressions as arguments.
 *
 * @param cls The class to construct.
 * @param arguments A list of expression to use as arguments to the constructor.
 * @param propagateNull When true, if any of the arguments is null, then null will be returned
 *                      instead of trying to construct the object.
 * @param dataType The type of object being constructed, as a Spark SQL datatype.  This allows you
 *                 to manually specify the type when the object in question is a valid internal
 *                 representation (i.e. ArrayData) instead of an object.
 */
case class NewInstance(
    cls: Class[_],
    arguments: Seq[Expression],
    propagateNull: Boolean = true,
    dataType: DataType) extends Expression {
  private val className = cls.getName

  override def nullable: Boolean = propagateNull

  override def children: Seq[Expression] = arguments

  override def eval(input: InternalRow): Any =
    throw new UnsupportedOperationException("Only code-generated evaluation is supported.")

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val javaType = ctx.javaType(dataType)
    val argGen = arguments.map(_.gen(ctx))
    val argString = argGen.map(_.value).mkString(", ")

    if (propagateNull) {
      val objNullCheck = if (ctx.defaultValue(dataType) == "null") {
        s"${ev.isNull} = ${ev.value} == null;"
      } else {
        ""
      }

      val argsNonNull = s"!(${argGen.map(_.isNull).mkString(" || ")})"
      s"""
        ${argGen.map(_.code).mkString("\n")}

        boolean ${ev.isNull} = true;
        $javaType ${ev.value} = ${ctx.defaultValue(dataType)};

        if ($argsNonNull) {
          ${ev.value} = new $className($argString);
          ${ev.isNull} = false;
        }
       """
    } else {
      s"""
        ${argGen.map(_.code).mkString("\n")}

        final boolean ${ev.isNull} = ${ev.value} == null;
        $javaType ${ev.value} = new $className($argString);
      """
    }
  }
}

/**
 * Given an expression that returns on object of type `Option[_]`, this expression unwraps the
 * option into the specified Spark SQL datatype.  In the case of `None`, the nullbit is set instead.
 *
 * @param dataType The expected unwrapped option type.
 * @param child An expression that returns an `Option`
 */
case class UnwrapOption(
    dataType: DataType,
    child: Expression) extends UnaryExpression with ExpectsInputTypes {

  override def nullable: Boolean = true

  override def children: Seq[Expression] = Nil

  override def inputTypes: Seq[AbstractDataType] = ObjectType :: Nil

  override def eval(input: InternalRow): Any =
    throw new UnsupportedOperationException("Only code-generated evaluation is supported")

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val javaType = ctx.javaType(dataType)
    val inputObject = child.gen(ctx)

    s"""
      ${inputObject.code}

      boolean ${ev.isNull} = ${inputObject.value} == null || ${inputObject.value}.isEmpty();
      $javaType ${ev.value} =
        ${ev.isNull} ? ${ctx.defaultValue(dataType)} : ($javaType)${inputObject.value}.get();
    """
  }
}

case class LambdaVariable(value: String, isNull: String, dataType: DataType) extends Expression {

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String =
    throw new UnsupportedOperationException("Only calling gen() is supported.")

  override def children: Seq[Expression] = Nil
  override def gen(ctx: CodeGenContext): GeneratedExpressionCode =
    GeneratedExpressionCode(code = "", value = value, isNull = isNull)

  override def nullable: Boolean = false
  override def eval(input: InternalRow): Any =
    throw new UnsupportedOperationException("Only code-generated evaluation is supported.")

}

/**
 * Applies the given expression to every element of a collection of items, returning the result
 * as an ArrayType.  This is similar to a typical map operation, but where the lambda function
 * is expressed using catalyst expressions.
 *
 * The following collection ObjectTypes are currently supported: Seq, Array
 *
 * @param function A function that returns an expression, given an attribute that can be used
 *                 to access the current value.  This is does as a lambda function so that
 *                 a unique attribute reference can be provided for each expression (thus allowing
 *                 us to nest multiple MapObject calls).
 * @param inputData An expression that when evaluted returns a collection object.
 * @param elementType The type of element in the collection, expressed as a DataType.
 */
case class MapObjects(
    function: AttributeReference => Expression,
    inputData: Expression,
    elementType: DataType) extends Expression {

  private val loopAttribute = AttributeReference("loopVar", elementType)()
  private val completeFunction = function(loopAttribute)

  private val (lengthFunction, itemAccessor) = inputData.dataType match {
    case ObjectType(cls) if cls.isAssignableFrom(classOf[Seq[_]]) =>
      (".size()", (i: String) => s".apply($i)")
    case ObjectType(cls) if cls.isArray =>
      (".length", (i: String) => s"[$i]")
  }

  override def nullable: Boolean = true

  override def children: Seq[Expression] = completeFunction :: inputData :: Nil

  override def eval(input: InternalRow): Any =
    throw new UnsupportedOperationException("Only code-generated evaluation is supported")

  override def dataType: DataType = ArrayType(completeFunction.dataType)

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val javaType = ctx.javaType(dataType)
    val elementJavaType = ctx.javaType(elementType)
    val genInputData = inputData.gen(ctx)

    // Variables to hold the element that is currently being processed.
    val loopValue = ctx.freshName("loopValue")
    val loopIsNull = ctx.freshName("loopIsNull")

    val loopVariable = LambdaVariable(loopValue, loopIsNull, elementType)
    val boundFunction = completeFunction transform {
      case a: AttributeReference if a == loopAttribute => loopVariable
    }

    val genFunction = boundFunction.gen(ctx)
    val dataLength = ctx.freshName("dataLength")
    val convertedArray = ctx.freshName("convertedArray")
    val loopIndex = ctx.freshName("loopIndex")

    s"""
      ${genInputData.code}

      boolean ${ev.isNull} = ${genInputData.value} == null;
      $javaType ${ev.value} = ${ctx.defaultValue(dataType)};

      if (!${ev.isNull}) {
        Object[] $convertedArray = null;
        int $dataLength = ${genInputData.value}$lengthFunction;
        $convertedArray = new Object[$dataLength];

        int $loopIndex = 0;
        while ($loopIndex < $dataLength) {
          $elementJavaType $loopValue =
            ($elementJavaType)${genInputData.value}${itemAccessor(loopIndex)};
          boolean $loopIsNull = $loopValue == null;

          ${genFunction.code}

          $convertedArray[$loopIndex] = ${genFunction.value};
          $loopIndex += 1;
        }

        ${ev.isNull} = false;
        ${ev.value} = new ${classOf[GenericArrayData].getName}($convertedArray);
      }
    """
  }
}
