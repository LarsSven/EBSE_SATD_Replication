/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.processor.internals;


import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.AbstractNotifyingBatchingRestoreCallback;
import org.apache.kafka.streams.processor.BatchingStateRestoreCallback;
import org.apache.kafka.streams.processor.StateRestoreCallback;
import org.apache.kafka.streams.processor.StateRestoreListener;

import java.util.Collection;

class CompositeRestoreListener implements BatchingStateRestoreCallback, StateRestoreListener {

    private static final NoOpStateRestoreListener NO_OP_STATE_RESTORE_LISTENER = new NoOpStateRestoreListener();
    private final BatchingStateRestoreCallback internalBatchingRestoreCallback;
    private final StateRestoreListener storeRestoreListener;
    private StateRestoreListener globalRestoreListener = NO_OP_STATE_RESTORE_LISTENER;

    CompositeRestoreListener(final StateRestoreCallback stateRestoreCallback) {

        if (stateRestoreCallback instanceof StateRestoreListener) {
            storeRestoreListener = (StateRestoreListener) stateRestoreCallback;
        } else {
            storeRestoreListener = new NoOpStateRestoreListener();
        }

        internalBatchingRestoreCallback =
            (BatchingStateRestoreCallback) ((stateRestoreCallback instanceof BatchingStateRestoreCallback)
                                            ? stateRestoreCallback
                                            : new WrappedBatchingStateRestoreCallback(stateRestoreCallback));
    }

    @Override
    public void onRestoreStart(final TopicPartition topicPartition,
                               final String storeName,
                               final long startingOffset,
                               final long endingOffset) {
        globalRestoreListener.onRestoreStart(topicPartition, storeName, startingOffset, endingOffset);
        storeRestoreListener.onRestoreStart(topicPartition, storeName, startingOffset, endingOffset);
    }

    @Override
    public void onBatchRestored(final TopicPartition topicPartition,
                                final String storeName,
                                final long batchEndOffset,
                                final long numRestored) {
        globalRestoreListener.onBatchRestored(topicPartition, storeName, batchEndOffset, numRestored);
        storeRestoreListener.onBatchRestored(topicPartition, storeName, batchEndOffset, numRestored);
    }

    @Override
    public void onRestoreEnd(final TopicPartition topicPartition,
                             final String storeName,
                             final long totalRestored) {
        globalRestoreListener.onRestoreEnd(topicPartition, storeName, totalRestored);
        storeRestoreListener.onRestoreEnd(topicPartition, storeName, totalRestored);

    }

    @Override
    public void restoreAll(final Collection<KeyValue<byte[], byte[]>> records) {
        internalBatchingRestoreCallback.restoreAll(records);
    }

    void setGlobalRestoreListener(final StateRestoreListener globalRestoreListener) {
        if (globalRestoreListener != null) {
            this.globalRestoreListener = globalRestoreListener;
        }
    }

    @Override
    public void restore(final byte[] key,
                        final byte[] value) {
        throw new UnsupportedOperationException("Single restore functionality shouldn't be called directly but "
                                                + "through the delegated StateRestoreCallback instance");
    }


    private static final class NoOpStateRestoreListener extends AbstractNotifyingBatchingRestoreCallback  {

        @Override
        public void restoreAll(final Collection<KeyValue<byte[], byte[]>> records) {

        }
    }
}
