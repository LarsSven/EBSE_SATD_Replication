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

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Files;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.avro.DataWriter;
import org.apache.iceberg.data.orc.GenericOrcWriter;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.orc.ORC;
import org.apache.iceberg.parquet.Parquet;

/**
 *
 */
public class TestHelpers {

  private TestHelpers() {}

  /**
   * Implements {@link StructLike#get} for passing data in tests.
   */
  public static class Row implements StructLike {
    public static Row of(Object... values) {
      return new Row(values);
    }

    private final Object[] values;

    private Row(Object... values) {
      this.values = values;
    }

    @Override
    public int size() {
      return values.length;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(int pos, Class<T> javaClass) {
      return javaClass.cast(values[pos]);
    }

    @Override
    public <T> void set(int pos, T value) {
      throw new UnsupportedOperationException("Setting values is not supported");
    }
  }

  public static DataFile writeFile(File targetFile,
      Table table, StructLike partitionData, FileFormat fileFormat, List<Record> records) throws IOException {
    if (targetFile.exists()) {
      if (!targetFile.delete()) {
        throw new IOException("Unable to delete " + targetFile.getAbsolutePath());
      }
    }
    FileAppender<Record> appender;
    switch (fileFormat) {
      case AVRO:
        appender = Avro.write(Files.localOutput(targetFile))
            .schema(table.schema())
            .createWriterFunc(DataWriter::create)
            .named(fileFormat.name())
            .build();
        break;
      case PARQUET:
        appender = Parquet.write(Files.localOutput(targetFile))
            .schema(table.schema())
            .createWriterFunc(GenericParquetWriter::buildWriter)
            .named(fileFormat.name())
            .build();
        break;
      case ORC:
        appender = ORC.write(Files.localOutput(targetFile))
            .schema(table.schema())
            .createWriterFunc(GenericOrcWriter::buildWriter)
            .build();
        break;
      default:
        throw new UnsupportedOperationException("Cannot write format: " + fileFormat);
    }

    try {
      appender.addAll(records);
    } finally {
      appender.close();
    }

    DataFiles.Builder builder = DataFiles.builder(table.spec())
        .withPath(targetFile.toString())
        .withFormat(fileFormat)
        .withFileSizeInBytes(targetFile.length())
        .withMetrics(appender.metrics());
    if (partitionData != null) {
      builder.withPartition(partitionData);
    }
    return builder.build();
  }

}