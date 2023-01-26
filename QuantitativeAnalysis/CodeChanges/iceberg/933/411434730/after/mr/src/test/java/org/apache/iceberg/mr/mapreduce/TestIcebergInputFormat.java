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

package org.apache.iceberg.mr.mapreduce;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.RandomGenericData;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.mr.BaseInputFormatTest;
import org.apache.iceberg.mr.InputFormatConfig;
import org.apache.iceberg.mr.TestHelpers.Row;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.apache.iceberg.mr.TestHelpers.writeFile;

@RunWith(Parameterized.class)
public class TestIcebergInputFormat extends BaseInputFormatTest {

  public TestIcebergInputFormat(String format) {
    this.fileFormat = FileFormat.valueOf(format.toUpperCase(Locale.ENGLISH));
  }

  @Override
  protected void runAndValidate(File tableLocation, List<Record> expectedRecords) throws IOException {
    Job job = Job.getInstance(conf);
    InputFormatConfig.ConfigBuilder configBuilder = IcebergInputFormat.configure(job);
    configBuilder.readFrom(tableLocation.toString());
    validate(job, expectedRecords);
  }

  //TODO: try move as many methods below into base class (once functionality is implemented in
  //      mapred InputFormat)
  @Test
  public void testFilterExp() throws Exception {
    File location = temp.newFolder(fileFormat.name());
    Assert.assertTrue(location.delete());
    Table table = tables.create(SCHEMA, SPEC,
                                ImmutableMap.of(TableProperties.DEFAULT_FILE_FORMAT, fileFormat.name()),
                                location.toString());
    List<Record> expectedRecords = RandomGenericData.generate(table.schema(), 2, 0L);
    expectedRecords.get(0).set(2, "2020-03-20");
    expectedRecords.get(1).set(2, "2020-03-20");
    DataFile dataFile1 = writeFile(temp.newFile(), table, Row.of("2020-03-20", 0), fileFormat, expectedRecords);
    DataFile dataFile2 = writeFile(temp.newFile(), table, Row.of("2020-03-21", 0), fileFormat,
                                   RandomGenericData.generate(table.schema(), 2, 0L));
    table.newAppend()
         .appendFile(dataFile1)
         .appendFile(dataFile2)
         .commit();
    Job job = Job.getInstance(conf);
    InputFormatConfig.ConfigBuilder configBuilder = IcebergInputFormat.configure(job);
    configBuilder.readFrom(location.toString())
                 .filter(Expressions.equal("date", "2020-03-20"));
    validate(job, expectedRecords);
  }

  @Test
  public void testResiduals() throws Exception {
    File location = temp.newFolder(fileFormat.name());
    Assert.assertTrue(location.delete());
    Table table = tables.create(SCHEMA, SPEC,
                                ImmutableMap.of(TableProperties.DEFAULT_FILE_FORMAT, fileFormat.name()),
                                location.toString());
    List<Record> writeRecords = RandomGenericData.generate(table.schema(), 2, 0L);
    writeRecords.get(0).set(1, 123L);
    writeRecords.get(0).set(2, "2020-03-20");
    writeRecords.get(1).set(1, 456L);
    writeRecords.get(1).set(2, "2020-03-20");

    List<Record> expectedRecords = new ArrayList<>();
    expectedRecords.add(writeRecords.get(0));

    DataFile dataFile1 = writeFile(temp.newFile(), table, Row.of("2020-03-20", 0), fileFormat, writeRecords);
    DataFile dataFile2 = writeFile(temp.newFile(), table, Row.of("2020-03-21", 0), fileFormat,
        RandomGenericData.generate(table.schema(), 2, 0L));
    table.newAppend()
         .appendFile(dataFile1)
         .appendFile(dataFile2)
         .commit();
    Job job = Job.getInstance(conf);
    InputFormatConfig.ConfigBuilder configBuilder = IcebergInputFormat.configure(job);
    configBuilder.readFrom(location.toString())
        .filter(Expressions.and(
            Expressions.equal("date", "2020-03-20"),
            Expressions.equal("id", 123)));
    validate(job, expectedRecords);

    // skip residual filtering
    job = Job.getInstance(conf);
    configBuilder = IcebergInputFormat.configure(job);
    configBuilder.skipResidualFiltering().readFrom(location.toString())
        .filter(Expressions.and(
            Expressions.equal("date", "2020-03-20"),
            Expressions.equal("id", 123)));
    validate(job, writeRecords);
  }

