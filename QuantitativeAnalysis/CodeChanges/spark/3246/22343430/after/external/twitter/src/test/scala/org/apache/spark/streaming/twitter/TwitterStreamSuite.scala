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

package org.apache.spark.streaming.twitter


import org.scalatest.{BeforeAndAfter, FunSuite}
import twitter4j.Status
import twitter4j.auth.{NullAuthorization, Authorization}

import org.apache.spark.Logging
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream.ReceiverInputDStream

class TwitterStreamSuite extends FunSuite with BeforeAndAfter with Logging {

  val batchDuration = Seconds(1)

  private val master: String = "local[2]"

  private val framework: String = this.getClass.getSimpleName

  test("twitter input stream") {
    val ssc = new StreamingContext(master, framework, batchDuration)
    val filters = Seq("filter1", "filter2")
    val locations: Seq[(Double, Double)] = Seq((-180, -90), (180, 90))
    val authorization: Authorization = NullAuthorization.getInstance()

    // tests the API, does not actually test data receiving
    val test1: ReceiverInputDStream[Status] = TwitterUtils.createStream(ssc, None)
    val test2: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, None, filters)

    val test3: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, None)
    TwitterUtils.setLocations(test3, locations)

    val test4: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, None, filters)
    TwitterUtils.setLocations(test4, locations)
 
    val test5: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, None, filters, StorageLevel.MEMORY_AND_DISK_SER_2)

    val test6: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, None, filters, StorageLevel.MEMORY_AND_DISK_SER_2)
    TwitterUtils.setLocations(test6, locations)

    val test7: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, Some(authorization))
    val test8: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, Some(authorization), filters)

    val test9: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, Some(authorization))
    TwitterUtils.setLocations(test9, locations)

    val test10: ReceiverInputDStream[Status] =
      TwitterUtils.createStream(ssc, Some(authorization), filters)
    TwitterUtils.setLocations(test10, locations)
 
    val test11: ReceiverInputDStream[Status] = TwitterUtils.createStream(
      ssc, Some(authorization), filters, StorageLevel.MEMORY_AND_DISK_SER_2)

    val test12: ReceiverInputDStream[Status] = TwitterUtils.createStream(
      ssc, Some(authorization), filters, StorageLevel.MEMORY_AND_DISK_SER_2)
    TwitterUtils.setLocations(test12, locations)
 
    // Note that actually testing the data receiving is hard as authentication keys are
    // necessary for accessing Twitter live stream
    ssc.stop()
  }
}
