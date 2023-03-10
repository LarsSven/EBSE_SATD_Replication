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

package org.apache.zookeeper.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.test.ClientBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CreateContainerTest extends ClientBase {

    private ZooKeeper zk;
    private Semaphore completedContainerDeletions;

    @BeforeEach
    @Override
    public void setUp() throws Exception {
        super.setUp();
        zk = createClient();

        completedContainerDeletions = new Semaphore(0);
        ZKDatabase testDatabase = new ZKDatabase(serverFactory.zkServer.getZKDatabase().snapLog) {
            @Override
            public void addCommittedProposal(Request request) {
                super.addCommittedProposal(request);
                if (request.type == ZooDefs.OpCode.deleteContainer) {
                    completedContainerDeletions.release();
                }
            }
        };
        serverFactory.zkServer.setZKDatabase(testDatabase);
    }

    @AfterEach
    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        zk.close();
    }

    @Test
    public void testCreate() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            createNoStatVerifyResult("/foo");
            createNoStatVerifyResult("/foo/child");
        });
    }

    @Test
    public void testCreateWithStat() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            Stat stat = createWithStatVerifyResult("/foo");
            Stat childStat = createWithStatVerifyResult("/foo/child");
            // Don't expect to get the same stats for different creates.
            assertFalse(stat.equals(childStat));
        });
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void testCreateWithNullStat() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            final String name = "/foo";
            assertNull(zk.exists(name, false));

            Stat stat = null;
            // If a null Stat object is passed the create should still
            // succeed, but no Stat info will be returned.
            zk.create(name, name.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER, stat);
            assertNull(stat);
            assertNotNull(zk.exists(name, false));
        });
    }

    @Test
    public void testSimpleDeletion() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.delete("/foo/bar", -1);  // should cause "/foo" to get deleted when checkContainers() is called

            ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100);
            containerManager.checkContainers();

            assertTrue(completedContainerDeletions.tryAcquire(1, TimeUnit.SECONDS));
            assertNull(zk.exists("/foo", false), "Container should have been deleted");
        });
    }

    @Test
    public void testMultiWithContainerSimple() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            Op createContainer = Op.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            zk.multi(Collections.singletonList(createContainer));

            DataTree dataTree = serverFactory.getZooKeeperServer().getZKDatabase().getDataTree();
            assertEquals(dataTree.getContainers().size(), 1);
        });
    }

    @Test
    public void testMultiWithContainer() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            Op createContainer = Op.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            Op createChild = Op.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
            zk.multi(Arrays.asList(createContainer, createChild));

            DataTree dataTree = serverFactory.getZooKeeperServer().getZKDatabase().getDataTree();
            assertEquals(dataTree.getContainers().size(), 1);

            zk.delete("/foo/bar", -1);  // should cause "/foo" to get deleted when checkContainers() is called

            ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100);
            containerManager.checkContainers();

            assertTrue(completedContainerDeletions.tryAcquire(1, TimeUnit.SECONDS));
            assertNull(zk.exists("/foo", false), "Container should have been deleted");

            createContainer = Op.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            createChild = Op.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            Op deleteChild = Op.delete("/foo/bar", -1);
            zk.multi(Arrays.asList(createContainer, createChild, deleteChild));

            containerManager.checkContainers();

            assertTrue(completedContainerDeletions.tryAcquire(1, TimeUnit.SECONDS));
            assertNull(zk.exists("/foo", false), "Container should have been deleted");
        });
    }

    @Test
    public void testSimpleDeletionAsync() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            final CountDownLatch latch = new CountDownLatch(1);
            AsyncCallback.Create2Callback cb = (rc, path, ctx, name, stat) -> {
                assertEquals(ctx, "context");
                latch.countDown();
            };
            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER, cb,
                "context");
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.delete("/foo/bar", -1);  // should cause "/foo" to get deleted when checkContainers() is called

            ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100);
            containerManager.checkContainers();

            assertTrue(completedContainerDeletions.tryAcquire(1, TimeUnit.SECONDS));
            assertNull(zk.exists("/foo", false), "Container should have been deleted");
        });
    }

    @Test
    public void testCascadingDeletion() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            zk.create("/foo/bar/one", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            zk.delete("/foo/bar/one", -1);  // should cause "/foo/bar" and "/foo" to get deleted when checkContainers() is called

            ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100);
            containerManager.checkContainers();
            assertTrue(completedContainerDeletions.tryAcquire(1, TimeUnit.SECONDS));
            containerManager.checkContainers();
            assertTrue(completedContainerDeletions.tryAcquire(1, TimeUnit.SECONDS));

            assertNull(zk.exists("/foo/bar", false), "Container should have been deleted");
            assertNull(zk.exists("/foo", false), "Container should have been deleted");
        });
    }

    @Test
    public void testFalseEmpty() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            zk.create("/foo/bar", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100) {
                @Override protected Collection<String> getCandidates() {
                    return Collections.singletonList("/foo");
                }
            };
            containerManager.checkContainers();

            assertTrue(completedContainerDeletions.tryAcquire(1, TimeUnit.SECONDS));
            assertNotNull(zk.exists("/foo", false), "Container should have not been deleted");
        });
    }

    @Test
    public void testMaxPerMinute() throws InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
            RequestProcessor processor = new RequestProcessor() {
                @Override public void processRequest(Request request) {
                    queue.add(new String(request.request.array()));
                }

                @Override public void shutdown() {
                }
            };
            final ContainerManager containerManager =
                new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(), processor,
                    1, 2) {
                    @Override protected long getMinIntervalMs() {
                        return 1000;
                    }

                    @Override protected Collection<String> getCandidates() {
                        return Arrays.asList("/one", "/two", "/three", "/four");
                    }
                };
            Executors.newSingleThreadExecutor().submit(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    containerManager.checkContainers();
                    return null;
                }
            });
            assertEquals(queue.poll(5, TimeUnit.SECONDS), "/one");
            assertEquals(queue.poll(5, TimeUnit.SECONDS), "/two");
            assertEquals(queue.size(), 0);
            Thread.sleep(500);
            assertEquals(queue.size(), 0);

            assertEquals(queue.poll(5, TimeUnit.SECONDS), "/three");
            assertEquals(queue.poll(5, TimeUnit.SECONDS), "/four");
        });
    }

    @Test
    public void testMaxNeverUsedInterval() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            AtomicLong elapsed = new AtomicLong(0);
            AtomicInteger deletesQty = new AtomicInteger(0);
            ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100, 1000) {
                @Override protected void postDeleteRequest(Request request) throws RequestProcessor.RequestProcessorException {
                    deletesQty.incrementAndGet();
                    super.postDeleteRequest(request);
                }

                @Override protected long getElapsed(DataNode node) {
                    return elapsed.get();
                }
            };
            containerManager.checkContainers(); // elapsed time will appear to be 0 - container will not get deleted
            assertEquals(deletesQty.get(), 0);
            assertNotNull(zk.exists("/foo", false), "Container should not have been deleted");

            elapsed.set(10000);
            containerManager.checkContainers(); // elapsed time will appear to be 10000 - container should get deleted
            assertTrue(completedContainerDeletions.tryAcquire(1, TimeUnit.SECONDS));
            assertNull(zk.exists("/foo", false), "Container should have been deleted");
        });
    }

    @Test
    public void testZeroMaxNeverUsedInterval() throws KeeperException, InterruptedException {
        assertTimeout(Duration.ofMillis(30000L), () -> {
            zk.create("/foo", new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
            AtomicInteger deletesQty = new AtomicInteger(0);
            ContainerManager containerManager = new ContainerManager(serverFactory.getZooKeeperServer().getZKDatabase(),
                serverFactory.getZooKeeperServer().firstProcessor, 1, 100, 0) {
                @Override protected void postDeleteRequest(Request request) throws RequestProcessor.RequestProcessorException {
                    deletesQty.incrementAndGet();
                    super.postDeleteRequest(request);
                }

                @Override protected long getElapsed(DataNode node) {
                    return 10000;   // some number greater than 0
                }
            };
            containerManager.checkContainers(); // elapsed time will appear to be 0 - container will not get deleted
            assertEquals(deletesQty.get(), 0);
            assertNotNull(zk.exists("/foo", false), "Container should not have been deleted");
        });
    }

    private void createNoStatVerifyResult(String newName) throws KeeperException, InterruptedException {
        assertNull(zk.exists(newName, false), "Node existed before created");
        zk.create(newName, newName.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER);
        assertNotNull(zk.exists(newName, false), "Node was not created as expected");
    }

    private Stat createWithStatVerifyResult(String newName) throws KeeperException, InterruptedException {
        assertNull(zk.exists(newName, false), "Node existed before created");
        Stat stat = new Stat();
        zk.create(newName, newName.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.CONTAINER, stat);
        validateCreateStat(stat, newName);

        Stat referenceStat = zk.exists(newName, false);
        assertNotNull(referenceStat, "Node was not created as expected");
        assertEquals(referenceStat, stat);

        return stat;
    }

    private void validateCreateStat(Stat stat, String name) {
        assertEquals(stat.getCzxid(), stat.getMzxid());
        assertEquals(stat.getCzxid(), stat.getPzxid());
        assertEquals(stat.getCtime(), stat.getMtime());
        assertEquals(0, stat.getCversion());
        assertEquals(0, stat.getVersion());
        assertEquals(0, stat.getAversion());
        assertEquals(0, stat.getEphemeralOwner());
        assertEquals(name.length(), stat.getDataLength());
        assertEquals(0, stat.getNumChildren());
    }

}
