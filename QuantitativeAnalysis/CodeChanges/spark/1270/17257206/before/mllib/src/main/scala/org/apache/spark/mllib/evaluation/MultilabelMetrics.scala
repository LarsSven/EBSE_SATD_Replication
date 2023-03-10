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

package org.apache.spark.mllib.evaluation

import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._

/**
 * Evaluator for multilabel classification.
 * @param predictionAndLabels an RDD of (predictions, labels) pairs, both are non-null sets.
 */
class MultilabelMetrics(predictionAndLabels: RDD[(Set[Double], Set[Double])]) {

  private lazy val numDocs: Long = predictionAndLabels.count()

  private lazy val numLabels: Long = predictionAndLabels.flatMap { case (_, labels) =>
    labels}.distinct.count

  /**
   * Returns subset accuracy
   * (for equal sets of labels)
   */
  lazy val subsetAccuracy: Double = predictionAndLabels.filter { case (predictions, labels) =>
    predictions == labels
  }.count().toDouble / numDocs

  /**
   * Returns accuracy
   */
  lazy val accuracy: Double = predictionAndLabels.map { case (predictions, labels) =>
    labels.intersect(predictions).size.toDouble / labels.union(predictions).size
  }.sum / numDocs

  /**
   * Returns Hamming-loss
   */
  lazy val hammingLoss: Double = predictionAndLabels.map { case (predictions, labels) =>
    labels.size + predictions.size - 2 * labels.intersect(predictions).size
  }.sum / (numDocs * numLabels)

  /**
   * Returns document-based precision averaged by the number of documents
   */
  lazy val precision: Double = predictionAndLabels.map { case (predictions, labels) =>
    if (predictions.size > 0) {
      predictions.intersect(labels).size.toDouble / predictions.size
    } else {
      0
    }
  }.sum / numDocs

  /**
   * Returns document-based recall averaged by the number of documents
   */
  lazy val recall: Double = predictionAndLabels.map { case (predictions, labels) =>
    labels.intersect(predictions).size.toDouble / labels.size
  }.sum / numDocs

  /**
   * Returns document-based f1-measure averaged by the number of documents
   */
  lazy val f1Measure: Double = predictionAndLabels.map { case (predictions, labels) =>
    2.0 * predictions.intersect(labels).size / (predictions.size + labels.size)
  }.sum / numDocs


  private lazy val tpPerClass = predictionAndLabels.flatMap { case (predictions, labels) =>
    predictions.intersect(labels)
  }.countByValue()

  private lazy val fpPerClass = predictionAndLabels.flatMap { case (predictions, labels) =>
    predictions.diff(labels)
  }.countByValue()

  private lazy val fnPerClass = predictionAndLabels.flatMap { case(predictions, labels) =>
    labels.diff(predictions)
  }.countByValue()

  /**
   * Returns precision for a given label (category)
   * @param label the label.
   */
  def precision(label: Double) = {
    val tp = tpPerClass(label)
    val fp = fpPerClass.getOrElse(label, 0L)
    if (tp + fp == 0) 0 else tp.toDouble / (tp + fp)
  }

  /**
   * Returns recall for a given label (category)
   * @param label the label.
   */
  def recall(label: Double) = {
    val tp = tpPerClass(label)
    val fn = fnPerClass.getOrElse(label, 0L)
    if (tp + fn == 0) 0 else tp.toDouble / (tp + fn)
  }

  /**
   * Returns f1-measure for a given label (category)
   * @param label the label.
   */
  def f1Measure(label: Double) = {
    val p = precision(label)
    val r = recall(label)
    if((p + r) == 0) 0 else 2 * p * r / (p + r)
  }

  private lazy val sumTp = tpPerClass.foldLeft(0L) { case (sum, (_, tp)) => sum + tp }
  private lazy val sumFpClass = fpPerClass.foldLeft(0L) { case (sum, (_, fp)) => sum + fp }
  private lazy val sumFnClass = fnPerClass.foldLeft(0L) { case (sum, (_, fn)) => sum + fn }

  /**
   * Returns micro-averaged label-based precision
   * (equals to micro-averaged document-based precision)
   */
  lazy val microPrecision = {
    val sumFp = fpPerClass.foldLeft(0L){ case(cum, (_, fp)) => cum + fp}
    sumTp.toDouble / (sumTp + sumFp)
  }

  /**
   * Returns micro-averaged label-based recall
   * (equals to micro-averaged document-based recall)
   */
  lazy val microRecall = {
    val sumFn = fnPerClass.foldLeft(0.0){ case(cum, (_, fn)) => cum + fn}
    sumTp.toDouble / (sumTp + sumFn)
  }

  /**
   * Returns micro-averaged label-based f1-measure
   * (equals to micro-averaged document-based f1-measure)
   */
  lazy val microF1Measure = 2.0 * sumTp / (2 * sumTp + sumFnClass + sumFpClass)

  /**
   * Returns the sequence of labels in ascending order
   */
  lazy val labels: Array[Double] = tpPerClass.keys.toArray.sorted
}
