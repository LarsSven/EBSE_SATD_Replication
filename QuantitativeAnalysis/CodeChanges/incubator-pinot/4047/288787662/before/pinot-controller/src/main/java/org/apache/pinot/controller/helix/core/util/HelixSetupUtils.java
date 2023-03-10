/**
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
package org.apache.pinot.controller.helix.core.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.helix.AccessOption;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyPathConfig;
import org.apache.helix.PropertyType;
import org.apache.helix.ZNRecord;
import org.apache.helix.controller.HelixControllerMain;
import org.apache.helix.controller.rebalancer.strategy.CrushEdRebalanceStrategy;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.manager.zk.ZKHelixManager;
import org.apache.helix.manager.zk.ZNRecordSerializer;
import org.apache.helix.manager.zk.ZkBaseDataAccessor;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.messaging.handling.HelixTaskExecutor;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.HelixConfigScope.ConfigScopeProperty;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.MasterSlaveSMD;
import org.apache.helix.model.Message.MessageType;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.pinot.common.utils.CommonConstants;
import org.apache.pinot.common.utils.helix.HelixHelper;
import org.apache.pinot.controller.helix.core.PinotHelixBrokerResourceOnlineOfflineStateModelGenerator;
import org.apache.pinot.controller.helix.core.PinotHelixSegmentOnlineOfflineStateModelGenerator;
import org.apache.pinot.controller.helix.core.PinotTableIdealStateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.pinot.common.utils.CommonConstants.Helix.LEAD_CONTROLLER_RESOURCE_NAME;


/**
 * HelixSetupUtils handles how to create or get a helixCluster in controller.
 *
 *
 */
