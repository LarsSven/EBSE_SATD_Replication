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

package org.apache.zookeeper.test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.cli.SetQuotaCommand;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.Test;

public class QuorumQuotaTest extends QuorumBase {

    @Test
    public void testQuotaWithQuorum() throws Exception {
        ZooKeeper zk = createClient();
        zk.setData("/", "some".getBytes(), -1);
        zk.create("/a", "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        int i = 0;
        for (i = 0; i < 300; i++) {
            zk.create("/a/" + i, "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        SetQuotaCommand.createQuota(zk, "/a", 1000L, 5000);
        String statPath = Quotas.quotaZookeeper + "/a" + "/" + Quotas.statNode;
        byte[] data = zk.getData(statPath, false, new Stat());
        StatsTrack st = new StatsTrack(new String(data));
        assertTrue(st.getBytes() == 1204L,"bytes are set");
        assertTrue(st.getCount() == 301, "num count is set");
        for (i = 300; i < 600; i++) {
            zk.create("/a/" + i, "some".getBytes(), Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        data = zk.getData(statPath, false, new Stat());
        st = new StatsTrack(new String(data));
        assertTrue(st.getBytes() == 2404L, "bytes are set");
        assertTrue(st.getCount() == 601, "num count is set");
    }

}
