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

package org.apache.spark.launcher

import java.io.IOException
import java.net.{InetAddress, Socket}

import org.apache.spark.SPARK_VERSION
import org.apache.spark.internal.Logging
import org.apache.spark.launcher.LauncherProtocol._
import org.apache.spark.util.{ShutdownHookManager, ThreadUtils, Utils}

/**
 * A class that can be used to talk to a launcher server. Users should extend this class to
 * provide implementation for the abstract methods.
 *
 * See `LauncherServer` for an explanation of how launcher communication works.
 */
private[spark] abstract class LauncherBackend extends Logging {

  private var clientThread: Thread = _
  private var connection: BackendConnection = _
  private var lastState: SparkAppHandle.State = _
  private var stopOnShutdown: Boolean = false
  @volatile private var _isConnected = false

  def connect(): Unit = {
    val port = sys.env.get(LauncherProtocol.ENV_LAUNCHER_PORT).map(_.toInt)
    val secret = sys.env.get(LauncherProtocol.ENV_LAUNCHER_SECRET)
    val stopFlag = sys.env.get(LauncherProtocol.ENV_LAUNCHER_STOP_IF_SHUTDOWN).map(_.toBoolean)
    if (port != None && secret != None) {
      connect(port.get, secret.get, stopFlag.getOrElse(false))
    }
  }

  def connect(port: Int, secret: String, stopFlag: Boolean): Unit = {
    this.stopOnShutdown = stopFlag
    val s = new Socket(InetAddress.getLoopbackAddress(), port)
    connection = new BackendConnection(s)
    connection.send(new Hello(secret, SPARK_VERSION))
    clientThread = LauncherBackend.threadFactory.newThread(connection)
    clientThread.start()
    _isConnected = true
    if (stopOnShutdown) {
      logDebug("Adding shutdown hook") // force eager creation of logger
      ShutdownHookManager.addShutdownHook(
        ShutdownHookManager.SPARK_CONTEXT_SHUTDOWN_PRIORITY) { () =>
        logInfo("Invoking onStopRequest() from shutdown hook")
        try {
          if (_isConnected) {
            onStopRequest()
          }
        } catch {
          case ioException: IOException =>
            logError("Error while running LauncherBackend shutdownHook...", ioException)
        }
      }
    }
  }

  def close(): Unit = {
    if (connection != null) {
      try {
        connection.close()
      } finally {
        if (clientThread != null) {
          clientThread.join()
        }
      }
    }
  }

  def setAppId(appId: String): Unit = {
    if (connection != null) {
      connection.send(new SetAppId(appId))
    }
  }

  def setState(state: SparkAppHandle.State): Unit = {
    if (connection != null && lastState != state) {
      connection.send(new SetState(state))
      lastState = state
    }
  }

  /** Return whether the launcher handle is still connected to this backend. */
  def isConnected(): Boolean = _isConnected

  /**
   * Implementations should provide this method, which should try to stop the application
   * as gracefully as possible.
   */
  protected def onStopRequest(): Unit

  /**
   * Callback for when the launcher handle disconnects from this backend.
   */
  protected def onDisconnected() : Unit = { }

  private def fireStopRequest(): Unit = {
    val thread = LauncherBackend.threadFactory.newThread(new Runnable() {
      override def run(): Unit = Utils.tryLogNonFatalError {
        onStopRequest()
      }
    })
    thread.start()
  }

  private class BackendConnection(s: Socket) extends LauncherConnection(s) {

    override protected def handle(m: Message): Unit = m match {
      case _: Stop =>
        fireStopRequest()

      case _ =>
        throw new IllegalArgumentException(s"Unexpected message type: ${m.getClass().getName()}")
    }

    override def close(): Unit = {
      try {
        super.close()
        if (stopOnShutdown) {
          fireStopRequest()
        }
      } finally {
        onDisconnected()
        _isConnected = false
      }
    }
  }

}

private object LauncherBackend {

  val threadFactory = ThreadUtils.namedThreadFactory("LauncherBackend")

}