  @Test
  public void testProjection() throws Exception {
    File location = temp.newFolder(fileFormat.name());
    Assert.assertTrue(location.delete());
    Schema projectedSchema = TypeUtil.select(SCHEMA, ImmutableSet.of(1));
    Table table = tables.create(SCHEMA, SPEC,
                                ImmutableMap.of(TableProperties.DEFAULT_FILE_FORMAT, fileFormat.name()),
                                location.toString());
    List<Record> inputRecords = RandomGenericData.generate(table.schema(), 1, 0L);
    DataFile dataFile = writeFile(temp.newFile(), table, Row.of("2020-03-20", 0), fileFormat, inputRecords);
    table.newAppend()
         .appendFile(dataFile)
         .commit();

    Job job = Job.getInstance(conf);
    InputFormatConfig.ConfigBuilder configBuilder = IcebergInputFormat.configure(job);
    configBuilder
        .readFrom(location.toString())
        .project(projectedSchema);
    List<Record> outputRecords = readRecords(job.getConfiguration());
    Assert.assertEquals(inputRecords.size(), outputRecords.size());
    Assert.assertEquals(projectedSchema.asStruct(), outputRecords.get(0).struct());
  }

  private static final Schema LOG_SCHEMA = new Schema(
      Types.NestedField.optional(1, "id", Types.IntegerType.get()),
      Types.NestedField.optional(2, "date", Types.StringType.get()),
      Types.NestedField.optional(3, "level", Types.StringType.get()),
      Types.NestedField.optional(4, "message", Types.StringType.get())
  );

  private static final PartitionSpec IDENTITY_PARTITION_SPEC =
      PartitionSpec.builderFor(LOG_SCHEMA).identity("date").identity("level").build();

