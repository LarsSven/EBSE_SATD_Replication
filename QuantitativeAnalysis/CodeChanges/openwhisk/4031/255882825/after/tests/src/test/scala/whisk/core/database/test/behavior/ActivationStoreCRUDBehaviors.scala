/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database.test.behavior

import whisk.common.TransactionId
import whisk.core.database.NoDocumentException
import whisk.core.entity._

import scala.util.Random

trait ActivationStoreCRUDBehaviors extends ActivationStoreBehaviorBase {

  behavior of s"${storeType}ActivationStore store"

  it should "put activation and get docinfo" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action = s"action1_${Random.alphanumeric.take(4).mkString}"
    val activation = newActivation(namespace, action, 1L)
    val doc = store(activation, context)
    doc shouldBe activation.docinfo
  }

  behavior of s"${storeType}ActivationStore delete"

  it should "deletes existing activation" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action = s"action1_${Random.alphanumeric.take(4).mkString}"
    val activation = newActivation(namespace, action, 1L)
    store(activation, context)
    activationStore.delete(ActivationId(activation.docid.asString), context).futureValue shouldBe true
  }

  it should "throws NoDocumentException when activation does not exist" in {
    implicit val tid: TransactionId = transId()
    activationStore.delete(ActivationId("non-existing-doc"), context).failed.futureValue shouldBe a[NoDocumentException]
  }

  behavior of s"${storeType}ActivationStore get"

  it should "get existing activation matching id" in {
    implicit val tid: TransactionId = transId()
    val namespace = s"ns_${Random.alphanumeric.take(4).mkString}"
    val action = s"action1_${Random.alphanumeric.take(4).mkString}"
    val activation = newActivation(namespace, action, 1L)
    store(activation, context)
    activationStore.get(ActivationId(activation.docid.asString), context).futureValue shouldBe activation
  }

  it should "throws NoDocumentException when activation does not exist" in {
    implicit val tid: TransactionId = transId()
    activationStore.get(ActivationId("non-existing-doc"), context).failed.futureValue shouldBe a[NoDocumentException]
  }
}
