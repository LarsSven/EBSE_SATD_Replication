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

import java.io.{FileInputStream, InputStream}
import java.util.{NoSuchElementException, Properties}

import scala.xml.XML

import org.apache.commons.lang3.StringUtils

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.SchedulingMode.SchedulingMode
import org.apache.spark.util.Utils

/**
 * An interface to build Schedulable tree
 * buildPools: build the tree nodes(pools)
 * addTaskSetManager: build the leaf nodes(TaskSetManagers)
 */
private[spark] trait SchedulableBuilder {
  def rootPool: Pool

  def buildPools(): Unit

  def addTaskSetManager(manager: Schedulable, properties: Properties): Unit
}

private[spark] class FIFOSchedulableBuilder(val rootPool: Pool)
  extends SchedulableBuilder with Logging {

  override def buildPools() {
    // nothing
  }

  override def addTaskSetManager(manager: Schedulable, properties: Properties) {
    rootPool.addSchedulable(manager)
  }
}

private[spark] class FairSchedulableBuilder(val rootPool: Pool, conf: SparkConf)
  extends SchedulableBuilder with Logging {

  val schedulerAllocFile = conf.getOption("spark.scheduler.allocation.file")
  val DEFAULT_SCHEDULER_FILE = "fairscheduler.xml"
  val FAIR_SCHEDULER_PROPERTIES = "spark.scheduler.pool"
  val DEFAULT_POOL_NAME = "default"
  val MINIMUM_SHARES_PROPERTY = "minShare"
  val SCHEDULING_MODE_PROPERTY = "schedulingMode"
  val WEIGHT_PROPERTY = "weight"
  val POOL_NAME_PROPERTY = "@name"
  val POOLS_PROPERTY = "pool"
  val DEFAULT_SCHEDULING_MODE = SchedulingMode.FIFO
  val DEFAULT_MINIMUM_SHARE = 0
  val DEFAULT_WEIGHT = 1

  override def buildPools() {
    var is: Option[InputStream] = None
    try {
      is = Option {
        schedulerAllocFile.map { f =>
          new FileInputStream(f)
        }.getOrElse {
          Utils.getSparkClassLoader.getResourceAsStream(DEFAULT_SCHEDULER_FILE)
        }
      }

      is.foreach { i => buildFairSchedulerPool(i) }
    } finally {
      is.foreach(_.close())
    }

    // finally create "default" pool
    buildDefaultPool()
  }

  private def buildDefaultPool() {
    if (rootPool.getSchedulableByName(DEFAULT_POOL_NAME) == null) {
      val pool = new Pool(DEFAULT_POOL_NAME, DEFAULT_SCHEDULING_MODE,
        DEFAULT_MINIMUM_SHARE, DEFAULT_WEIGHT)
      rootPool.addSchedulable(pool)
      logInfo("Created default pool %s, schedulingMode: %s, minShare: %d, weight: %d".format(
        DEFAULT_POOL_NAME, DEFAULT_SCHEDULING_MODE, DEFAULT_MINIMUM_SHARE, DEFAULT_WEIGHT))
    }
  }

  private def buildFairSchedulerPool(is: InputStream) {
    val xml = XML.load(is)
    for (poolNode <- (xml \\ POOLS_PROPERTY)) {

      val poolName = (poolNode \ POOL_NAME_PROPERTY).text

      val xmlSchedulingMode = (poolNode \ SCHEDULING_MODE_PROPERTY).text
      val schedulingMode = getSchedulingModeValue(xmlSchedulingMode, DEFAULT_SCHEDULING_MODE)

      val xmlMinShare = (poolNode \ MINIMUM_SHARES_PROPERTY).text
      val minShare = getIntValue(MINIMUM_SHARES_PROPERTY, xmlMinShare, DEFAULT_MINIMUM_SHARE)

      val xmlWeight = (poolNode \ WEIGHT_PROPERTY).text
      val weight = getIntValue(WEIGHT_PROPERTY, xmlWeight, DEFAULT_WEIGHT)

      rootPool.addSchedulable(new Pool(poolName, schedulingMode, minShare, weight))

      logInfo("Created pool %s, schedulingMode: %s, minShare: %d, weight: %d".format(
        poolName, schedulingMode, minShare, weight))
    }
  }

  private def getSchedulingModeValue(data: String, defaultValue: SchedulingMode): SchedulingMode = {
    if (StringUtils.isNotBlank(data)
      && checkType(SchedulingMode.withName(data.toUpperCase))
      && SchedulingMode.withName(data.toUpperCase) != SchedulingMode.NONE) {
      SchedulingMode.withName(data.toUpperCase)
    } else {
      logWarning(s"Unsupported schedulingMode: $data, using the default schedulingMode: " +
        s"$defaultValue")
      defaultValue
    }
  }

  private def getIntValue(propertyName: String, data: String, defaultValue: Int): Int = {
    if (StringUtils.isNotBlank(data) && checkType(data.toInt)) {
      data.toInt
    } else {
      logWarning(s"$propertyName is blank or invalid: $data, using the default $propertyName: " +
        s"$defaultValue")
      defaultValue
    }
  }

  private def checkType(fun: => Any): Boolean = {
    try {
      fun
      true
    } catch {
      case e: NumberFormatException => false
      case e: NoSuchElementException => false
    }
  }

  override def addTaskSetManager(manager: Schedulable, properties: Properties) {
    var poolName = DEFAULT_POOL_NAME
    var parentPool = rootPool.getSchedulableByName(poolName)
    if (properties != null) {
      poolName = properties.getProperty(FAIR_SCHEDULER_PROPERTIES, DEFAULT_POOL_NAME)
      parentPool = rootPool.getSchedulableByName(poolName)
      if (parentPool == null) {
        // we will create a new pool that user has configured in app
        // instead of being defined in xml file
        parentPool = new Pool(poolName, DEFAULT_SCHEDULING_MODE,
          DEFAULT_MINIMUM_SHARE, DEFAULT_WEIGHT)
        rootPool.addSchedulable(parentPool)
        logInfo("Created pool %s, schedulingMode: %s, minShare: %d, weight: %d".format(
          poolName, DEFAULT_SCHEDULING_MODE, DEFAULT_MINIMUM_SHARE, DEFAULT_WEIGHT))
      }
    }
    parentPool.addSchedulable(manager)
    logInfo("Added task set " + manager.name + " tasks to pool " + poolName)
  }
}
