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

package org.apache.carbondata.spark.rdd

import scala.collection.mutable

import org.apache.spark.TaskContext
import org.apache.spark.sql.Row
import org.apache.spark.util.CollectionAccumulator

import org.apache.carbondata.common.CarbonIterator
import org.apache.carbondata.common.logging.LogServiceFactory
import org.apache.carbondata.core.statusmanager.{LoadMetadataDetails, SegmentStatus}
import org.apache.carbondata.core.util.{SegmentMetaDataInfo, ThreadLocalTaskInfo}
import org.apache.carbondata.processing.loading.{DataLoadExecutor, TableProcessingOperations}
import org.apache.carbondata.processing.loading.model.CarbonLoadModel
import org.apache.carbondata.spark.util.CommonUtil

/**
 * Data load in case of update command .
 */
object UpdateDataLoad {

  def DataLoadForUpdate(
      segId: String,
      index: Long,
      iter: Iterator[Row],
      carbonLoadModel: CarbonLoadModel,
      loadMetadataDetails: LoadMetadataDetails,
      segmentMetaDataAccumulator: CollectionAccumulator[Map[String, SegmentMetaDataInfo]]): Unit = {
    val LOGGER = LogServiceFactory.getLogService(this.getClass.getCanonicalName)
    try {
      val recordReaders = mutable.Buffer[CarbonIterator[Array[AnyRef]]]()
      recordReaders += new NewRddIterator(iter,
          carbonLoadModel,
          TaskContext.get())

      val loader = new SparkPartitionLoader(carbonLoadModel,
        index,
        null,
        loadMetadataDetails)
      // Initialize to set carbon properties
      loader.initialize()

      loadMetadataDetails.setSegmentStatus(SegmentStatus.SUCCESS)
      val executor = new DataLoadExecutor
      TaskContext.get().addTaskCompletionListener { context =>
        // fill segment level minMax to accumulator
      CarbonDataRDDFactory.fillSegmentMetaDataInfoToAccumulator(carbonLoadModel.getTableName,
          segId,
          segmentMetaDataAccumulator)
        executor.close()
        CommonUtil.clearUnsafeMemory(ThreadLocalTaskInfo.getCarbonTaskInfo.getTaskId)
      }
      executor.execute(carbonLoadModel,
        loader.storeLocation,
        recordReaders.toArray)

    } catch {
      case e: Exception =>
        LOGGER.error(e)
        throw e
    } finally {
      TableProcessingOperations.deleteLocalDataLoadFolderLocation(carbonLoadModel, false, false)
    }
  }

}
