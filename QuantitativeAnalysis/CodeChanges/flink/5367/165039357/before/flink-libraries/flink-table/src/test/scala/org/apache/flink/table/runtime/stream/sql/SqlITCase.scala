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

package org.apache.flink.table.runtime.stream.sql

import org.apache.flink.api.common.typeinfo.{BasicTypeInfo, TypeInformation, Types}
import org.apache.flink.api.java.typeutils.{ObjectArrayTypeInfo, RowTypeInfo}
import org.apache.flink.api.scala._
import org.apache.flink.streaming.api
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.datastream.DataStream
import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.watermark.Watermark
import org.apache.flink.table.api.{TableEnvironment, TableSchema}
import org.apache.flink.table.api.scala._
import org.apache.flink.table.expressions.utils.SplitUDF
import org.apache.flink.table.expressions.utils.Func15
import org.apache.flink.table.runtime.stream.sql.SqlITCase.{TestCaseClass, TimestampAndWatermarkWithOffset}
import org.apache.flink.table.runtime.utils.TimeTestUtil.EventTimeSourceFunction
import org.apache.flink.table.runtime.utils._
import org.apache.flink.table.sources.StreamTableSource
import org.apache.flink.types.Row
import org.apache.flink.table.utils.MemoryTableSinkUtil
import org.junit.Assert._
import org.junit._

import scala.collection.JavaConverters._
import scala.collection.mutable

class SqlITCase extends StreamingWithStateTestBase {

  val data = List(
    (1000L, "1", "Hello"),
    (2000L, "2", "Hello"),
    (3000L, null.asInstanceOf[String], "Hello"),
    (4000L, "4", "Hello"),
    (5000L, null.asInstanceOf[String], "Hello"),
    (6000L, "6", "Hello"),
    (7000L, "7", "Hello World"),
    (8000L, "8", "Hello World"),
    (20000L, "20", "Hello World"))

