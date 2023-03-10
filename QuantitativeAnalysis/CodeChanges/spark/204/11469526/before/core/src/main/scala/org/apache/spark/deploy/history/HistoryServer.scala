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

package org.apache.spark.deploy.history

import javax.servlet.http.HttpServletRequest

import scala.collection.mutable

import org.apache.hadoop.fs.{FileStatus, Path}
import org.eclipse.jetty.servlet.ServletContextHandler

import org.apache.spark.{Logging, SecurityManager, SparkConf}
import org.apache.spark.deploy.SparkUIContainer
import org.apache.spark.scheduler._
import org.apache.spark.ui.SparkUI
import org.apache.spark.ui.JettyUtils._
import org.apache.spark.util.Utils

/**
 * A web server that renders SparkUIs of finished applications.
 *
 * For the standalone mode, MasterWebUI already achieves this functionality. Thus, the
 * main use case of the HistoryServer is in other deploy modes (e.g. Yarn or Mesos).
 *
 * The logging directory structure is as follows: Within the given base directory, each
 * application's event logs are maintained in the application's own sub-directory. This
 * is the same structure as maintained in the event log write code path in
 * EventLoggingListener.
 *
 * @param baseLogDir The base directory in which event logs are found
 */
class HistoryServer(
    val baseLogDir: String,
    conf: SparkConf)
  extends SparkUIContainer("History Server") with Logging {

  import HistoryServer._

  private val fileSystem = Utils.getHadoopFileSystem(baseLogDir)
  private val localHost = Utils.localHostName()
  private val publicHost = Option(System.getenv("SPARK_PUBLIC_DNS")).getOrElse(localHost)
  private val port = WEB_UI_PORT
  private val securityManager = new SecurityManager(conf)
  private val indexPage = new IndexPage(this)

  // A timestamp of when the disk was last accessed to check for log updates
  private var lastLogCheckTime = -1L

  // Number of complete applications found in this directory
  private var numApplicationsTotal = 0

  @volatile private var stopped = false

  /**
   * A background thread that periodically checks for event log updates on disk.
   *
   * If a log check is invoked manually in the middle of a period, this thread re-adjusts the
   * time at which it performs the next log check to maintain the same period as before.
   *
   * TODO: Add a mechanism to update manually.
   */
  private val logCheckingThread = new Thread {
    override def run() {
      while (!stopped) {
        val now = System.currentTimeMillis
        if (now - lastLogCheckTime > UPDATE_INTERVAL_MS) {
          checkForLogs()
          Thread.sleep(UPDATE_INTERVAL_MS)
        } else {
          // If the user has manually checked for logs recently, wait until
          // UPDATE_INTERVAL_MS after the last check time
          Thread.sleep(lastLogCheckTime + UPDATE_INTERVAL_MS - now)
        }
      }
    }
  }

  private val handlers = Seq[ServletContextHandler](
    createStaticHandler(STATIC_RESOURCE_DIR, "/static"),
    createServletHandler("/",
      (request: HttpServletRequest) => indexPage.render(request), securityMgr = securityManager)
  )

  // A mapping of application ID to its history information, which includes the rendered UI
  val appIdToInfo = mutable.HashMap[String, ApplicationHistoryInfo]()

  /**
   * Start the history server.
   *
   * This starts a background thread that periodically synchronizes information displayed on
   * this UI with the event logs in the provided base directory.
   */
  def start() {
    logCheckingThread.start()
  }

  /** Bind to the HTTP server behind this web interface. */
  override def bind() {
    try {
      serverInfo = Some(startJettyServer("0.0.0.0", port, handlers, conf))
      logInfo("Started HistoryServer at http://%s:%d".format(publicHost, boundPort))
    } catch {
      case e: Exception =>
        logError("Failed to bind HistoryServer", e)
        System.exit(1)
    }
  }

  /**
   * Check for any updates to event logs in the base directory. This is only effective once
   * the server has been bound.
   *
   * If a new finished application is found, the server renders the associated SparkUI
   * from the application's event logs, attaches this UI to itself, and stores metadata
   * information for this application.
   *
   * If the logs for an existing finished application are no longer found, the server
   * removes all associated information and detaches the SparkUI.
   */
  def checkForLogs() = synchronized {
    if (serverInfo.isDefined) {
      lastLogCheckTime = System.currentTimeMillis
      logDebug("Checking for logs. Time is now %d.".format(lastLogCheckTime))
      try {
        val logStatus = fileSystem.listStatus(new Path(baseLogDir))
        val logDirs = if (logStatus != null) logStatus.filter(_.isDir).toSeq else Seq[FileStatus]()
        val logInfos = logDirs
          .sortBy { dir => getModificationTime(dir) }
          .map { dir => (dir, EventLoggingListener.parseLoggingInfo(dir.getPath, fileSystem)) }
          .filter { case (dir, info) => info.applicationComplete }

        // Logging information for applications that should be retained
        val retainedLogInfos = logInfos.takeRight(RETAINED_APPLICATIONS)
        val retainedAppIds = retainedLogInfos.map { case (dir, _) => dir.getPath.getName }

        // Remove any applications that should no longer be retained
        appIdToInfo.foreach { case (appId, info) =>
          if (!retainedAppIds.contains(appId)) {
            detachUI(info.ui)
            appIdToInfo.remove(appId)
          }
        }

        // Render the application's UI if it is not already there
        retainedLogInfos.foreach { case (dir, info) =>
          val appId = dir.getPath.getName
          if (!appIdToInfo.contains(appId)) {
            renderSparkUI(dir, info)
          }
        }

        // Track the total number of complete applications observed this round
        numApplicationsTotal = logInfos.size

      } catch {
        case t: Throwable => logError("Exception in checking for event log updates", t)
      }
    } else {
      logWarning("Attempted to check for event log updates before binding the server.")
    }
  }

  /**
   * Render a new SparkUI from the event logs if the associated application is finished.
   *
   * HistoryServer looks for a special file that indicates application completion in the given
   * directory. If this file exists, the associated application is regarded to be complete, in
   * which case the server proceeds to render the SparkUI. Otherwise, the server does nothing.
   */
  private def renderSparkUI(logDir: FileStatus, logInfo: EventLoggingInfo) {
    val path = logDir.getPath
    val appId = path.getName
    val replayBus = new ReplayListenerBus(logInfo.logPaths, fileSystem, logInfo.compressionCodec)
    val ui = new SparkUI(replayBus, appId, "/history/" + appId)
    val appListener = new ApplicationEventListener
    replayBus.addListener(appListener)

    // Do not call ui.bind() to avoid creating a new server for each application
    ui.start()
    replayBus.replay()
    if (appListener.applicationStarted) {
      attachUI(ui)
      val appName = appListener.appName
      val sparkUser = appListener.sparkUser
      val startTime = appListener.startTime
      val endTime = appListener.endTime
      val lastUpdated = getModificationTime(logDir)
      ui.setAppName(appName + " (finished)")
      appIdToInfo(appId) = ApplicationHistoryInfo(appId, appName, startTime, endTime,
        lastUpdated, sparkUser, path, ui)
    }
  }

  /** Stop the server and close the file system. */
  override def stop() {
    super.stop()
    stopped = true
    fileSystem.close()
  }

  /** Return the address of this server. */
  def getAddress: String = "http://" + publicHost + ":" + boundPort

  /** Return the total number of application logs found, whether or not the UI is retained. */
  def getNumApplications: Int = numApplicationsTotal

  /** Return when this directory was last modified. */
  private def getModificationTime(dir: FileStatus): Long = {
    try {
      val logFiles = fileSystem.listStatus(dir.getPath)
      if (logFiles != null) {
        logFiles.map(_.getModificationTime).max
      } else {
        dir.getModificationTime
      }
    } catch {
      case t: Throwable =>
        logError("Exception in accessing modification time of %s".format(dir.getPath), t)
        -1L
    }
  }
}

