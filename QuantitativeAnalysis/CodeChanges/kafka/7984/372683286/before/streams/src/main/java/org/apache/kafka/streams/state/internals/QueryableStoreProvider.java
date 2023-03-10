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
package org.apache.kafka.streams.state.internals;

import org.apache.kafka.streams.StoreQueryParams;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.state.QueryableStoreType;

import java.util.ArrayList;
import java.util.List;

/**
 * A wrapper over all of the {@link StateStoreProvider}s in a Topology
 */
public class QueryableStoreProvider {

    private final List<StreamThreadStateStoreProvider> storeProviders;
    private final GlobalStateStoreProvider globalStoreProvider;

    public QueryableStoreProvider(final List<StreamThreadStateStoreProvider> storeProviders,
                                  final GlobalStateStoreProvider globalStateStoreProvider) {
        this.storeProviders = new ArrayList<>(storeProviders);
        this.globalStoreProvider = globalStateStoreProvider;
    }

    /**
     * Get a composite object wrapping the instances of the {@link StateStore} with the provided
     * storeName and {@link QueryableStoreType}
     *
     * @param storeQueryParams       if stateStoresEnabled is used i.e. staleStoresEnabled is true, include standbys and recovering stores;
     *                                        if stateStoresDisabled i.e. staleStoresEnabled is false, only include running actives;
     *                                        if partition is null then it fetches all local partitions on the instance;
     *                                        if partition is set then it fetches a specific partition.
     * @param <T>                The expected type of the returned store
     * @return A composite object that wraps the store instances.
     */
    public <T> T getStore(final StoreQueryParams<T> storeQueryParams) {
        final String storeName = storeQueryParams.getStoreName();
        final QueryableStoreType<T> queryableStoreType = storeQueryParams.getQueryableStoreType();
        final List<T> globalStore = globalStoreProvider.stores(storeName, queryableStoreType);
        if (!globalStore.isEmpty()) {
            return queryableStoreType.create(globalStoreProvider, storeName);
        }
        final List<T> allStores = new ArrayList<>();
        for (final StreamThreadStateStoreProvider storeProvider : storeProviders) {
            allStores.addAll(storeProvider.stores(storeQueryParams));
        }
        if (allStores.isEmpty()) {
            throw new InvalidStateStoreException("The state store, " + storeName + ", may have migrated to another instance.");
        }
        return queryableStoreType.create(
            new WrappingStoreProvider(storeProviders, storeQueryParams),
            storeName
        );
    }
}