  @Test
  public void testIdentityPartitionProjections() throws Exception {
    File location = temp.newFolder(fileFormat.name());
    Assert.assertTrue(location.delete());
    Table table = tables.create(LOG_SCHEMA, IDENTITY_PARTITION_SPEC,
                                ImmutableMap.of(TableProperties.DEFAULT_FILE_FORMAT, fileFormat.name()),
                                location.toString());

    List<Record> inputRecords = RandomGenericData.generate(LOG_SCHEMA, 10, 0);
    Integer idx = 0;
    AppendFiles append = table.newAppend();
    for (Record record : inputRecords) {
      record.set(1, "2020-03-2" + idx);
      record.set(2, idx.toString());
      append.appendFile(writeFile(temp.newFile(), table, Row.of("2020-03-2" + idx, idx.toString()),
                        fileFormat, ImmutableList.of(record)));
      idx += 1;
    }
    append.commit();

    // individual fields
    validateIdentityPartitionProjections(location.toString(), withColumns("date"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("level"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("message"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("id"), inputRecords);
    // field pairs
    validateIdentityPartitionProjections(location.toString(), withColumns("date", "message"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("level", "message"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("date", "level"), inputRecords);
    // out-of-order pairs
    validateIdentityPartitionProjections(location.toString(), withColumns("message", "date"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("message", "level"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("level", "date"), inputRecords);
    // full projection
    validateIdentityPartitionProjections(location.toString(), LOG_SCHEMA, inputRecords);
    // out-of-order triplets
    validateIdentityPartitionProjections(location.toString(), withColumns("date", "level", "message"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("level", "date", "message"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("date", "message", "level"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("level", "message", "date"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("message", "date", "level"), inputRecords);
    validateIdentityPartitionProjections(location.toString(), withColumns("message", "level", "date"), inputRecords);
  }

  private static Schema withColumns(String... names) {
    Map<String, Integer> indexByName = TypeUtil.indexByName(LOG_SCHEMA.asStruct());
    Set<Integer> projectedIds = Sets.newHashSet();
    for (String name : names) {
      projectedIds.add(indexByName.get(name));
    }
    return TypeUtil.select(LOG_SCHEMA, projectedIds);
  }

  private void validateIdentityPartitionProjections(
      String tablePath, Schema projectedSchema, List<Record> inputRecords) throws Exception {
    Job job = Job.getInstance(conf);
    InputFormatConfig.ConfigBuilder configBuilder = IcebergInputFormat.configure(job);
    configBuilder
        .readFrom(tablePath)
        .project(projectedSchema);
    List<Record> actualRecords = readRecords(job.getConfiguration());

    Set<String> fieldNames = TypeUtil.indexByName(projectedSchema.asStruct()).keySet();
    for (int pos = 0; pos < inputRecords.size(); pos++) {
      Record inputRecord = inputRecords.get(pos);
      Record actualRecord = actualRecords.get(pos);
      Assert.assertEquals("Projected schema should match", projectedSchema.asStruct(), actualRecord.struct());
      for (String name : fieldNames) {
        Assert.assertEquals(
            "Projected field " + name + " should match", inputRecord.getField(name), actualRecord.getField(name));
      }
    }
  }

  @Test
  public void testSnapshotReads() throws Exception {
    File location = temp.newFolder(fileFormat.name());
    Assert.assertTrue(location.delete());
    Table table = tables.create(SCHEMA, PartitionSpec.unpartitioned(),
                                ImmutableMap.of(TableProperties.DEFAULT_FILE_FORMAT, fileFormat.name()),
                                location.toString());
    List<Record> expectedRecords = RandomGenericData.generate(table.schema(), 1, 0L);
    table.newAppend()
         .appendFile(writeFile(temp.newFile(), table, null, fileFormat, expectedRecords))
         .commit();
    long snapshotId = table.currentSnapshot().snapshotId();
    table.newAppend()
         .appendFile(writeFile(temp.newFile(), table, null, fileFormat,
                     RandomGenericData.generate(table.schema(), 1, 0L)))
         .commit();

    Job job = Job.getInstance(conf);
    InputFormatConfig.ConfigBuilder configBuilder = IcebergInputFormat.configure(job);
    configBuilder
        .readFrom(location.toString())
        .snapshotId(snapshotId);

    validate(job, expectedRecords);
  }

  @Test
  public void testLocality() throws Exception {
    File location = temp.newFolder(fileFormat.name());
    Assert.assertTrue(location.delete());
    Table table = tables.create(SCHEMA, PartitionSpec.unpartitioned(),
                                ImmutableMap.of(TableProperties.DEFAULT_FILE_FORMAT, fileFormat.name()),
                                location.toString());
    List<Record> expectedRecords = RandomGenericData.generate(table.schema(), 1, 0L);
    table.newAppend()
         .appendFile(writeFile(temp.newFile(), table, null, fileFormat, expectedRecords))
         .commit();
    Job job = Job.getInstance(conf);
    InputFormatConfig.ConfigBuilder configBuilder = IcebergInputFormat.configure(job);
    configBuilder.readFrom(location.toString());

    for (InputSplit split : splits(job.getConfiguration())) {
      Assert.assertArrayEquals(IcebergInputFormat.IcebergSplit.ANYWHERE, split.getLocations());
    }

    configBuilder.preferLocality();
    for (InputSplit split : splits(job.getConfiguration())) {
      Assert.assertArrayEquals(new String[]{"localhost"}, split.getLocations());
    }
  }

  public static class HadoopCatalogFunc implements Function<Configuration, Catalog> {
    @Override
    public Catalog apply(Configuration conf) {
      return new HadoopCatalog(conf, conf.get("warehouse.location"));
    }
  }

  @Test
  public void testCustomCatalog() throws Exception {
    conf = new Configuration();
    conf.set("warehouse.location", temp.newFolder("hadoop_catalog").getAbsolutePath());

    Catalog catalog = new HadoopCatalogFunc().apply(conf);
    TableIdentifier tableIdentifier = TableIdentifier.of("db", "t");
    Table table = catalog.createTable(tableIdentifier, SCHEMA, SPEC,
                                      ImmutableMap.of(TableProperties.DEFAULT_FILE_FORMAT, fileFormat.name()));
    List<Record> expectedRecords = RandomGenericData.generate(table.schema(), 1, 0L);
    expectedRecords.get(0).set(2, "2020-03-20");
    DataFile dataFile = writeFile(temp.newFile(), table, Row.of("2020-03-20", 0), fileFormat, expectedRecords);
    table.newAppend()
         .appendFile(dataFile)
         .commit();

    Job job = Job.getInstance(conf);
    InputFormatConfig.ConfigBuilder configBuilder = IcebergInputFormat.configure(job);
    configBuilder
        .catalogFunc(HadoopCatalogFunc.class)
        .readFrom(tableIdentifier.toString());
    validate(job, expectedRecords);
  }

  private static void validate(Job job, List<Record> expectedRecords) {
    List<Record> actualRecords = readRecords(job.getConfiguration());
    Assert.assertEquals(expectedRecords, actualRecords);
  }

  private static <T> List<InputSplit> splits(Configuration conf) {
    TaskAttemptContext context = new TaskAttemptContextImpl(conf, new TaskAttemptID());
    IcebergInputFormat<T> icebergInputFormat = new IcebergInputFormat<>();
    return icebergInputFormat.getSplits(context);
  }

  private static <T> List<T> readRecords(Configuration conf) {
    TaskAttemptContext context = new TaskAttemptContextImpl(conf, new TaskAttemptID());
    IcebergInputFormat<T> icebergInputFormat = new IcebergInputFormat<>();
    List<InputSplit> splits = icebergInputFormat.getSplits(context);
    return
        FluentIterable
            .from(splits)
            .transformAndConcat(split -> readRecords(icebergInputFormat, split, context))
            .toList();
  }

  private static <T> Iterable<T> readRecords(
      IcebergInputFormat<T> inputFormat, InputSplit split, TaskAttemptContext context) {
    RecordReader<Void, T> recordReader = inputFormat.createRecordReader(split, context);
    List<T> records = new ArrayList<>();
    try {
      recordReader.initialize(split, context);
      while (recordReader.nextKeyValue()) {
        records.add(recordReader.getCurrentValue());
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return records;
  }

}
