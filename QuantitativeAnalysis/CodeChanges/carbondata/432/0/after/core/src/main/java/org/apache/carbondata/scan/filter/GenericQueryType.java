/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.carbondata.scan.filter;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.carbondata.core.carbon.datastore.chunk.DimensionColumnDataChunk;
import org.apache.carbondata.scan.processor.BlocksChunkHolder;

public interface GenericQueryType {

  String getName();

  void setName(String name);

  String getParentname();

  void setParentname(String parentname);

  void addChildren(GenericQueryType children);

  int getColsCount();

  void parseBlocksAndReturnComplexColumnByteArray(DimensionColumnDataChunk[] dimensionDataChunks,
      int rowNumber, DataOutputStream dataOutputStream) throws IOException;

  void fillRequiredBlockData(BlocksChunkHolder blockChunkHolder) throws IOException;

  Object getDataBasedOnDataTypeFromSurrogates(ByteBuffer surrogateData);
}