/**
 * The recommended way of starting and stopping a HistoryServer is through the scripts
 * start-history-server.sh and stop-history-server.sh. The path to a base log directory
 * is must be specified, while the requested UI port is optional. For example:
 *
 *   ./sbin/spark-history-server.sh /tmp/spark-events
 *   ./sbin/spark-history-server.sh hdfs://1.2.3.4:9000/spark-events
 *
 * This launches the HistoryServer as a Spark daemon.
 */
object HistoryServer {
  private val conf = new SparkConf

  // Interval between each check for event log updates
  val UPDATE_INTERVAL_MS = conf.getInt("spark.history.updateInterval", 10) * 1000

  // How many applications to retain
  val RETAINED_APPLICATIONS = conf.getInt("spark.history.retainedApplications", 250)

  // The port to which the web UI is bound
  val WEB_UI_PORT = conf.getInt("spark.history.ui.port", 18080)

  val STATIC_RESOURCE_DIR = SparkUI.STATIC_RESOURCE_DIR

  def main(argStrings: Array[String]) {
    val args = new HistoryServerArguments(argStrings)
    val server = new HistoryServer(args.logDir, conf)
    server.bind()
    server.start()

    // Wait until the end of the world... or if the HistoryServer process is manually stopped
    while(true) { Thread.sleep(Int.MaxValue) }
    server.stop()
  }
}


private[spark] case class ApplicationHistoryInfo(
    id: String,
    name: String,
    startTime: Long,
    endTime: Long,
    lastUpdated: Long,
    sparkUser: String,
    logDirPath: Path,
    ui: SparkUI) {
  def started = startTime != -1
  def finished = endTime != -1
}
