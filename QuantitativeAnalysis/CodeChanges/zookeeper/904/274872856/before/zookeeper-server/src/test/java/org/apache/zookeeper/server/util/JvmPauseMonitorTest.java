/**
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

package org.apache.zookeeper.server.util;

import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JvmPauseMonitorTest {

    @Test(timeout=5000)
    public void testJvmPauseMonitorExceedThreshold() throws InterruptedException {
        final Long sleepTime = 100L;
        final Long warnTH = -1L;
        final Long infoTH = -1L;

        QuorumPeerConfig qpConfig = mock(QuorumPeerConfig.class);
        when(qpConfig.getJvmPauseSleepTimeMs()).thenReturn(sleepTime);
        when(qpConfig.getJvmPauseWarnThresholdMs()).thenReturn(warnTH);
        when(qpConfig.getJvmPauseInfoThresholdMs()).thenReturn(infoTH);

        JvmPauseMonitor pauseMonitor = new JvmPauseMonitor(qpConfig);
        pauseMonitor.serviceStart();

        Assert.assertEquals(sleepTime, Long.valueOf(pauseMonitor.sleepTimeMs));
        Assert.assertEquals(warnTH, Long.valueOf(pauseMonitor.warnThresholdMs));
        Assert.assertEquals(infoTH, Long.valueOf(pauseMonitor.infoThresholdMs));

        while(pauseMonitor.getNumGcInfoThresholdExceeded() == 0 && pauseMonitor.getNumGcWarnThresholdExceeded() == 0) {
            Thread.sleep(200);
        }

        pauseMonitor.serviceStop();
    }
}