  @Test
  def testRowTimeTumbleWindow(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.testResults = mutable.MutableList()
    StreamITCase.clear
    env.setParallelism(1)

    val stream = env
                 .fromCollection(data)
                 .assignTimestampsAndWatermarks(
                   new TimestampAndWatermarkWithOffset[(Long, String, String)](0L))
    val table = stream.toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.registerTable("T1", table)

    val sqlQuery = "SELECT c, COUNT(*), COUNT(1), COUNT(b) FROM T1 " +
      "GROUP BY TUMBLE(rowtime, interval '5' SECOND), c"

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List("Hello World,2,2,2", "Hello World,1,1,1", "Hello,4,4,3", "Hello,2,2,1")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testNonWindowedCount(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.retractedResults = mutable.ArrayBuffer()
    StreamITCase.clear

    env.setParallelism(1)

    val stream = env.fromCollection(data)
    val table = stream.toTable(tEnv, 'a, 'b, 'c)

    tEnv.registerTable("T1", table)

    val sqlQuery = "SELECT c, COUNT(*), COUNT(1), COUNT(b) FROM T1 GROUP BY c"

    val result = tEnv.sqlQuery(sqlQuery).toRetractStream[Row]
    result.addSink(new StreamITCase.RetractingSink)
    env.execute()

    val expected = List("Hello World,3,3,3", "Hello,6,6,4")
    assertEquals(expected.sorted, StreamITCase.retractedResults.sorted)
  }

   /** test row stream registered table **/
  @Test
  def testRowRegister(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT * FROM MyTableRow WHERE c < 3"

    val data = List(
      Row.of("Hello", "Worlds", Int.box(1)),
      Row.of("Hello", "Hiden", Int.box(5)),
      Row.of("Hello again", "Worlds", Int.box(2)))
        
    implicit val tpe: TypeInformation[Row] = new RowTypeInfo(
      BasicTypeInfo.STRING_TYPE_INFO,
      BasicTypeInfo.STRING_TYPE_INFO,
      BasicTypeInfo.INT_TYPE_INFO) // tpe is automatically 
    
    val ds = env.fromCollection(data)
    
    val t = ds.toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTableRow", t)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List("Hello,Worlds,1","Hello again,Worlds,2")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }
    
  /** test unbounded groupBy (without window) **/
  @Test
  def testUnboundedGroupBy(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT b, COUNT(a) FROM MyTable GROUP BY b"

    val t = StreamTestData.get3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", t)

    val result = tEnv.sqlQuery(sqlQuery).toRetractStream[Row]
    result.addSink(new StreamITCase.RetractingSink).setParallelism(1)
    env.execute()

    val expected = List("1,1", "2,2", "3,3", "4,4", "5,5", "6,6")
    assertEquals(expected.sorted, StreamITCase.retractedResults.sorted)
  }

  @Test
  def testUnboundedGroupByCollect(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    env.setStateBackend(getStateBackend)
    StreamITCase.clear

    val sqlQuery = "SELECT b, COLLECT(a) FROM MyTable GROUP BY b"

    val t = StreamTestData.get3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", t)

    val result = tEnv.sql(sqlQuery).toRetractStream[Row]
    result.addSink(new StreamITCase.RetractingSink).setParallelism(1)
    env.execute()

    val expected = List(
      "1,{1=1}",
      "2,{2=1, 3=1}",
      "3,{4=1, 5=1, 6=1}",
      "4,{7=1, 8=1, 9=1, 10=1}",
      "5,{11=1, 12=1, 13=1, 14=1, 15=1}",
      "6,{16=1, 17=1, 18=1, 19=1, 20=1, 21=1}")
    assertEquals(expected.sorted, StreamITCase.retractedResults.sorted)
  }

  @Test
  def testUnboundedGroupByCollectWithObject(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    env.setStateBackend(getStateBackend)
    StreamITCase.clear

    val sqlQuery = "SELECT b, COLLECT(c) FROM MyTable GROUP BY b"

    val data = List(
      (1, 1, (12, "45.6")),
      (2, 2, (12, "45.612")),
      (3, 2, (13, "41.6")),
      (4, 3, (14, "45.2136")),
      (5, 3, (18, "42.6"))
    )

    tEnv.registerTable("MyTable",
      env.fromCollection(data).toTable(tEnv).as('a, 'b, 'c))

    val result = tEnv.sql(sqlQuery).toRetractStream[Row]
    result.addSink(new StreamITCase.RetractingSink).setParallelism(1)
    env.execute()

    val expected = List(
      "1,{(12,45.6)=1}",
      "2,{(13,41.6)=1, (12,45.612)=1}",
      "3,{(18,42.6)=1, (14,45.2136)=1}")
    assertEquals(expected.sorted, StreamITCase.retractedResults.sorted)
  }

  /** test select star **/
  @Test
  def testSelectStar(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT * FROM MyTable"

    val t = StreamTestData.getSmallNestedTupleDataStream(env).toTable(tEnv).as('a, 'b)
    tEnv.registerTable("MyTable", t)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List("(1,1),one", "(2,2),two", "(3,3),three")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  /** test selection **/
  @Test
  def testSelectExpressionFromTable(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT a * 2, b - 1 FROM MyTable"

    val t = StreamTestData.getSmall3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", t)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List("2,0", "4,1", "6,1")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  /** test filtering with registered table **/
  @Test
  def testSimpleFilter(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT * FROM MyTable WHERE a = 3"

    val t = StreamTestData.getSmall3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("MyTable", t)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List("3,2,Hello world")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  /** test filtering with registered datastream **/
  @Test
  def testDatastreamFilter(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT * FROM MyTable WHERE _1 = 3"

    val t = StreamTestData.getSmall3TupleDataStream(env)
    tEnv.registerDataStream("MyTable", t)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List("3,2,Hello world")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  /** test union with registered tables **/
  @Test
  def testUnion(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT * FROM T1 " +
      "UNION ALL " +
      "SELECT * FROM T2"

    val t1 = StreamTestData.getSmall3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("T1", t1)
    val t2 = StreamTestData.getSmall3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("T2", t2)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "1,1,Hi", "1,1,Hi",
      "2,2,Hello", "2,2,Hello",
      "3,2,Hello world", "3,2,Hello world")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  /** test union with filter **/
  @Test
  def testUnionWithFilter(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT * FROM T1 WHERE a = 3 " +
      "UNION ALL " +
      "SELECT * FROM T2 WHERE a = 2"

    val t1 = StreamTestData.getSmall3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("T1", t1)
    val t2 = StreamTestData.getSmall3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("T2", t2)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "2,2,Hello",
      "3,2,Hello world")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  /** test union of a table and a datastream **/
  @Test
  def testUnionTableWithDataSet(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val sqlQuery = "SELECT c FROM T1 WHERE a = 3 " +
      "UNION ALL " +
      "SELECT c FROM T2 WHERE a = 2"

    val t1 = StreamTestData.getSmall3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("T1", t1)
    val t2 = StreamTestData.get3TupleDataStream(env)
    tEnv.registerDataStream("T2", t2, 'a, 'b, 'c)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List("Hello", "Hello world")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testUnnestPrimitiveArrayFromTable(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val data = List(
      (1, Array(12, 45), Array(Array(12, 45))),
      (2, Array(41, 5), Array(Array(18), Array(87))),
      (3, Array(18, 42), Array(Array(1), Array(45)))
    )
    val stream = env.fromCollection(data)
    tEnv.registerDataStream("T", stream, 'a, 'b, 'c)

    val sqlQuery = "SELECT a, b, s FROM T, UNNEST(T.b) AS A (s)"

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "1,[12, 45],12",
      "1,[12, 45],45",
      "2,[41, 5],41",
      "2,[41, 5],5",
      "3,[18, 42],18",
      "3,[18, 42],42"
    )
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testUnnestArrayOfArrayFromTable(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val data = List(
      (1, Array(12, 45), Array(Array(12, 45))),
      (2, Array(41, 5), Array(Array(18), Array(87))),
      (3, Array(18, 42), Array(Array(1), Array(45)))
    )
    val stream = env.fromCollection(data)
    tEnv.registerDataStream("T", stream, 'a, 'b, 'c)

    val sqlQuery = "SELECT a, s FROM T, UNNEST(T.c) AS A (s)"

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "1,[12, 45]",
      "2,[18]",
      "2,[87]",
      "3,[1]",
      "3,[45]")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testUnnestObjectArrayFromTableWithFilter(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val data = List(
      (1, Array((12, "45.6"), (12, "45.612"))),
      (2, Array((13, "41.6"), (14, "45.2136"))),
      (3, Array((18, "42.6")))
    )
    val stream = env.fromCollection(data)
    tEnv.registerDataStream("T", stream, 'a, 'b)

    val sqlQuery = "SELECT a, b, s, t FROM T, UNNEST(T.b) AS A (s, t) WHERE s > 13"

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "2,[(13,41.6), (14,45.2136)],14,45.2136",
      "3,[(18,42.6)],18,42.6")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testArrayElementAtFromTableForTuple(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val data = List(
      (1, Array((12, 45), (2, 5))),
      (2, Array(null, (1, 49))),
      (3, Array((18, 42), (127, 454)))
    )
    val stream = env.fromCollection(data)
    tEnv.registerDataStream("T", stream, 'a, 'b)

    val sqlQuery = "SELECT a, b[1]._1 FROM T"

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "1,12",
      "2,null",
      "3,18")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testArrayElementAtFromTableForCaseClass(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val data = List(
      (1, Array(TestCaseClass(12, 45), TestCaseClass(2, 5))),
      (2, Array(TestCaseClass(41, 5), TestCaseClass(1, 49))),
      (3, Array(TestCaseClass(18, 42), TestCaseClass(127, 454)))
    )
    val stream = env.fromCollection(data)
    tEnv.registerDataStream("T", stream, 'a, 'b)

    val sqlQuery = "SELECT a, b[1].f1 FROM T"

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "1,45",
      "2,5",
      "3,42")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testArrayElementAtFromTableForPojo(): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    val p1 = new JavaPojos.Pojo1();
    p1.msg = "msg1";
    val p2 = new JavaPojos.Pojo1();
    p2.msg = "msg2";
    val data = List(
      (1, Array(p1)),
      (2, Array(p2))
    )
    val stream = env.fromCollection(data)
    tEnv.registerDataStream("T", stream, 'a, 'b)

    val sqlQuery = "SELECT a, b[1].msg FROM T"

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "1,msg1",
      "2,msg2")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testArrayElementAtFromTableForRow(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    tEnv.registerTableSource("mytable", new StreamTableSource[Row] {
      private val fieldNames: Array[String] = Array("name", "record")
      private val fieldTypes: Array[TypeInformation[_]] =
        Array(
          Types.STRING,
          ObjectArrayTypeInfo.getInfoFor(Types.ROW_NAMED(
            Array[String]("longField", "strField", "floatField", "arrayField"),
            Types.LONG,
            Types.STRING,
            Types.FLOAT,
            ObjectArrayTypeInfo.getInfoFor(
              Types.ROW_NAMED(Array[String]("nestedLong"), Types.LONG)))))
        .asInstanceOf[Array[TypeInformation[_]]]

      override def getDataStream(execEnv: api.environment.StreamExecutionEnvironment)
          : DataStream[Row] = {
        val nestRow1 = new Row(1)
        nestRow1.setField(0, 1213L)
        val mockRow1 = new Row(4)
        mockRow1.setField(0, 273132121L)
        mockRow1.setField(1, "str1")
        mockRow1.setField(2, 123.4f)
        mockRow1.setField(3, Array(nestRow1))
        val mockRow2 = new Row(4)
        mockRow2.setField(0, 27318121L)
        mockRow2.setField(1, "str2")
        mockRow2.setField(2, 987.2f)
        mockRow2.setField(3, Array(nestRow1))
        val data = List(
          Row.of("Mary", Array(mockRow1, mockRow2)),
          Row.of("Mary", Array(mockRow2, mockRow1))).asJava
        execEnv.fromCollection(data, getReturnType)
      }

      override def getReturnType: TypeInformation[Row] = new RowTypeInfo(fieldTypes, fieldNames)
      override def getTableSchema: TableSchema = new TableSchema(fieldNames, fieldTypes)
    })
    StreamITCase.clear

    val sqlQuery = "SELECT name, record[1].floatField, record[1].strField, " +
      "record[2].longField, record[1].arrayField[1].nestedLong FROM mytable"

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "Mary,123.4,str1,27318121,1213",
      "Mary,987.2,str2,273132121,1213")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testHopStartEndWithHaving(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setStateBackend(getStateBackend)
    StreamITCase.clear
    env.setParallelism(1)

    val sqlQueryHopStartEndWithHaving =
      """
        |SELECT
        |  c AS k,
        |  COUNT(a) AS v,
        |  HOP_START(rowtime, INTERVAL '1' MINUTE, INTERVAL '1' MINUTE) AS windowStart,
        |  HOP_END(rowtime, INTERVAL '1' MINUTE, INTERVAL '1' MINUTE) AS windowEnd
        |FROM T1
        |GROUP BY HOP(rowtime, INTERVAL '1' MINUTE, INTERVAL '1' MINUTE), c
        |HAVING
        |  SUM(b) > 1 AND
        |    QUARTER(HOP_START(rowtime, INTERVAL '1' MINUTE, INTERVAL '1' MINUTE)) = 1
      """.stripMargin

    val data = Seq(
      Left(14000005L, (1, 1L, "Hi")),
      Left(14000000L, (2, 1L, "Hello")),
      Left(14000002L, (3, 1L, "Hello")),
      Right(14000010L),
      Left(8640000000L, (4, 1L, "Hello")), // data for the quarter to validate having filter
      Left(8640000001L, (4, 1L, "Hello")),
      Right(8640000010L)
    )

    val t1 = env.addSource(new EventTimeSourceFunction[(Int, Long, String)](data))
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)

    tEnv.registerTable("T1", t1)

    val resultHopStartEndWithHaving = tEnv
      .sqlQuery(sqlQueryHopStartEndWithHaving)
      .toAppendStream[Row]
    resultHopStartEndWithHaving.addSink(new StreamITCase.StringSink[Row])

    env.execute()

    val expected = List(
      "Hello,2,1970-01-01 03:53:00.0,1970-01-01 03:54:00.0"
    )
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testInsertIntoMemoryTable(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    val tEnv = TableEnvironment.getTableEnvironment(env)
    MemoryTableSinkUtil.clear

    val t = StreamTestData.getSmall3TupleDataStream(env)
        .assignAscendingTimestamps(x => x._2)
      .toTable(tEnv, 'a, 'b, 'c, 'rowtime.rowtime)
    tEnv.registerTable("sourceTable", t)

    val fieldNames = Array("d", "e", "f", "t")
    val fieldTypes = Array(Types.INT, Types.LONG, Types.STRING, Types.SQL_TIMESTAMP)
      .asInstanceOf[Array[TypeInformation[_]]]
    val sink = new MemoryTableSinkUtil.UnsafeMemoryAppendTableSink
    tEnv.registerTableSink("targetTable", fieldNames, fieldTypes, sink)

    val sql = "INSERT INTO targetTable SELECT a, b, c, rowtime FROM sourceTable"
    tEnv.sqlUpdate(sql)
    env.execute()

    val expected = List(
      "1,1,Hi,1970-01-01 00:00:00.001",
      "2,2,Hello,1970-01-01 00:00:00.002",
      "3,2,Hello world,1970-01-01 00:00:00.002")
    assertEquals(expected.sorted, MemoryTableSinkUtil.results.sorted)
  }

  @Test
  def testUdfWithUnicodeParameter(): Unit = {
    val data = List(
      ("a\u0001b", "c\"d", "e\\\"\u0004f"),
      ("x\u0001y", "y\"z", "z\\\"\u0004z")
    )

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

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

    val result = tEnv.sql(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List("a,a,d,d,e,e", "x,x,z,z,z,z")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }

  @Test
  def testUDFWithLongVarargs(): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val tEnv = TableEnvironment.getTableEnvironment(env)
    StreamITCase.clear

    tEnv.registerFunction("func15", Func15)

    val parameters = "c," + (0 until 255).map(_ => "a").mkString(",")
    val sqlQuery = s"SELECT func15($parameters) FROM T1"

    val t1 = StreamTestData.getSmall3TupleDataStream(env).toTable(tEnv).as('a, 'b, 'c)
    tEnv.registerTable("T1", t1)

    val result = tEnv.sqlQuery(sqlQuery).toAppendStream[Row]
    result.addSink(new StreamITCase.StringSink[Row])
    env.execute()

    val expected = List(
      "Hi255",
      "Hello255",
      "Hello world255")
    assertEquals(expected.sorted, StreamITCase.testResults.sorted)
  }
}

object SqlITCase {

  case class TestCaseClass(f0: Integer, f1: Integer) extends Serializable

  class TimestampAndWatermarkWithOffset[T <: Product](
      offset: Long) extends AssignerWithPunctuatedWatermarks[T] {

    override def checkAndGetNextWatermark(
        lastElement: T,
        extractedTimestamp: Long): Watermark = {
      new Watermark(extractedTimestamp - offset)
    }

    override def extractTimestamp(
        element: T,
        previousElementTimestamp: Long): Long = {
      element.productElement(0).asInstanceOf[Long]
    }
  }

}
