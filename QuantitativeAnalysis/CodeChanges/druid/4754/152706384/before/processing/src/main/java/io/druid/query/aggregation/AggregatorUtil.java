/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query.aggregation;

import com.google.common.collect.Lists;
import io.druid.guice.annotations.PublicApi;
import io.druid.java.util.common.Pair;
import io.druid.math.expr.Expr;
import io.druid.math.expr.ExprEval;
import io.druid.math.expr.ExprMacroTable;
import io.druid.math.expr.Parser;
import io.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import io.druid.segment.BaseDoubleColumnValueSelector;
import io.druid.segment.BaseFloatColumnValueSelector;
import io.druid.segment.BaseLongColumnValueSelector;
import io.druid.segment.ColumnSelectorFactory;
import io.druid.segment.ColumnValueSelector;
import io.druid.segment.DoubleColumnSelector;
import io.druid.segment.FloatColumnSelector;
import io.druid.segment.LongColumnSelector;
import io.druid.segment.virtual.ExpressionSelectors;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

@PublicApi
public class AggregatorUtil
{
  public static final byte STRING_SEPARATOR = (byte) 0xFF;
  public static final byte COUNT_CACHE_TYPE_ID = 0x0;
  public static final byte LONG_SUM_CACHE_TYPE_ID = 0x1;
  public static final byte DOUBLE_SUM_CACHE_TYPE_ID = 0x2;
  public static final byte DOUBLE_MAX_CACHE_TYPE_ID = 0x3;
  public static final byte DOUBLE_MIN_CACHE_TYPE_ID = 0x4;
  public static final byte HYPER_UNIQUE_CACHE_TYPE_ID = 0x5;
  public static final byte JS_CACHE_TYPE_ID = 0x6;
  public static final byte HIST_CACHE_TYPE_ID = 0x7;
  public static final byte CARD_CACHE_TYPE_ID = 0x8;
  public static final byte FILTERED_AGG_CACHE_TYPE_ID = 0x9;
  public static final byte LONG_MAX_CACHE_TYPE_ID = 0xA;
  public static final byte LONG_MIN_CACHE_TYPE_ID = 0xB;
  public static final byte FLOAT_SUM_CACHE_TYPE_ID = 0xC;
  public static final byte FLOAT_MAX_CACHE_TYPE_ID = 0xD;
  public static final byte FLOAT_MIN_CACHE_TYPE_ID = 0xE;
  public static final byte SKETCH_MERGE_CACHE_TYPE_ID = 0xF;
  public static final byte DISTINCT_COUNT_CACHE_KEY = 0x10;
  public static final byte FLOAT_LAST_CACHE_TYPE_ID = 0x11;
  public static final byte APPROX_HIST_CACHE_TYPE_ID = 0x12;
  public static final byte APPROX_HIST_FOLDING_CACHE_TYPE_ID = 0x13;
  public static final byte DOUBLE_FIRST_CACHE_TYPE_ID = 0x14;
  public static final byte DOUBLE_LAST_CACHE_TYPE_ID = 0x15;
  public static final byte FLOAT_FIRST_CACHE_TYPE_ID = 0x16;
  public static final byte LONG_FIRST_CACHE_TYPE_ID = 0x17;
  public static final byte LONG_LAST_CACHE_TYPE_ID = 0x18;
  public static final byte TIMESTAMP_CACHE_TYPE_ID = 0x19;
  public static final byte VARIANCE_CACHE_TYPE_ID = 0x1A;

  // Quantiles sketch aggregator
  public static final byte QUANTILES_DOUBLES_SKETCH_BUILD_CACHE_TYPE_ID = 0x1B;
  public static final byte QUANTILES_DOUBLES_SKETCH_MERGE_CACHE_TYPE_ID = 0x1C;
  public static final byte QUANTILES_DOUBLES_SKETCH_TO_HISTOGRAM_CACHE_TYPE_ID = 0x1D;
  public static final byte QUANTILES_DOUBLES_SKETCH_TO_QUANTILE_CACHE_TYPE_ID = 0x1E;
  public static final byte QUANTILES_DOUBLES_SKETCH_TO_QUANTILES_CACHE_TYPE_ID = 0x1F;
  public static final byte QUANTILES_DOUBLES_SKETCH_TO_STRING_CACHE_TYPE_ID = 0x20;

  /**
   * returns the list of dependent postAggregators that should be calculated in order to calculate given postAgg
   *
   * @param postAggregatorList List of postAggregator, there is a restriction that the list should be in an order
   *                           such that all the dependencies of any given aggregator should occur before that aggregator.
   *                           See AggregatorUtilTest.testOutOfOrderPruneDependentPostAgg for example.
   * @param postAggName        name of the postAgg on which dependency is to be calculated
   *
   * @return the list of dependent postAggregators
   */
  public static List<PostAggregator> pruneDependentPostAgg(List<PostAggregator> postAggregatorList, String postAggName)
  {
    LinkedList<PostAggregator> rv = Lists.newLinkedList();
    Set<String> deps = new HashSet<>();
    deps.add(postAggName);
    // Iterate backwards to find the last calculated aggregate and add dependent aggregator as we find dependencies in reverse order
    for (PostAggregator agg : Lists.reverse(postAggregatorList)) {
      if (deps.contains(agg.getName())) {
        rv.addFirst(agg); // add to the beginning of List
        deps.remove(agg.getName());
        deps.addAll(agg.getDependentFields());
      }
    }

    return rv;
  }

