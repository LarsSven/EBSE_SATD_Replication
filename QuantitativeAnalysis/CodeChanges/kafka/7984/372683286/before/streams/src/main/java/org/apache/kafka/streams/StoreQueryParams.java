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
package org.apache.kafka.streams;

import org.apache.kafka.streams.state.QueryableStoreType;

import java.util.Objects;

/**
 * Represents all the query options that a user can provide to state what kind of stores it is expecting. The options would be whether a user would want to enable/disable stale stores* or whether it knows the list of partitions that it specifically wants to fetch. If this information is not provided the default behavior is to fetch the stores for all the partitions available on that instance* for that particular store name.
 * It contains a partition, which for a point queries can be populated from the  KeyQueryMetadata.
 */
public class StoreQueryParams<T> {

    private Integer partition;
    private boolean staleStores;
    private final String storeName;
    private final QueryableStoreType<T> queryableStoreType;

    private StoreQueryParams(final String storeName, final QueryableStoreType<T>  queryableStoreType) {
        this.storeName = storeName;
        this.queryableStoreType = queryableStoreType;
    }

    public static final <T> StoreQueryParams<T> fromNameAndType(final String storeName, final QueryableStoreType<T>  queryableStoreType) {
        return new<T> StoreQueryParams<T>(storeName, queryableStoreType);
    }

    /**
     * Get the partition to be used to fetch list of Queryable store from QueryableStoreProvider.
     * If the function returns null, it would mean that no specific partition has been requested so all the local partitions
     * for the store will be returned.
     *
     * @return Integer partition
     */
    public Integer partition() {
        return partition;
    }

    /**
     * Get the flag staleStores. If true, include standbys and recovering stores along with running stores.
     *
     * @return boolean staleStores
     */
    public boolean staleStoresEnabled() {
        return staleStores;
    }

    /**
     * Get the {@link StoreQueryParams} with stale(standby, restoring) stores added via fetching the stores.
     *
     * @param partition   The specific integer partition to be fetched from the stores list by using {@link StoreQueryParams}.
     *
     * @return String storeName
     */
    public StoreQueryParams<T> withPartition(final Integer partition) {
        this.partition = partition;
        return this;
    }

    /**
     * Get the {@link StoreQueryParams} with stale(standby, restoring) stores added via fetching the stores.
     *
     * @return String storeName
     */
    public StoreQueryParams<T> enableStaleStores() {
        this.staleStores = true;
        return this;
    }

    /**
     * Get the store name for which key is queried by the user.
     *
     * @return String storeName
     */
    public String getStoreName() {
        return storeName;
    }

    /**
     * Get the queryable store type for which key is queried by the user.
     *
     * @return QueryableStoreType queryableStoreType
     */
    public QueryableStoreType<T> getQueryableStoreType() {
        return queryableStoreType;
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof StoreQueryParams)) {
            return false;
        }
        final StoreQueryParams storeQueryParams = (StoreQueryParams) obj;
        return Objects.equals(storeQueryParams.partition, partition)
                && Objects.equals(storeQueryParams.staleStores, staleStores)
                && Objects.equals(storeQueryParams.storeName, storeName)
                && Objects.equals(storeQueryParams.queryableStoreType, queryableStoreType);
    }

    @Override
    public String toString() {
        return "StoreQueryParams {" +
                "partition=" + partition +
                ", staleStores=" + staleStores +
                ", storeName=" + storeName +
                ", queryableStoreType=" + queryableStoreType +
                '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(partition, staleStores, storeName, queryableStoreType);
    }
}