public class HelixSetupUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(HelixSetupUtils.class);

  public static synchronized HelixManager setup(String helixClusterName, String zkPath,
      String pinotControllerInstanceId, boolean isUpdateStateModel, boolean enableBatchMessageMode) {
    try {
      setupHelixCluster(helixClusterName, zkPath, isUpdateStateModel, enableBatchMessageMode);
    } catch (final Exception e) {
      LOGGER.error("Caught exception when setting up Helix cluster: {}", helixClusterName, e);
      return null;
    }

    try {
      return startHelixControllerInStandadloneMode(helixClusterName, zkPath, pinotControllerInstanceId);
    } catch (final Exception e) {
      LOGGER.error("Caught exception when starting helix controller", e);
      return null;
    }
  }

  public static void setupHelixCluster(String helixClusterName, String zkPath, boolean isUpdateStateModel,
      boolean enableBatchMessageMode) {
    final HelixAdmin admin = new ZKHelixAdmin(zkPath);

    createHelixClusterIfNeeded(helixClusterName, zkPath, admin, isUpdateStateModel, enableBatchMessageMode);

    createLeadControllerResourceIfNeeded(helixClusterName, admin, enableBatchMessageMode);
  }

  public static void createHelixClusterIfNeeded(String helixClusterName, String zkPath, HelixAdmin admin,
      boolean isUpdateStateModel, boolean enableBatchMessageMode) {
    final String segmentStateModelName =
        PinotHelixSegmentOnlineOfflineStateModelGenerator.PINOT_SEGMENT_ONLINE_OFFLINE_STATE_MODEL;

    if (admin.getClusters().contains(helixClusterName)) {
      LOGGER.info("cluster already exists ********************************************* ");
      if (isUpdateStateModel) {
        final StateModelDefinition curStateModelDef = admin.getStateModelDef(helixClusterName, segmentStateModelName);
        List<String> states = curStateModelDef.getStatesPriorityList();
        if (states.contains(PinotHelixSegmentOnlineOfflineStateModelGenerator.CONSUMING_STATE)) {
          LOGGER.info("State model {} already updated to contain CONSUMING state", segmentStateModelName);
          return;
        } else {
          LOGGER.info("Updating {} to add states for low level consumers", segmentStateModelName);
          StateModelDefinition newStateModelDef =
              PinotHelixSegmentOnlineOfflineStateModelGenerator.generatePinotStateModelDefinition();
          ZkClient zkClient = new ZkClient(zkPath);
          zkClient.waitUntilConnected(CommonConstants.Helix.ZkClient.DEFAULT_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS);
          zkClient.setZkSerializer(new ZNRecordSerializer());
          HelixDataAccessor accessor =
              new ZKHelixDataAccessor(helixClusterName, new ZkBaseDataAccessor<ZNRecord>(zkClient));
          PropertyKey.Builder keyBuilder = accessor.keyBuilder();
          accessor.setProperty(keyBuilder.stateModelDef(segmentStateModelName), newStateModelDef);
          LOGGER.info("Completed updating statemodel {}", segmentStateModelName);
          zkClient.close();
        }
      }
      return;
    }

    LOGGER.info("Creating a new cluster, as the helix cluster : " + helixClusterName
        + " was not found ********************************************* ");
    admin.addCluster(helixClusterName, false);

    LOGGER.info("Enable auto join.");
    final HelixConfigScope scope =
        new HelixConfigScopeBuilder(ConfigScopeProperty.CLUSTER).forCluster(helixClusterName).build();

    final Map<String, String> props = new HashMap<String, String>();
    props.put(ZKHelixManager.ALLOW_PARTICIPANT_AUTO_JOIN, String.valueOf(true));
    //we need only one segment to be loaded at a time
    props.put(MessageType.STATE_TRANSITION + "." + HelixTaskExecutor.MAX_THREADS, String.valueOf(1));

    admin.setConfig(scope, props);

    LOGGER.info(
        "Adding state model {} (with CONSUMED state) generated using {} **********************************************",
        segmentStateModelName, PinotHelixSegmentOnlineOfflineStateModelGenerator.class.toString());

    // If this is a fresh cluster we are creating, then the cluster will see the CONSUMING state in the
    // state model. But then the servers will never be asked to go to that STATE (whether they have the code
    // to handle it or not) unil we complete the feature using low-level consumers and turn the feature on.
    admin.addStateModelDef(helixClusterName, segmentStateModelName,
        PinotHelixSegmentOnlineOfflineStateModelGenerator.generatePinotStateModelDefinition());

    LOGGER.info("Adding state model definition named : "
        + PinotHelixBrokerResourceOnlineOfflineStateModelGenerator.PINOT_BROKER_RESOURCE_ONLINE_OFFLINE_STATE_MODEL
        + " generated using : " + PinotHelixBrokerResourceOnlineOfflineStateModelGenerator.class.toString()
        + " ********************************************** ");

    admin.addStateModelDef(helixClusterName,
        PinotHelixBrokerResourceOnlineOfflineStateModelGenerator.PINOT_BROKER_RESOURCE_ONLINE_OFFLINE_STATE_MODEL,
        PinotHelixBrokerResourceOnlineOfflineStateModelGenerator.generatePinotStateModelDefinition());

    LOGGER.info("Adding empty ideal state for Broker!");
    HelixHelper.updateResourceConfigsFor(new HashMap<String, String>(), CommonConstants.Helix.BROKER_RESOURCE_INSTANCE,
        helixClusterName, admin);
    IdealState idealState = PinotTableIdealStateBuilder
        .buildEmptyIdealStateForBrokerResource(admin, helixClusterName, enableBatchMessageMode);
    admin.setResourceIdealState(helixClusterName, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE, idealState);
    initPropertyStorePath(helixClusterName, zkPath);
    LOGGER.info("New Cluster setup completed... ********************************************** ");
  }

  private static void initPropertyStorePath(String helixClusterName, String zkPath) {
    String propertyStorePath = PropertyPathConfig.getPath(PropertyType.PROPERTYSTORE, helixClusterName);
    ZkHelixPropertyStore<ZNRecord> propertyStore =
        new ZkHelixPropertyStore<ZNRecord>(zkPath, new ZNRecordSerializer(), propertyStorePath);
    propertyStore.create("/CONFIGS", new ZNRecord(""), AccessOption.PERSISTENT);
    propertyStore.create("/CONFIGS/CLUSTER", new ZNRecord(""), AccessOption.PERSISTENT);
    propertyStore.create("/CONFIGS/TABLE", new ZNRecord(""), AccessOption.PERSISTENT);
    propertyStore.create("/CONFIGS/INSTANCE", new ZNRecord(""), AccessOption.PERSISTENT);
    propertyStore.create("/SCHEMAS", new ZNRecord(""), AccessOption.PERSISTENT);
    propertyStore.create("/SEGMENTS", new ZNRecord(""), AccessOption.PERSISTENT);
  }

  private static HelixManager startHelixControllerInStandadloneMode(String helixClusterName, String zkUrl,
      String pinotControllerInstanceId) {
    LOGGER.info("Starting Helix Standalone Controller ... ");
    return HelixControllerMain
        .startHelixController(zkUrl, helixClusterName, pinotControllerInstanceId, HelixControllerMain.STANDALONE);
  }

  private static void createLeadControllerResourceIfNeeded(String helixClusterName, HelixAdmin admin,
      boolean enableBatchMessageMode) {
    IdealState leadControllerResourceIdealState = admin.getResourceIdealState(helixClusterName, LEAD_CONTROLLER_RESOURCE_NAME);
    if (leadControllerResourceIdealState == null) {
      LOGGER.info("Cluster {} doesn't contain {}. Creating one..", helixClusterName,
          LEAD_CONTROLLER_RESOURCE_NAME);

      admin.addStateModelDef(helixClusterName, MasterSlaveSMD.name, MasterSlaveSMD.build());

      HelixHelper.updateResourceConfigsFor(new HashMap<>(), LEAD_CONTROLLER_RESOURCE_NAME,
          helixClusterName, admin);
      // FULL-AUTO Master-Slave state model with CrushED rebalance strategy.
      admin.addResource(helixClusterName, LEAD_CONTROLLER_RESOURCE_NAME,
          CommonConstants.Helix.DEFAULT_NUMBER_OF_PARTITIONS_IN_LEAD_CONTROLLER_RESOURCE,
          MasterSlaveSMD.name, IdealState.RebalanceMode.FULL_AUTO.toString(), CrushEdRebalanceStrategy.class.getName());

      // Set instance group tag for lead controller resource.
      IdealState leadControllerIdealState =
          admin.getResourceIdealState(helixClusterName, LEAD_CONTROLLER_RESOURCE_NAME);
      leadControllerIdealState.setInstanceGroupTag(CommonConstants.Helix.CONTROLLER_INSTANCE_TYPE);
      leadControllerIdealState.setBatchMessageMode(enableBatchMessageMode);
      leadControllerIdealState.setMinActiveReplicas(0);
      admin.setResourceIdealState(helixClusterName, LEAD_CONTROLLER_RESOURCE_NAME,
          leadControllerIdealState);

      LOGGER.info("Re-balance lead controller resource with replicas: {}",
          CommonConstants.Helix.DEFAULT_NUMBER_OF_CONTROLLER_REPLICAS);
      admin.rebalance(helixClusterName, LEAD_CONTROLLER_RESOURCE_NAME,
          CommonConstants.Helix.DEFAULT_NUMBER_OF_CONTROLLER_REPLICAS);
    }
  }
}