  public static Pair<List<AggregatorFactory>, List<PostAggregator>> condensedAggregators(
      List<AggregatorFactory> aggList,
      List<PostAggregator> postAggList,
      String metric
  )
  {

    List<PostAggregator> condensedPostAggs = AggregatorUtil.pruneDependentPostAgg(
        postAggList,
        metric
    );
    // calculate dependent aggregators for these postAgg
    Set<String> dependencySet = new HashSet<>();
    dependencySet.add(metric);
    for (PostAggregator postAggregator : condensedPostAggs) {
      dependencySet.addAll(postAggregator.getDependentFields());
    }

    List<AggregatorFactory> condensedAggs = Lists.newArrayList();
    for (AggregatorFactory aggregatorSpec : aggList) {
      if (dependencySet.contains(aggregatorSpec.getName())) {
        condensedAggs.add(aggregatorSpec);
      }
    }
    return new Pair(condensedAggs, condensedPostAggs);
  }

  public static BaseFloatColumnValueSelector makeColumnValueSelectorWithFloatDefault(
      final ColumnSelectorFactory metricFactory,
      final ExprMacroTable macroTable,
      final String fieldName,
      final String fieldExpression,
      final Float nullValue
  )
  {
    if (fieldName != null && fieldExpression == null) {
      return metricFactory.makeColumnValueSelector(fieldName);
    }
    if (fieldName == null && fieldExpression != null) {
      final Expr expr = Parser.parse(fieldExpression, macroTable);
      final ColumnValueSelector<ExprEval> baseSelector = ExpressionSelectors.makeExprEvalSelector(metricFactory, expr);
      class ExpressionFloatColumnSelector implements FloatColumnSelector
      {
        @Override
        public float getFloat()
        {
          final ExprEval exprEval = baseSelector.getObject();
          return exprEval.isNull() ? nullValue : (float) exprEval.asDouble();
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {
          inspector.visit("baseSelector", baseSelector);
        }

        @Override
        public boolean isNull()
        {
          return baseSelector.getObject().isNull();
        }
      }
      return new ExpressionFloatColumnSelector();
    }
    throw new IllegalArgumentException("Must have a valid, non-null fieldName or expression");
  }

  public static BaseLongColumnValueSelector makeColumnValueSelectorWithLongDefault(
      final ColumnSelectorFactory metricFactory,
      final ExprMacroTable macroTable,
      final String fieldName,
      final String fieldExpression,
      final long nullValue
  )
  {
    if (fieldName != null && fieldExpression == null) {
      return metricFactory.makeColumnValueSelector(fieldName);
    }
    if (fieldName == null && fieldExpression != null) {
      final Expr expr = Parser.parse(fieldExpression, macroTable);
      final ColumnValueSelector<ExprEval> baseSelector = ExpressionSelectors.makeExprEvalSelector(metricFactory, expr);
      class ExpressionLongColumnSelector implements LongColumnSelector
      {
        @Override
        public long getLong()
        {
          final ExprEval exprEval = baseSelector.getObject();
          return exprEval.isNull() ? nullValue : exprEval.asLong();
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {
          inspector.visit("baseSelector", baseSelector);
        }

        @Override
        public boolean isNull()
        {
          final ExprEval exprEval = baseSelector.getObject();
          return exprEval.isNull();
        }
      }
      return new ExpressionLongColumnSelector();
    }
    throw new IllegalArgumentException("Must have a valid, non-null fieldName or expression");
  }

  public static BaseDoubleColumnValueSelector makeColumnValueSelectorWithDoubleDefault(
      final ColumnSelectorFactory metricFactory,
      final ExprMacroTable macroTable,
      final String fieldName,
      final String fieldExpression,
      final double nullValue
  )
  {
    if (fieldName != null && fieldExpression == null) {
      return metricFactory.makeColumnValueSelector(fieldName);
    }
    if (fieldName == null && fieldExpression != null) {
      final Expr expr = Parser.parse(fieldExpression, macroTable);
      final ColumnValueSelector<ExprEval> baseSelector = ExpressionSelectors.makeExprEvalSelector(metricFactory, expr);
      class ExpressionDoubleColumnSelector implements DoubleColumnSelector
      {
        @Override
        public double getDouble()
        {
          final ExprEval exprEval = baseSelector.getObject();
          return exprEval.isNull() ? nullValue : exprEval.asDouble();
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {
          inspector.visit("baseSelector", baseSelector);
        }

        @Override
        public boolean isNull()
        {
          final ExprEval exprEval = baseSelector.getObject();
          return exprEval.isNull();
        }
      }
      return new ExpressionDoubleColumnSelector();
    }
    throw new IllegalArgumentException("Must have a valid, non-null fieldName or expression");
  }
}
