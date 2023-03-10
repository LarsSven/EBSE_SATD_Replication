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

package org.apache.spark.scheduler

/**
 * A simple listener for application events.
 *
 * This listener assumes at most one of each of SparkListenerApplicationStart and
 * SparkListenerApplicationEnd will be received. Otherwise, only the latest event
 * of each type will take effect.
 */
private[spark] class ApplicationEventListener extends SparkListener {
  var appName = "<Not Started>"
  var sparkUser = "<Not Started>"
  var startTime = -1L
  var endTime = -1L

  def applicationStarted = startTime != -1

  def applicationFinished = endTime != -1

  def applicationDuration: Long = {
    val difference = endTime - startTime
    if (applicationStarted && applicationFinished && difference > 0) difference else -1L
  }

  override def onApplicationStart(applicationStart: SparkListenerApplicationStart) {
    appName = applicationStart.appName
    startTime = applicationStart.time
    sparkUser = applicationStart.sparkUser
  }

  override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd) {
    endTime = applicationEnd.time
  }
}
