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

package org.apache.flink.table.planner.plan.nodes.physical.batch

import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.core.Calc
import org.apache.calcite.rex.{RexInputRef, RexProgram}
import org.apache.flink.api.dag.Transformation
import org.apache.flink.streaming.api.transformations.OneInputTransformation
import org.apache.flink.table.dataformat.BaseRow
import org.apache.flink.table.planner.calcite.{FlinkTypeFactory, FlinkTypeSystem}
import org.apache.flink.table.planner.codegen.{CalcCodeGenerator, CodeGeneratorContext}
import org.apache.flink.table.planner.delegation.BatchPlanner
import org.apache.flink.table.planner.plan.nodes.common.CommonPythonCalc
import org.apache.flink.table.runtime.operators.AbstractProcessStreamOperator
import org.apache.flink.table.runtime.typeutils.BaseRowTypeInfo

/**
  * Batch physical RelNode for Python ScalarFunctions.
  */
class BatchExecPythonCalc(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputRel: RelNode,
    calcProgram: RexProgram,
    outputRowType: RelDataType)
  extends BatchExecCalcBase(
    cluster,
    traitSet,
    inputRel,
    calcProgram,
    outputRowType)
  with CommonPythonCalc {

  override def copy(traitSet: RelTraitSet, child: RelNode, program: RexProgram): Calc = {
    new BatchExecPythonCalc(cluster, traitSet, child, program, outputRowType)
  }

  override protected def translateToPlanInternal(planner: BatchPlanner): Transformation[BaseRow] = {
    val inputTransform = getInputNodes.get(0).translateToPlan(planner)
      .asInstanceOf[Transformation[BaseRow]]

    val (pythonInputTransform, pythonOperatorResultTyeInfo, resultProjectList) =
      generatePythonOneInputStream(inputTransform, calcProgram, getRelDetailedDescription)

    val onlyFilter = resultProjectList.zipWithIndex.forall { case (rexNode, index) =>
      rexNode.isInstanceOf[RexInputRef] && rexNode.asInstanceOf[RexInputRef].getIndex == index
    }

    if (onlyFilter) {
      pythonInputTransform
    } else {
      val config = planner.getTableConfig
      val ctx = CodeGeneratorContext(config).setOperatorBaseClass(
        classOf[AbstractProcessStreamOperator[BaseRow]])
      val outputType = FlinkTypeFactory.toLogicalRowType(getRowType)
      val rexProgram = createProjectionRexProgram(
        pythonOperatorResultTyeInfo.toRowType, outputType, resultProjectList, cluster)
      val operator = CalcCodeGenerator.generateCalcOperator(
        ctx,
        cluster,
        pythonInputTransform,
        outputType,
        config,
        rexProgram,
        None,
        opName = "BatchCalc"
      )

      new OneInputTransformation(
        pythonInputTransform,
        getRelDetailedDescription,
        operator,
        BaseRowTypeInfo.of(outputType),
        inputTransform.getParallelism)
    }
  }
}
