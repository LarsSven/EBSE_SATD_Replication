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

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.admin.ZooKeeperAdmin;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReconfigMisconfigTest extends ZKTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(ReconfigMisconfigTest.class);
    private QuorumUtil qu;
    private ZooKeeperAdmin zkAdmin;
    private static String errorMsg = "Reconfig should fail without configuring the super "
                                     + "user's password on server side first.";

    @BeforeEach
    public void setup() throws InterruptedException {
        QuorumPeerConfig.setReconfigEnabled(true);
        // Get a three server quorum.
        qu = new QuorumUtil(1);
        qu.disableJMXTest = true;
        try {
            qu.startAll();
        } catch (IOException e) {
            fail("Fail to start quorum servers.");
        }

        instantiateZKAdmin();
    }

    @AfterEach
    public void tearDown() throws Exception {
        try {
            if (qu != null) {
                qu.tearDown();
            }
            if (zkAdmin != null) {
                zkAdmin.close();
            }
        } catch (Exception e) {
            // Ignore.
        }
    }

    @Test
    public void testReconfigFailWithoutSuperuserPasswordConfiguredOnServer() throws InterruptedException {
        assertTimeout(Duration.ofMillis(10000L), () -> {
            // This tests the case where ZK ensemble does not have the super user's password configured.
            // Reconfig should fail as the super user has to be explicitly configured via
            // zookeeper.DigestAuthenticationProvider.superDigest.
            try {
                reconfigPort();
                fail(errorMsg);
            } catch (KeeperException e) {
                assertTrue(e.code() == KeeperException.Code.NOAUTH);
            }

            try {
                zkAdmin.addAuthInfo("digest", "super:".getBytes());
                reconfigPort();
                fail(errorMsg);
            } catch (KeeperException e) {
                assertTrue(e.code() == KeeperException.Code.NOAUTH);
            }
        });
    }

    private void instantiateZKAdmin() throws InterruptedException {
        String cnxString;
        ClientBase.CountdownWatcher watcher = new ClientBase.CountdownWatcher();
        try {
            cnxString = "127.0.0.1:" + qu.getPeer(1).peer.getClientPort();
            zkAdmin = new ZooKeeperAdmin(cnxString, ClientBase.CONNECTION_TIMEOUT, watcher);
        } catch (IOException e) {
            fail("Fail to create ZooKeeperAdmin handle.");
            return;
        }

        try {
            watcher.waitForConnected(ClientBase.CONNECTION_TIMEOUT);
        } catch (InterruptedException | TimeoutException e) {
            fail("ZooKeeper admin client can not connect to " + cnxString);
        }
    }

    private boolean reconfigPort() throws KeeperException, InterruptedException {
        List<String> joiningServers = new ArrayList<String>();
        int leaderId = 1;
        while (qu.getPeer(leaderId).peer.leader == null) {
            leaderId++;
        }
        int followerId = leaderId == 1 ? 2 : 1;
        joiningServers.add("server." + followerId
                           + "=localhost:"
                           + qu.getPeer(followerId).peer.getQuorumAddress().getAllPorts().get(0) /*quorum port*/
                           + ":"
                           + qu.getPeer(followerId).peer.getElectionAddress().getAllPorts().get(0) /*election port*/
                           + ":participant;localhost:"
                           + PortAssignment.unique()/* new client port */);
        zkAdmin.reconfigure(joiningServers, null, null, -1, new Stat());
        return true;
    }

}

