/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr;

import com.google.common.base.Preconditions;
import java.util.function.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.common.DynConstructors;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.hadoop.HadoopTables;

public class InputFormatConfig {

  private InputFormatConfig() {}

  public static final String REUSE_CONTAINERS = "iceberg.mr.reuse.containers";
  public static final String CASE_SENSITIVE = "iceberg.mr.case.sensitive";
  public static final String SKIP_RESIDUAL_FILTERING = "skip.residual.filtering";
  public static final String AS_OF_TIMESTAMP = "iceberg.mr.as.of.time";
  public static final String FILTER_EXPRESSION = "iceberg.mr.filter.expression";
  public static final String IN_MEMORY_DATA_MODEL = "iceberg.mr.in.memory.data.model";
  public static final String READ_SCHEMA = "iceberg.mr.read.schema";
  public static final String SNAPSHOT_ID = "iceberg.mr.snapshot.id";
  public static final String SPLIT_SIZE = "iceberg.mr.split.size";
  public static final String TABLE_PATH = "iceberg.mr.table.path";
  public static final String TABLE_SCHEMA = "iceberg.mr.table.schema";
  public static final String LOCALITY = "iceberg.mr.locality";
  public static final String CATALOG = "iceberg.mr.catalog";

  public static class ConfigBuilder {
    private final Configuration conf;

    public ConfigBuilder(Configuration conf) {
      this.conf = conf;
      // defaults
      conf.setBoolean(SKIP_RESIDUAL_FILTERING, false);
      conf.setBoolean(CASE_SENSITIVE, true);
      conf.setBoolean(REUSE_CONTAINERS, false);
      conf.setBoolean(LOCALITY, false);
    }

    public ConfigBuilder readFrom(String path) {
      conf.set(TABLE_PATH, path);
      Table table = findTable(conf);
      conf.set(TABLE_SCHEMA, SchemaParser.toJson(table.schema()));
      return this;
    }

    public ConfigBuilder filter(Expression expression) {
      conf.set(FILTER_EXPRESSION, SerializationUtil.serializeToBase64(expression));
      return this;
    }

    public ConfigBuilder project(Schema schema) {
      conf.set(READ_SCHEMA, SchemaParser.toJson(schema));
      return this;
    }

    public ConfigBuilder reuseContainers(boolean reuse) {
      conf.setBoolean(InputFormatConfig.REUSE_CONTAINERS, reuse);
      return this;
    }

    public ConfigBuilder caseSensitive(boolean caseSensitive) {
      conf.setBoolean(InputFormatConfig.CASE_SENSITIVE, caseSensitive);
      return this;
    }

    public ConfigBuilder snapshotId(long snapshotId) {
      conf.setLong(SNAPSHOT_ID, snapshotId);
      return this;
    }

    public ConfigBuilder asOfTime(long asOfTime) {
      conf.setLong(AS_OF_TIMESTAMP, asOfTime);
      return this;
    }

    public ConfigBuilder splitSize(long splitSize) {
      conf.setLong(SPLIT_SIZE, splitSize);
      return this;
    }

    /**
     * If this API is called. The input splits
     * constructed will have host location information
     */
    public ConfigBuilder preferLocality() {
      conf.setBoolean(LOCALITY, true);
      return this;
    }

    public ConfigBuilder catalogFunc(Class<? extends Function<Configuration, Catalog>> catalogFuncClass) {
      conf.setClass(CATALOG, catalogFuncClass, Function.class);
      return this;
    }

    /**
     * Compute platforms pass down filters to data sources. If the data source cannot apply some filters, or only
     * partially applies the filter, it will return the residual filter back. If the platform can correctly apply
     * the residual filters, then it should call this api. Otherwise the current api will throw an exception if the
     * passed in filter is not completely satisfied.
     */
    public ConfigBuilder skipResidualFiltering() {
      conf.setBoolean(InputFormatConfig.SKIP_RESIDUAL_FILTERING, true);
      return this;
    }
  }

  public static Table findTable(Configuration conf) {
    String path = conf.get(InputFormatConfig.TABLE_PATH);
    Preconditions.checkArgument(path != null, "Table path should not be null");
    if (path.contains("/")) {
      HadoopTables tables = new HadoopTables(conf);
      return tables.load(path);
    }

    String catalogFuncClass = conf.get(InputFormatConfig.CATALOG);
    if (catalogFuncClass != null) {
      Function<Configuration, Catalog> catalogFunc = (Function<Configuration, Catalog>)
          DynConstructors.builder(Function.class)
                         .impl(catalogFuncClass)
                         .build()
                         .newInstance();
      Catalog catalog = catalogFunc.apply(conf);
      TableIdentifier tableIdentifier = TableIdentifier.parse(path);
      return catalog.loadTable(tableIdentifier);
    } else {
      throw new IllegalArgumentException("No custom catalog specified to load table " + path);
    }
  }

}
