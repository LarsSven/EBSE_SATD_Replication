/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.batch.sql

import java.sql.{Date, Time, Timestamp}
import java.util

import org.apache.flink.api.scala._
import org.apache.flink.api.scala.util.CollectionDataSets
import org.apache.flink.table.api.scala._
import org.apache.flink.table.api.{TableEnvironment, ValidationException}
import org.apache.flink.table.expressions.utils.SplitUDF
import org.apache.flink.table.functions.ScalarFunction
import org.apache.flink.table.runtime.batch.table.OldHashCode
import org.apache.flink.table.runtime.utils.TableProgramsTestBase.TableConfigMode
import org.apache.flink.table.runtime.utils.{JavaPojos, TableProgramsCollectionTestBase, TableProgramsTestBase}
import org.apache.flink.test.util.TestBaseUtils
import org.apache.flink.types.Row
import org.junit._
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

import scala.collection.JavaConverters._

@RunWith(classOf[Parameterized])
class CalcITCase(
    configMode: TableConfigMode)
  extends TableProgramsCollectionTestBase(configMode) {

  @Test
  def testSelectStarFromTable(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "1,1,Hi\n" + "2,2,Hello\n" + "3,2,Hello world\n" +
      "4,3,Hello world, how are you?\n" + "5,3,I am fine.\n" + "6,3,Luke Skywalker\n" +
      "7,4,Comment#1\n" + "8,4,Comment#2\n" + "9,4,Comment#3\n" + "10,4,Comment#4\n" +
      "11,5,Comment#5\n" + "12,5,Comment#6\n" + "13,5,Comment#7\n" + "14,5,Comment#8\n" +
      "15,5,Comment#9\n" + "16,6,Comment#10\n" + "17,6,Comment#11\n" + "18,6,Comment#12\n" +
      "19,6,Comment#13\n" + "20,6,Comment#14\n" + "21,6,Comment#15\n"

    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testSelectStarFromNestedTable(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable"

    val ds = CollectionDataSets.getSmallNestedTupleDataSet(env).toTable(tEnv).as('a, 'b)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "(1,1),one\n" + "(2,2),two\n" + "(3,3),three\n"

    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testSelectStarFromDataSet(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable"

    val ds = CollectionDataSets.get3TupleDataSet(env)
    tEnv.registerDataSet("MyTable", ds, 'a, 'b, 'c)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "1,1,Hi\n" + "2,2,Hello\n" + "3,2,Hello world\n" +
      "4,3,Hello world, how are you?\n" + "5,3,I am fine.\n" + "6,3,Luke Skywalker\n" +
      "7,4,Comment#1\n" + "8,4,Comment#2\n" + "9,4,Comment#3\n" + "10,4,Comment#4\n" +
      "11,5,Comment#5\n" + "12,5,Comment#6\n" + "13,5,Comment#7\n" + "14,5,Comment#8\n" +
      "15,5,Comment#9\n" + "16,6,Comment#10\n" + "17,6,Comment#11\n" + "18,6,Comment#12\n" +
      "19,6,Comment#13\n" + "20,6,Comment#14\n" + "21,6,Comment#15\n"

    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testSimpleSelectAll(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT a, b, c FROM MyTable"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "1,1,Hi\n" + "2,2,Hello\n" + "3,2,Hello world\n" +
      "4,3,Hello world, how are you?\n" + "5,3,I am fine.\n" + "6,3,Luke Skywalker\n" +
      "7,4,Comment#1\n" + "8,4,Comment#2\n" + "9,4,Comment#3\n" + "10,4,Comment#4\n" +
      "11,5,Comment#5\n" + "12,5,Comment#6\n" + "13,5,Comment#7\n" + "14,5,Comment#8\n" +
      "15,5,Comment#9\n" + "16,6,Comment#10\n" + "17,6,Comment#11\n" + "18,6,Comment#12\n" +
      "19,6,Comment#13\n" + "20,6,Comment#14\n" + "21,6,Comment#15\n"

    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testSelectWithNaming(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT `1-_./??`, b FROM (SELECT _1 as `1-_./??`, _2 as b FROM MyTable)"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "1,1\n" + "2,2\n" + "3,2\n" + "4,3\n" + "5,3\n" + "6,3\n" + "7,4\n" +
      "8,4\n" + "9,4\n" + "10,4\n" + "11,5\n" + "12,5\n" + "13,5\n" + "14,5\n" + "15,5\n" +
      "16,6\n" + "17,6\n" + "18,6\n" + "19,6\n" + "20,6\n" + "21,6\n"

    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test(expected = classOf[ValidationException])
  def testInvalidFields(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT a, foo FROM MyTable"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", ds)

    tEnv.sqlQuery(sqlQuery)
  }

  @Test
  def testAllRejectingFilter(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable WHERE false"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "\n"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testAllPassingFilter(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable WHERE true"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "1,1,Hi\n" + "2,2,Hello\n" + "3,2,Hello world\n" + "4,3,Hello world, " +
      "how are you?\n" + "5,3,I am fine.\n" + "6,3,Luke Skywalker\n" + "7,4," +
      "Comment#1\n" + "8,4,Comment#2\n" + "9,4,Comment#3\n" + "10,4,Comment#4\n" + "11,5," +
      "Comment#5\n" + "12,5,Comment#6\n" + "13,5,Comment#7\n" + "14,5,Comment#8\n" + "15,5," +
      "Comment#9\n" + "16,6,Comment#10\n" + "17,6,Comment#11\n" + "18,6,Comment#12\n" + "19," +
      "6,Comment#13\n" + "20,6,Comment#14\n" + "21,6,Comment#15\n"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testFilterOnString(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable WHERE c LIKE '%world%'"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "3,2,Hello world\n" + "4,3,Hello world, how are you?\n"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testFilterOnInteger(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable WHERE MOD(a,2)=0"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "2,2,Hello\n" + "4,3,Hello world, how are you?\n" +
      "6,3,Luke Skywalker\n" + "8,4," + "Comment#2\n" + "10,4,Comment#4\n" +
      "12,5,Comment#6\n" + "14,5,Comment#8\n" + "16,6," +
      "Comment#10\n" + "18,6,Comment#12\n" + "20,6,Comment#14\n"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testDisjunctivePredicate(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable WHERE a < 2 OR a > 20"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "1,1,Hi\n" + "21,6,Comment#15\n"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testFilterWithAnd(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT * FROM MyTable WHERE MOD(a,2)<>0 AND MOD(b,2)=0"

    val ds = CollectionDataSets.get3TupleDataSet(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", ds)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "3,2,Hello world\n" + "7,4,Comment#1\n" +
      "9,4,Comment#3\n" + "17,6,Comment#11\n" +
      "19,6,Comment#13\n" + "21,6,Comment#15\n"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testAdvancedDataTypes(): Unit = {
    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT a, b, c, DATE '1984-07-12', TIME '14:34:24', " +
      "TIMESTAMP '1984-07-12 14:34:24' FROM MyTable"

    val ds = env.fromElements((
      Date.valueOf("1984-07-12"),
      Time.valueOf("14:34:24"),
      Timestamp.valueOf("1984-07-12 14:34:24")))
    tEnv.registerDataSet("MyTable", ds, 'a, 'b, 'c)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "1984-07-12,14:34:24,1984-07-12 14:34:24.0," +
      "1984-07-12,14:34:24,1984-07-12 14:34:24.0"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testValueConstructor(): Unit = {
    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    val sqlQuery = "SELECT (a, b, c), ARRAY[12, b], MAP[a, c] FROM MyTable " +
      "WHERE (a, b, c) = ('foo', 12, TIMESTAMP '1984-07-12 14:34:24')"
    val rowValue = ("foo", 12, Timestamp.valueOf("1984-07-12 14:34:24"))

    val ds = env.fromElements(rowValue)
    tEnv.registerDataSet("MyTable", ds, 'a, 'b, 'c)

    val result = tEnv.sqlQuery(sqlQuery)

    val expected = "foo,12,1984-07-12 14:34:24.0,[12, 12],{foo=1984-07-12 14:34:24.0}"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)

    // Compare actual object to avoid undetected Calcite flattening
    val resultRow = results.asJava.get(0)
    assertEquals(rowValue._1, resultRow.getField(0).asInstanceOf[Row].getField(0))
    assertEquals(rowValue._2, resultRow.getField(1).asInstanceOf[Array[Integer]](1))
    assertEquals(rowValue._3,
      resultRow.getField(2).asInstanceOf[util.Map[String, Timestamp]].get(rowValue._1))
  }

  @Test
  def testUserDefinedScalarFunction(): Unit = {
    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env, config)

    tEnv.registerFunction("hashCode", OldHashCode)
    tEnv.registerFunction("hashCode", MyHashCode)

    val ds = env.fromElements("a", "b", "c")
    tEnv.registerDataSet("MyTable", ds, 'text)

    val result = tEnv.sqlQuery("SELECT hashCode(text) FROM MyTable")

    val expected = "97\n98\n99"
    val results = result.toDataSet[Row].collect()
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testUdfWithUnicodeParameter(): Unit = {
    val data = List(
      ("a\u0001b", "c\"d", "e\\\"\u0004f"),
      ("x\u0001y", "y\"z", "z\\\"\u0004z")
    )

    val env = ExecutionEnvironment.getExecutionEnvironment

    val tEnv = TableEnvironment.getTableEnvironment(env)

    val splitUDF0 = new SplitUDF(deterministic = true)
    val splitUDF1 = new SplitUDF(deterministic = false)

    tEnv.registerFunction("splitUDF0", splitUDF0)
    tEnv.registerFunction("splitUDF1", splitUDF1)

    // user have to specify '\' with '\\' in SQL
    val sqlQuery = "SELECT " +
      "splitUDF0(a, '\u0001', 0) as a0, " +
      "splitUDF1(a, '\u0001', 0) as a1, " +
      "splitUDF0(b, '\"', 1) as b0, " +
      "splitUDF1(b, '\"', 1) as b1, " +
      "splitUDF0(c, '\\\\\"\u0004', 0) as c0, " +
      "splitUDF1(c, '\\\\\"\u0004', 0) as c1 from T1"

    val t1 = env.fromCollection(data).toTable(tEnv, 'a, 'b, 'c)

    tEnv.registerTable("T1", t1)

    val results = tEnv.sql(sqlQuery).toDataSet[Row].collect()

    val expected = List("a,a,d,d,e,e", "x,x,z,z,z,z").mkString("\n")
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }

  @Test
  def testArrayElementAtFromTableForPojo(): Unit = {

    val env = ExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)

    val p1 = new JavaPojos.Pojo1();
    p1.msg = "msg1";
    val p2 = new JavaPojos.Pojo1();
    p2.msg = "msg2";
    val data = List(
      (1, Array(p1)),
      (2, Array(p2))
    )
    val stream = env.fromCollection(data)
    tEnv.registerDataSet("T", stream, 'a, 'b)

    val sqlQuery = "SELECT a, b[1].msg FROM T"

    val results = tEnv.sqlQuery(sqlQuery).toDataSet[Row].collect()

    val expected = List(
      "1,msg1",
      "2,msg2").mkString("\n")
    TestBaseUtils.compareResultAsText(results.asJava, expected)
  }
}

object MyHashCode extends ScalarFunction {
  def eval(s: String): Int = s.hashCode()
}

object CalcITCase {

  @Parameterized.Parameters(name = "Table config = {0}")
  def parameters(): util.Collection[Array[java.lang.Object]] = {
    Seq[Array[AnyRef]](
      Array(TableProgramsTestBase.DEFAULT),
      Array(TableProgramsTestBase.NO_NULL)).asJava
  }
}
