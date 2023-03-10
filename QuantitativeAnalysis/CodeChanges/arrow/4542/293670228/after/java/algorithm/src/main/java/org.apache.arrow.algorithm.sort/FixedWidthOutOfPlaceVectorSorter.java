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

package org.apache.arrow.algorithm.sort;

import java.util.stream.IntStream;

import org.apache.arrow.vector.BaseFixedWidthVector;
import org.apache.arrow.vector.BitVectorHelper;

import io.netty.buffer.ArrowBuf;
import io.netty.util.internal.PlatformDependent;

/**
 * Default out-of-place sorter for fixed-width vectors.
 * It is an out-of-place sort, with time complexity O(n*log(n)).
 * @param <V> vector type.
 */
public class FixedWidthOutOfPlaceVectorSorter<V extends BaseFixedWidthVector> implements VectorSorter<V> {

  private final IndexSorter<V> indexSorter = new IndexSorter<>();

  @Override
  public V sort(V srcVector, VectorValueComparator<V> comparator) {
    comparator.attachVector(srcVector);

    int valueWidth = comparator.getValueWidth();

    // create output vector
    V dstVector = comparator.newVector(srcVector.getAllocator());
    dstVector.allocateNew(srcVector.getValueCount());

    // buffers referenced in the sort
    ArrowBuf srcValueBuffer = srcVector.getDataBuffer();
    ArrowBuf dstValidityBuffer = dstVector.getValidityBuffer();
    ArrowBuf dstValueBuffer = dstVector.getDataBuffer();

    // sort value indices
    int[] sortedIndices = IntStream.range(0, srcVector.getValueCount()).toArray();
    indexSorter.sort(sortedIndices, comparator);

    // copy sorted values to the output vector
    int dstIndex = 0;
    for (int srcIndex : sortedIndices) {
      if (srcVector.isNull(srcIndex)) {
        BitVectorHelper.setValidityBit(dstValidityBuffer, dstIndex, 0);
      } else {
        BitVectorHelper.setValidityBit(dstValidityBuffer, dstIndex, 1);
        PlatformDependent.copyMemory(
                srcValueBuffer.memoryAddress() + srcIndex * valueWidth,
                dstValueBuffer.memoryAddress() + dstIndex * valueWidth,
                valueWidth);
      }
      dstIndex += 1;
    }

    dstVector.setValueCount(srcVector.getValueCount());
    return dstVector;
  }

  @Override
  public boolean isInPlace() {
    return false;
  }
}
