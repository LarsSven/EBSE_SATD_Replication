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
package org.apache.spark.deploy.k8s.features

import scala.collection.JavaConverters._
import scala.collection.mutable

import io.fabric8.kubernetes.api.model._

import org.apache.spark.{SecurityManager, SparkConf, SparkException}
import org.apache.spark.deploy.k8s._
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.internal.config.Python._
import org.apache.spark.resource.{ExecutorResourceRequest, ResourceProfile}
import org.apache.spark.rpc.RpcEndpointAddress
import org.apache.spark.scheduler.cluster.CoarseGrainedSchedulerBackend
import org.apache.spark.util.Utils

private[spark] class BasicExecutorFeatureStep(
    kubernetesConf: KubernetesExecutorConf,
    secMgr: SecurityManager,
    resourceProfile: ResourceProfile)
  extends KubernetesFeatureConfigStep with Logging {

  // Consider moving some of these fields to KubernetesConf or KubernetesExecutorSpecificConf
  private val executorContainerImage = kubernetesConf
    .get(EXECUTOR_CONTAINER_IMAGE)
    .getOrElse(throw new SparkException("Must specify the executor container image"))
  private val blockManagerPort = kubernetesConf
    .sparkConf
    .getInt("spark.blockmanager.port", DEFAULT_BLOCKMANAGER_PORT)

  private val executorPodNamePrefix = kubernetesConf.resourceNamePrefix

  private val driverUrl = RpcEndpointAddress(
    kubernetesConf.get(DRIVER_HOST_ADDRESS),
    kubernetesConf.sparkConf.getInt(DRIVER_PORT.key, DEFAULT_DRIVER_PORT),
    CoarseGrainedSchedulerBackend.ENDPOINT_NAME).toString

  private var executorMemoryMiB = kubernetesConf.get(EXECUTOR_MEMORY)

  private var memoryOverheadMiB = kubernetesConf
    .get(EXECUTOR_MEMORY_OVERHEAD)
    .getOrElse(math.max(
      (kubernetesConf.get(MEMORY_OVERHEAD_FACTOR) * executorMemoryMiB).toInt,
      MEMORY_OVERHEAD_MIN_MIB))

  private var executorCores = kubernetesConf.sparkConf.get(EXECUTOR_CORES)

  private var pysparkMemoryMiB =
    kubernetesConf.get(PYSPARK_EXECUTOR_MEMORY).map(_.toInt).getOrElse(0).toLong

  private var memoryOffHeapMiB = Utils.executorOffHeapMemorySizeAsMb(kubernetesConf.sparkConf)

  private val customResources = new mutable.HashSet[ExecutorResourceRequest]
  resourceProfile.executorResources.foreach { case (resource, execReq) =>
    resource match {
      case ResourceProfile.MEMORY =>
        executorMemoryMiB = execReq.amount
      case ResourceProfile.OVERHEAD_MEM =>
        memoryOverheadMiB = execReq.amount
      case ResourceProfile.PYSPARK_MEM =>
        pysparkMemoryMiB = execReq.amount
      case ResourceProfile.OFFHEAP_MEM =>
        memoryOffHeapMiB = execReq.amount.toInt
      case ResourceProfile.CORES =>
        executorCores = execReq.amount.toInt
      case rName =>
        customResources += execReq
    }
  }

  private val executorMemoryString = s"${executorMemoryMiB}m"

  private val executorCoresRequest =
    if (kubernetesConf.sparkConf.contains(KUBERNETES_EXECUTOR_REQUEST_CORES)) {
      kubernetesConf.get(KUBERNETES_EXECUTOR_REQUEST_CORES).get
    } else {
      executorCores.toString
    }
  private val executorLimitCores = kubernetesConf.get(KUBERNETES_EXECUTOR_LIMIT_CORES)

  private val executorMemoryWithOverhead =
    executorMemoryMiB + memoryOverheadMiB + memoryOffHeapMiB
  private val executorMemoryTotal =
    if (kubernetesConf.get(APP_RESOURCE_TYPE) == Some(APP_RESOURCE_TYPE_PYTHON)) {
      executorMemoryWithOverhead + pysparkMemoryMiB
    } else {
      executorMemoryWithOverhead
    }

  private def buildExecutorResourcesQuantities(
      customResources: Set[ExecutorResourceRequest]): Map[String, Quantity] = {
    customResources.map { request =>
      val vendorDomain = if (request.vendor.nonEmpty) {
        request.vendor
      } else {
        throw new SparkException(s"Resource: ${request.resourceName} was requested, " +
          "but vendor was not specified.")
      }
      val quantity = new Quantity(request.amount.toString)
      (KubernetesConf.buildKubernetesResourceName(vendorDomain, request.resourceName), quantity)
    }.toMap
  }

  override def configurePod(pod: SparkPod): SparkPod = {
    val name = s"$executorPodNamePrefix-exec-${kubernetesConf.executorId}"

    // hostname must be no longer than 63 characters, so take the last 63 characters of the pod
    // name as the hostname.  This preserves uniqueness since the end of name contains
    // executorId
    val hostname = name.substring(Math.max(0, name.length - 63))
      // Remove non-word characters from the start of the hostname
      .replaceAll("^[^\\w]+", "")
      // Replace dangerous characters in the remaining string with a safe alternative.
      .replaceAll("[^\\w-]+", "_")

    val executorMemoryQuantity = new Quantity(s"${executorMemoryTotal}Mi")
    val executorCpuQuantity = new Quantity(executorCoresRequest)

    val executorResourceQuantities = buildExecutorResourcesQuantities(customResources.toSet)

    val executorEnv: Seq[EnvVar] = {
        (Seq(
          (ENV_DRIVER_URL, driverUrl),
          (ENV_EXECUTOR_CORES, executorCores.toString),
          (ENV_EXECUTOR_MEMORY, executorMemoryString),
          (ENV_APPLICATION_ID, kubernetesConf.appId),
          // This is to set the SPARK_CONF_DIR to be /opt/spark/conf
          (ENV_SPARK_CONF_DIR, SPARK_CONF_DIR_INTERNAL),
          (ENV_EXECUTOR_ID, kubernetesConf.executorId),
          (ENV_RESOURCE_PROFILE_ID, resourceProfile.id.toString)
        ) ++ kubernetesConf.environment).map { case (k, v) =>
          new EnvVarBuilder()
            .withName(k)
            .withValue(v)
            .build()
        }
      } ++ {
        Seq(new EnvVarBuilder()
          .withName(ENV_EXECUTOR_POD_IP)
          .withValueFrom(new EnvVarSourceBuilder()
            .withNewFieldRef("v1", "status.podIP")
            .build())
          .build())
      } ++ {
        if (kubernetesConf.get(AUTH_SECRET_FILE_EXECUTOR).isEmpty) {
          Option(secMgr.getSecretKey()).map { authSecret =>
            new EnvVarBuilder()
              .withName(SecurityManager.ENV_AUTH_SECRET)
              .withValue(authSecret)
              .build()
          }
        } else None
      } ++ {
        kubernetesConf.get(EXECUTOR_CLASS_PATH).map { cp =>
          new EnvVarBuilder()
            .withName(ENV_CLASSPATH)
            .withValue(cp)
            .build()
        }
      } ++ {
        val userOpts = kubernetesConf.get(EXECUTOR_JAVA_OPTIONS).toSeq.flatMap { opts =>
          val subsOpts = Utils.substituteAppNExecIds(opts, kubernetesConf.appId,
            kubernetesConf.executorId)
          Utils.splitCommandString(subsOpts)
        }

        val sparkOpts = Utils.sparkJavaOpts(kubernetesConf.sparkConf,
          SparkConf.isExecutorStartupConf)

        (userOpts ++ sparkOpts).zipWithIndex.map { case (opt, index) =>
          new EnvVarBuilder()
            .withName(s"$ENV_JAVA_OPT_PREFIX$index")
            .withValue(opt)
            .build()
        }
      }
    executorEnv.find(_.getName == ENV_EXECUTOR_DIRS).foreach { e =>
      e.setValue(e.getValue
        .replaceAll(ENV_APPLICATION_ID, kubernetesConf.appId)
        .replaceAll(ENV_EXECUTOR_ID, kubernetesConf.executorId))
    }

    val requiredPorts = Seq(
      (BLOCK_MANAGER_PORT_NAME, blockManagerPort))
      .map { case (name, port) =>
        new ContainerPortBuilder()
          .withName(name)
          .withContainerPort(port)
          .build()
      }

    val executorContainer = new ContainerBuilder(pod.container)
      .withName(Option(pod.container.getName).getOrElse(DEFAULT_EXECUTOR_CONTAINER_NAME))
      .withImage(executorContainerImage)
      .withImagePullPolicy(kubernetesConf.imagePullPolicy)
      .editOrNewResources()
        .addToRequests("memory", executorMemoryQuantity)
        .addToLimits("memory", executorMemoryQuantity)
        .addToRequests("cpu", executorCpuQuantity)
        .addToLimits(executorResourceQuantities.asJava)
        .endResources()
      .addNewEnv()
        .withName(ENV_SPARK_USER)
        .withValue(Utils.getCurrentUserName())
        .endEnv()
      .addAllToEnv(executorEnv.asJava)
      .withPorts(requiredPorts.asJava)
      .addToArgs("executor")
      .build()
    val containerWithLimitCores = executorLimitCores.map { limitCores =>
      val executorCpuLimitQuantity = new Quantity(limitCores)
      new ContainerBuilder(executorContainer)
        .editResources()
          .addToLimits("cpu", executorCpuLimitQuantity)
          .endResources()
        .build()
    }.getOrElse(executorContainer)
    val containerWithLifecycle =
      if (!kubernetesConf.workerDecommissioning) {
        logInfo("Decommissioning not enabled, skipping shutdown script")
        containerWithLimitCores
      } else {
        logInfo("Adding decommission script to lifecycle")
        new ContainerBuilder(containerWithLimitCores).withNewLifecycle()
          .withNewPreStop()
            .withNewExec()
              .addToCommand("/opt/decom.sh")
            .endExec()
          .endPreStop()
          .endLifecycle()
          .build()
      }
    val ownerReference = kubernetesConf.driverPod.map { pod =>
      new OwnerReferenceBuilder()
        .withController(true)
        .withApiVersion(pod.getApiVersion)
        .withKind(pod.getKind)
        .withName(pod.getMetadata.getName)
        .withUid(pod.getMetadata.getUid)
        .build()
    }
    val executorPod = new PodBuilder(pod.pod)
      .editOrNewMetadata()
        .withName(name)
        .addToLabels(kubernetesConf.labels.asJava)
        .addToAnnotations(kubernetesConf.annotations.asJava)
        .addToOwnerReferences(ownerReference.toSeq: _*)
        .endMetadata()
      .editOrNewSpec()
        .withHostname(hostname)
        .withRestartPolicy("Never")
        .addToNodeSelector(kubernetesConf.nodeSelector.asJava)
        .addToImagePullSecrets(kubernetesConf.imagePullSecrets: _*)
        .endSpec()
      .build()

    kubernetesConf.get(KUBERNETES_EXECUTOR_SCHEDULER_NAME)
      .foreach(executorPod.getSpec.setSchedulerName)

    SparkPod(executorPod, containerWithLifecycle)
  }
}
