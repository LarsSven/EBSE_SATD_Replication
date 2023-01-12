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

package org.apache.hadoop.hive.ql;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Represents the driver's state. Also has mechanism for locking for the time of state transitions.
 */
public class LockedDriverState {
  private static ThreadLocal<LockedDriverState> tlInstance = new ThreadLocal<LockedDriverState>() {
    @Override
    protected LockedDriverState initialValue() {
      return new LockedDriverState();
    }
  };

  public static void setLockedDriverState(LockedDriverState state) {
    tlInstance.set(state);
  }

  public static LockedDriverState getLockedDriverState() {
    return tlInstance.get();
  }

  public static void removeLockedDriverState() {
    tlInstance.remove();
  }

  /**
   * Enumeration of the potential driver states.
   */
  private enum DriverState {
    INITIALIZED,
    COMPILING,
    COMPILED,
    EXECUTING,
    EXECUTED,
    // a state that the driver enters after close() has been called to clean the query results
    // and release the resources after the query has been executed
    CLOSED,
    // a state that the driver enters after destroy() is called and it is the end of driver life cycle
    DESTROYED,
    ERROR
  }

  // a lock is used for synchronizing the state transition and its associated resource releases
  private final ReentrantLock stateLock = new ReentrantLock();
  private final AtomicBoolean aborted = new AtomicBoolean();
  private DriverState driverState = DriverState.INITIALIZED;

  public void lock() {
    stateLock.lock();
  }

  public void unlock() {
    stateLock.unlock();
  }

  public boolean isAborted() {
    return aborted.get();
  }

  public void abort() {
    aborted.set(true);
  }

  public void compiling() {
    driverState = DriverState.COMPILING;
  }

  public boolean isCompiling() {
    return driverState == DriverState.COMPILING;
  }

  public void compilationInterrupted(boolean deferClose) {
    driverState = deferClose ? DriverState.EXECUTING : DriverState.ERROR;
  }

  public void compilationFinished(boolean wasError) {
    driverState = wasError ? DriverState.ERROR : DriverState.COMPILED;
  }

  public boolean isCompiled() {
    return driverState == DriverState.COMPILED;
  }

  public void executing() {
    driverState = DriverState.EXECUTING;
  }

  public boolean isExecuting() {
    return driverState == DriverState.EXECUTING;
  }

  public void executionFinished(boolean wasError) {
    driverState = wasError ? DriverState.ERROR : DriverState.EXECUTED;
  }

  public boolean isExecuted() {
    return driverState == DriverState.EXECUTED;
  }

  public void closed() {
    driverState = DriverState.CLOSED;
  }

  public boolean isClosed() {
    return driverState == DriverState.CLOSED;
  }

  public void descroyed() {
    driverState = DriverState.DESTROYED;
  }

  public boolean isDestroyed() {
    return driverState == DriverState.DESTROYED;
  }

  public void error() {
    driverState = DriverState.ERROR;
  }

  public boolean isError() {
    return driverState == DriverState.ERROR;
  }

  @Override
  public String toString() {
    return String.format("%s(aborted:%s)", driverState, aborted.get());
  }
}