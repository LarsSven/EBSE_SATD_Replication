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

package org.apache.spark.storage

import java.io.{File, InputStream, OutputStream}
import java.nio.{ByteBuffer, MappedByteBuffer}
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Random
import akka.actor.{ActorSystem, Cancellable, Props}
import it.unimi.dsi.fastutil.io.{FastBufferedOutputStream, FastByteArrayOutputStream}
import sun.nio.ch.DirectBuffer
import org.apache.spark.{Logging, SecurityManager, SparkConf, SparkEnv, SparkException}
import org.apache.spark.io.CompressionCodec
import org.apache.spark.network._
import org.apache.spark.serializer.Serializer
import org.apache.spark.util._


sealed trait Values

case class ByteBufferValues(buffer: ByteBuffer) extends Values
case class IteratorValues(iterator: Iterator[Any]) extends Values
case class ArrayBufferValues(buffer: ArrayBuffer[Any]) extends Values

private[spark] class BlockManager(
    executorId: String,
    actorSystem: ActorSystem,
    val master: BlockManagerMaster,
    val defaultSerializer: Serializer,
    maxMemory: Long,
    val conf: SparkConf,
    securityManager: SecurityManager)
  extends Logging {

  val shuffleBlockManager = new ShuffleBlockManager(this)
  val diskBlockManager = new DiskBlockManager(shuffleBlockManager,
    conf.get("spark.local.dir",  System.getProperty("java.io.tmpdir")))

  private val blockInfo = new TimeStampedHashMap[BlockId, BlockInfo]

  private[storage] val memoryStore: BlockStore = new MemoryStore(this, maxMemory)
  private[storage] val diskStore = new DiskStore(this, diskBlockManager)
  var tachyonInitialized = false
  private[storage] lazy val tachyonStore: TachyonStore = {
    val storeDir = conf.get("spark.tachyonStore.baseDir", "/tmp_spark_tachyon")
    val appFolderName = conf.get("spark.tachyonStore.folderName")
    val tachyonStorePath = s"${storeDir}/${appFolderName}/${this.executorId}"
    val tachyonMaster = conf.get("spark.tachyonStore.url",  "tachyon://localhost:19998")
    val tachyonBlockManager = new TachyonBlockManager(
      shuffleBlockManager, tachyonStorePath, tachyonMaster)
    tachyonInitialized = true
    new TachyonStore(this, tachyonBlockManager)
  }

  // If we use Netty for shuffle, start a new Netty-based shuffle sender service.
  private val nettyPort: Int = {
    val useNetty = conf.getBoolean("spark.shuffle.use.netty", false)
    val nettyPortConfig = conf.getInt("spark.shuffle.sender.port", 0)
    if (useNetty) diskBlockManager.startShuffleBlockSender(nettyPortConfig) else 0
  }

  val connectionManager = new ConnectionManager(0, conf, securityManager)
  implicit val futureExecContext = connectionManager.futureExecContext

  val blockManagerId = BlockManagerId(
    executorId, connectionManager.id.host, connectionManager.id.port, nettyPort)

  // Max megabytes of data to keep in flight per reducer (to avoid over-allocating memory
  // for receiving shuffle outputs)
  val maxBytesInFlight =
    conf.getLong("spark.reducer.maxMbInFlight", 48) * 1024 * 1024

  // Whether to compress broadcast variables that are stored
  val compressBroadcast = conf.getBoolean("spark.broadcast.compress", true)
  // Whether to compress shuffle output that are stored
  val compressShuffle = conf.getBoolean("spark.shuffle.compress", true)
  // Whether to compress RDD partitions that are stored serialized
  val compressRdds = conf.getBoolean("spark.rdd.compress", false)
  // Whether to compress shuffle output temporarily spilled to disk
  val compressShuffleSpill = conf.getBoolean("spark.shuffle.spill.compress", true)

  val heartBeatFrequency = BlockManager.getHeartBeatFrequency(conf)

  val slaveActor = actorSystem.actorOf(Props(new BlockManagerSlaveActor(this)),
    name = "BlockManagerActor" + BlockManager.ID_GENERATOR.next)

  // Pending re-registration action being executed asynchronously or null if none
  // is pending. Accesses should synchronize on asyncReregisterLock.
  var asyncReregisterTask: Future[Unit] = null
  val asyncReregisterLock = new Object

  private def heartBeat() {
    if (!master.sendHeartBeat(blockManagerId)) {
      reregister()
    }
  }

  var heartBeatTask: Cancellable = null

  private val metadataCleaner = new MetadataCleaner(
    MetadataCleanerType.BLOCK_MANAGER, this.dropOldNonBroadcastBlocks, conf)
  private val broadcastCleaner = new MetadataCleaner(
    MetadataCleanerType.BROADCAST_VARS, this.dropOldBroadcastBlocks, conf)

  initialize()

  // The compression codec to use. Note that the "lazy" val is necessary because we want to delay
  // the initialization of the compression codec until it is first used. The reason is that a Spark
  // program could be using a user-defined codec in a third party jar, which is loaded in
  // Executor.updateDependencies. When the BlockManager is initialized, user level jars hasn't been
  // loaded yet.
  private lazy val compressionCodec: CompressionCodec = CompressionCodec.createCodec(conf)

  /**
   * Construct a BlockManager with a memory limit set based on system properties.
   */
  def this(
      execId: String,
      actorSystem: ActorSystem,
      master: BlockManagerMaster,
      serializer: Serializer,
      conf: SparkConf,
      securityManager: SecurityManager) = {
    this(execId, actorSystem, master, serializer, BlockManager.getMaxMemory(conf),
      conf, securityManager)
  }

  /**
   * Initialize the BlockManager. Register to the BlockManagerMaster, and start the
   * BlockManagerWorker actor.
   */
  private def initialize() {
    master.registerBlockManager(blockManagerId, maxMemory, slaveActor)
    BlockManagerWorker.startBlockManagerWorker(this)
    if (!BlockManager.getDisableHeartBeatsForTesting(conf)) {
      heartBeatTask = actorSystem.scheduler.schedule(0.seconds, heartBeatFrequency.milliseconds) {
        heartBeat()
      }
    }
  }

  /**
   * Report all blocks to the BlockManager again. This may be necessary if we are dropped
   * by the BlockManager and come back or if we become capable of recovering blocks on disk after
   * an executor crash.
   *
   * This function deliberately fails silently if the master returns false (indicating that
   * the slave needs to re-register). The error condition will be detected again by the next
   * heart beat attempt or new block registration and another try to re-register all blocks
   * will be made then.
   */
  private def reportAllBlocks() {
    logInfo("Reporting " + blockInfo.size + " blocks to the master.")
    for ((blockId, info) <- blockInfo) {
      val status = getCurrentBlockStatus(blockId, info)
      if (!tryToReportBlockStatus(blockId, info, status)) {
        logError("Failed to report " + blockId + " to master; giving up.")
        return
      }
    }
  }

  /**
   * Re-register with the master and report all blocks to it. This will be called by the heart beat
   * thread if our heartbeat to the block manager indicates that we were not registered.
   *
   * Note that this method must be called without any BlockInfo locks held.
   */
  def reregister() {
    // TODO: We might need to rate limit re-registering.
    logInfo("BlockManager re-registering with master")
    master.registerBlockManager(blockManagerId, maxMemory, slaveActor)
    reportAllBlocks()
  }

  /**
   * Re-register with the master sometime soon.
   */
  def asyncReregister() {
    asyncReregisterLock.synchronized {
      if (asyncReregisterTask == null) {
        asyncReregisterTask = Future[Unit] {
          reregister()
          asyncReregisterLock.synchronized {
            asyncReregisterTask = null
          }
        }
      }
    }
  }

  /**
   * For testing. Wait for any pending asynchronous re-registration; otherwise, do nothing.
   */
  def waitForAsyncReregister() {
    val task = asyncReregisterTask
    if (task != null) {
      Await.ready(task, Duration.Inf)
    }
  }

  /**
   * Get storage level of local block. If no info exists for the block, then returns null.
   */
  def getLevel(blockId: BlockId): StorageLevel = blockInfo.get(blockId).map(_.level).orNull

  /**
   * Tell the master about the current storage status of a block. This will send a block update
   * message reflecting the current status, *not* the desired storage level in its block info.
   * For example, a block with MEMORY_AND_DISK set might have fallen out to be only on disk.
   *
   * droppedMemorySize exists to account for when the block is dropped from memory to disk (so
   * it is still valid). This ensures that update in master will compensate for the increase in
   * memory on slave.
   */
  def reportBlockStatus(
      blockId: BlockId,
      info: BlockInfo,
      status: BlockStatus,
      droppedMemorySize: Long = 0L) {
    val needReregister = !tryToReportBlockStatus(blockId, info, status, droppedMemorySize)
    if (needReregister) {
      logInfo("Got told to re-register updating block " + blockId)
      // Re-registering will report our new block for free.
      asyncReregister()
    }
    logDebug("Told master about block " + blockId)
  }

  /**
   * Actually send a UpdateBlockInfo message. Returns the master's response,
   * which will be true if the block was successfully recorded and false if
   * the slave needs to re-register.
   */
  private def tryToReportBlockStatus(
      blockId: BlockId,
      info: BlockInfo,
      status: BlockStatus,
      droppedMemorySize: Long = 0L): Boolean = {
    if (info.tellMaster) {
      val storageLevel = status.storageLevel
      val inMemSize = Math.max(status.memSize, droppedMemorySize)
      val inTachyonSize = status.tachyonSize
      val onDiskSize = status.diskSize
      master.updateBlockInfo(
        blockManagerId, blockId, storageLevel, inMemSize, onDiskSize, inTachyonSize)
    } else true
  }

  /**
   * Return the updated storage status of the block with the given ID. More specifically, if
   * the block is dropped from memory and possibly added to disk, return the new storage level
   * and the updated in-memory and on-disk sizes.
   */
  private def getCurrentBlockStatus(blockId: BlockId, info: BlockInfo): BlockStatus = {
    val (newLevel, inMemSize, onDiskSize, inTachyonSize) = info.synchronized {
      info.level match {
        case null =>
          (StorageLevel.NONE, 0L, 0L, 0L)
        case level =>
          val inMem = level.useMemory && memoryStore.contains(blockId)
          val inTachyon = level.useOffHeap && tachyonStore.contains(blockId)
          val onDisk = level.useDisk && diskStore.contains(blockId)
          val deserialized = if (inMem) level.deserialized else false
          val replication = if (inMem || inTachyon || onDisk) level.replication else 1
          val storageLevel = StorageLevel(onDisk, inMem, inTachyon, deserialized, replication)
          val memSize = if (inMem) memoryStore.getSize(blockId) else 0L
          val tachyonSize = if (inTachyon) tachyonStore.getSize(blockId) else 0L
          val diskSize = if (onDisk) diskStore.getSize(blockId) else 0L
          (storageLevel, memSize, diskSize, tachyonSize)
      }
    }
    BlockStatus(newLevel, inMemSize, onDiskSize, inTachyonSize)
  }

  /**
   * Get locations of an array of blocks.
   */
  def getLocationBlockIds(blockIds: Array[BlockId]): Array[Seq[BlockManagerId]] = {
    val startTimeMs = System.currentTimeMillis
    val locations = master.getLocations(blockIds).toArray
    logDebug("Got multiple block location in " + Utils.getUsedTimeMs(startTimeMs))
    locations
  }

  /**
   * A short-circuited method to get blocks directly from disk. This is used for getting
   * shuffle blocks. It is safe to do so without a lock on block info since disk store
   * never deletes (recent) items.
   */
  def getLocalFromDisk(blockId: BlockId, serializer: Serializer): Option[Iterator[Any]] = {
    diskStore.getValues(blockId, serializer).orElse(
      sys.error("Block " + blockId + " not found on disk, though it should be"))
  }

  /**
   * Get block from local block manager.
   */
  def getLocal(blockId: BlockId): Option[Iterator[Any]] = {
    logDebug("Getting local block " + blockId)
    doGetLocal(blockId, asValues = true).asInstanceOf[Option[Iterator[Any]]]
  }

  /**
   * Get block from the local block manager as serialized bytes.
   */
  def getLocalBytes(blockId: BlockId): Option[ByteBuffer] = {
    logDebug("Getting local block " + blockId + " as bytes")
    // As an optimization for map output fetches, if the block is for a shuffle, return it
    // without acquiring a lock; the disk store never deletes (recent) items so this should work
    if (blockId.isShuffle) {
      diskStore.getBytes(blockId) match {
        case Some(bytes) =>
          Some(bytes)
        case None =>
          throw new Exception("Block " + blockId + " not found on disk, though it should be")
      }
    } else {
      doGetLocal(blockId, asValues = false).asInstanceOf[Option[ByteBuffer]]
    }
  }

  private def doGetLocal(blockId: BlockId, asValues: Boolean): Option[Any] = {
    val info = blockInfo.get(blockId).orNull
    if (info != null) {
      info.synchronized {

        // If another thread is writing the block, wait for it to become ready.
        if (!info.waitForReady()) {
          // If we get here, the block write failed.
          logWarning("Block " + blockId + " was marked as failure.")
          return None
        }

        val level = info.level
        logDebug("Level for block " + blockId + " is " + level)

        // Look for the block in memory
        if (level.useMemory) {
          logDebug("Getting block " + blockId + " from memory")
          val result = if (asValues) {
            memoryStore.getValues(blockId)
          } else {
            memoryStore.getBytes(blockId)
          }
          result match {
            case Some(values) =>
              return Some(values)
            case None =>
              logDebug("Block " + blockId + " not found in memory")
          }
        }
        
        // Look for the block in Tachyon
        if (level.useOffHeap) {
          logDebug("Getting block " + blockId + " from tachyon")
          if (tachyonStore.contains(blockId)) {
            tachyonStore.getBytes(blockId) match {
              case Some(bytes) => {
                if (!asValues) {
                  return Some(bytes)
                } else {
                  return Some(dataDeserialize(blockId, bytes))
                }
              }
              case None =>
                logDebug("Block " + blockId + " not found in tachyon")
            }
          }
        }

        // Look for block on disk, potentially storing it back into memory if required:
        if (level.useDisk) {
          logDebug("Getting block " + blockId + " from disk")
          val bytes: ByteBuffer = diskStore.getBytes(blockId) match {
            case Some(bytes) => bytes
            case None =>
              throw new Exception("Block " + blockId + " not found on disk, though it should be")
          }
          assert (0 == bytes.position())

          if (!level.useMemory) {
            // If the block shouldn't be stored in memory, we can just return it:
            if (asValues) {
              return Some(dataDeserialize(blockId, bytes))
            } else {
              return Some(bytes)
            }
          } else {
            // Otherwise, we also have to store something in the memory store:
            if (!level.deserialized || !asValues) {
              // We'll store the bytes in memory if the block's storage level includes
              // "memory serialized", or if it should be cached as objects in memory
              // but we only requested its serialized bytes:
              val copyForMemory = ByteBuffer.allocate(bytes.limit)
              copyForMemory.put(bytes)
              memoryStore.putBytes(blockId, copyForMemory, level)
              bytes.rewind()
            }
            if (!asValues) {
              return Some(bytes)
            } else {
              val values = dataDeserialize(blockId, bytes)
              if (level.deserialized) {
                // Cache the values before returning them:
                // TODO: Consider creating a putValues that also takes in a iterator?
                val valuesBuffer = new ArrayBuffer[Any]
                valuesBuffer ++= values
                memoryStore.putValues(blockId, valuesBuffer, level, true).data match {
                  case Left(values2) =>
                    return Some(values2)
                  case _ =>
                    throw new Exception("Memory store did not return back an iterator")
                }
              } else {
                return Some(values)
              }
            }
          }
        }
      }
    } else {
      logDebug("Block " + blockId + " not registered locally")
    }
    None
  }

  /**
   * Get block from remote block managers.
   */
  def getRemote(blockId: BlockId): Option[Iterator[Any]] = {
    logDebug("Getting remote block " + blockId)
    doGetRemote(blockId, asValues = true).asInstanceOf[Option[Iterator[Any]]]
  }

  /**
   * Get block from remote block managers as serialized bytes.
   */
  def getRemoteBytes(blockId: BlockId): Option[ByteBuffer] = {
    logDebug("Getting remote block " + blockId + " as bytes")
    doGetRemote(blockId, asValues = false).asInstanceOf[Option[ByteBuffer]]
  }

  private def doGetRemote(blockId: BlockId, asValues: Boolean): Option[Any] = {
    require(blockId != null, "BlockId is null")
    val locations = Random.shuffle(master.getLocations(blockId))
    for (loc <- locations) {
      logDebug("Getting remote block " + blockId + " from " + loc)
      val data = BlockManagerWorker.syncGetBlock(
        GetBlock(blockId), ConnectionManagerId(loc.host, loc.port))
      if (data != null) {
        if (asValues) {
          return Some(dataDeserialize(blockId, data))
        } else {
          return Some(data)
        }
      }
      logDebug("The value of block " + blockId + " is null")
    }
    logDebug("Block " + blockId + " not found")
    None
  }

  /**
   * Get a block from the block manager (either local or remote).
   */
  def get(blockId: BlockId): Option[Iterator[Any]] = {
    val local = getLocal(blockId)
    if (local.isDefined) {
      logInfo("Found block %s locally".format(blockId))
      return local
    }
    val remote = getRemote(blockId)
    if (remote.isDefined) {
      logInfo("Found block %s remotely".format(blockId))
      return remote
    }
    None
  }

  /**
   * Get multiple blocks from local and remote block manager using their BlockManagerIds. Returns
   * an Iterator of (block ID, value) pairs so that clients may handle blocks in a pipelined
   * fashion as they're received. Expects a size in bytes to be provided for each block fetched,
   * so that we can control the maxMegabytesInFlight for the fetch.
   */
  def getMultiple(
      blocksByAddress: Seq[(BlockManagerId, Seq[(BlockId, Long)])],
      serializer: Serializer): BlockFetcherIterator = {
    val iter =
      if (conf.getBoolean("spark.shuffle.use.netty", false)) {
        new BlockFetcherIterator.NettyBlockFetcherIterator(this, blocksByAddress, serializer)
      } else {
        new BlockFetcherIterator.BasicBlockFetcherIterator(this, blocksByAddress, serializer)
      }

    iter.initialize()
    iter
  }

  def put(
      blockId: BlockId,
      values: Iterator[Any],
      level: StorageLevel,
      tellMaster: Boolean): Seq[(BlockId, BlockStatus)] = {
    doPut(blockId, IteratorValues(values), level, tellMaster)
  }

  /**
   * A short circuited method to get a block writer that can write data directly to disk.
   * The Block will be appended to the File specified by filename.
   * This is currently used for writing shuffle files out. Callers should handle error
   * cases.
   */
  def getDiskWriter(
      blockId: BlockId,
      file: File,
      serializer: Serializer,
      bufferSize: Int): BlockObjectWriter = {
    val compressStream: OutputStream => OutputStream = wrapForCompression(blockId, _)
    val syncWrites = conf.getBoolean("spark.shuffle.sync", false)
    new DiskBlockObjectWriter(blockId, file, serializer, bufferSize, compressStream, syncWrites)
  }

  /**
   * Put a new block of values to the block manager. Return a list of blocks updated as a
   * result of this put.
   */
  def put(
      blockId: BlockId,
      values: ArrayBuffer[Any],
      level: StorageLevel,
      tellMaster: Boolean = true): Seq[(BlockId, BlockStatus)] = {
    require(values != null, "Values is null")
    doPut(blockId, ArrayBufferValues(values), level, tellMaster)
  }

  /**
   * Put a new block of serialized bytes to the block manager. Return a list of blocks updated
   * as a result of this put.
   */
  def putBytes(
      blockId: BlockId,
      bytes: ByteBuffer,
      level: StorageLevel,
      tellMaster: Boolean = true): Seq[(BlockId, BlockStatus)] = {
    require(bytes != null, "Bytes is null")
    doPut(blockId, ByteBufferValues(bytes), level, tellMaster)
  }

  private def doPut(
      blockId: BlockId,
      data: Values,
      level: StorageLevel,
      tellMaster: Boolean = true): Seq[(BlockId, BlockStatus)] = {

    require(blockId != null, "BlockId is null")
    require(level != null && level.isValid, "StorageLevel is null or invalid")

    // Return value
    val updatedBlocks = new ArrayBuffer[(BlockId, BlockStatus)]

    // Remember the block's storage level so that we can correctly drop it to disk if it needs
    // to be dropped right after it got put into memory. Note, however, that other threads will
    // not be able to get() this block until we call markReady on its BlockInfo.
    val putBlockInfo = {
      val tinfo = new BlockInfo(level, tellMaster)
      // Do atomically !
      val oldBlockOpt = blockInfo.putIfAbsent(blockId, tinfo)

      if (oldBlockOpt.isDefined) {
        if (oldBlockOpt.get.waitForReady()) {
          logWarning("Block " + blockId + " already exists on this machine; not re-adding it")
          return updatedBlocks
        }

        // TODO: So the block info exists - but previous attempt to load it (?) failed.
        // What do we do now ? Retry on it ?
        oldBlockOpt.get
      } else {
        tinfo
      }
    }

    val startTimeMs = System.currentTimeMillis

    // If we're storing values and we need to replicate the data, we'll want access to the values,
    // but because our put will read the whole iterator, there will be no values left. For the
    // case where the put serializes data, we'll remember the bytes, above; but for the case where
    // it doesn't, such as deserialized storage, let's rely on the put returning an Iterator.
    var valuesAfterPut: Iterator[Any] = null

    // Ditto for the bytes after the put
    var bytesAfterPut: ByteBuffer = null

    // Size of the block in bytes
    var size = 0L

    // If we're storing bytes, then initiate the replication before storing them locally.
    // This is faster as data is already serialized and ready to send.
    val replicationFuture = if (data.isInstanceOf[ByteBufferValues] && level.replication > 1) {
      // Duplicate doesn't copy the bytes, just creates a wrapper
      val bufferView = data.asInstanceOf[ByteBufferValues].buffer.duplicate()
      Future {
        replicate(blockId, bufferView, level)
      }
    } else {
      null
    }

    putBlockInfo.synchronized {
      logTrace("Put for block " + blockId + " took " + Utils.getUsedTimeMs(startTimeMs)
        + " to get into synchronized block")

      var marked = false
      try {
        if (level.useMemory) {
          // Save it just to memory first, even if it also has useDisk set to true; we will
          // drop it to disk later if the memory store can't hold it.
          val res = data match {
            case IteratorValues(iterator) =>
              memoryStore.putValues(blockId, iterator, level, true)
            case ArrayBufferValues(array) =>
              memoryStore.putValues(blockId, array, level, true)
            case ByteBufferValues(bytes) => {
              bytes.rewind()
              memoryStore.putBytes(blockId, bytes, level)
            }
          }
          size = res.size
          res.data match {
            case Right(newBytes) => bytesAfterPut = newBytes
            case Left(newIterator) => valuesAfterPut = newIterator
          }
          // Keep track of which blocks are dropped from memory
          res.droppedBlocks.foreach { block => updatedBlocks += block }
        } else if (level.useOffHeap) {
          // Save to Tachyon.
          val res = data match {
            case IteratorValues(iterator) =>
              tachyonStore.putValues(blockId, iterator, level, false)
            case ArrayBufferValues(array) =>
              tachyonStore.putValues(blockId, array, level, false)
            case ByteBufferValues(bytes) => {
              bytes.rewind();
              tachyonStore.putBytes(blockId, bytes, level)
            }
          }
          size = res.size
          res.data match {
            case Right(newBytes) => bytesAfterPut = newBytes
            case _ =>
          }
        } else {
          // Save directly to disk.
          // Don't get back the bytes unless we replicate them.
          val askForBytes = level.replication > 1

          val res = data match {
            case IteratorValues(iterator) =>
              diskStore.putValues(blockId, iterator, level, askForBytes)
            case ArrayBufferValues(array) =>
              diskStore.putValues(blockId, array, level, askForBytes)
            case ByteBufferValues(bytes) => {
              bytes.rewind()
              diskStore.putBytes(blockId, bytes, level)
            }
          }
          size = res.size
          res.data match {
            case Right(newBytes) => bytesAfterPut = newBytes
            case _ =>
          }
        }

        val putBlockStatus = getCurrentBlockStatus(blockId, putBlockInfo)
        if (putBlockStatus.storageLevel != StorageLevel.NONE) {
          // Now that the block is in either the memory, tachyon, or disk store,
          // let other threads read it, and tell the master about it.
          marked = true
          putBlockInfo.markReady(size)
          if (tellMaster) {
            reportBlockStatus(blockId, putBlockInfo, putBlockStatus)
          }
          updatedBlocks += ((blockId, putBlockStatus))
        }
      } finally {
        // If we failed in putting the block to memory/disk, notify other possible readers
        // that it has failed, and then remove it from the block info map.
        if (!marked) {
          // Note that the remove must happen before markFailure otherwise another thread
          // could've inserted a new BlockInfo before we remove it.
          blockInfo.remove(blockId)
          putBlockInfo.markFailure()
          logWarning("Putting block " + blockId + " failed")
        }
      }
    }
    logDebug("Put block " + blockId + " locally took " + Utils.getUsedTimeMs(startTimeMs))

    // Either we're storing bytes and we asynchronously started replication, or we're storing
    // values and need to serialize and replicate them now:
    if (level.replication > 1 && !level.useOffHeap) {
      data match {
        case ByteBufferValues(bytes) => Await.ready(replicationFuture, Duration.Inf)
        case _ => {
          val remoteStartTime = System.currentTimeMillis
          // Serialize the block if not already done
          if (bytesAfterPut == null) {
            if (valuesAfterPut == null) {
              throw new SparkException(
                "Underlying put returned neither an Iterator nor bytes! This shouldn't happen.")
            }
            bytesAfterPut = dataSerialize(blockId, valuesAfterPut)
          }
          replicate(blockId, bytesAfterPut, level)
          logDebug("Put block " + blockId + " remotely took " +
            Utils.getUsedTimeMs(remoteStartTime))
        }
      }
    }

    BlockManager.dispose(bytesAfterPut)

    if (level.replication > 1 && !level.useOffHeap) {
      logDebug("Put for block " + blockId + " with replication took " +
        Utils.getUsedTimeMs(startTimeMs))
    } else {
      logDebug("Put for block " + blockId + " without replication took " +
        Utils.getUsedTimeMs(startTimeMs))
    }

    updatedBlocks
  }

  /**
   * Replicate block to another node.
   */
  var cachedPeers: Seq[BlockManagerId] = null
  private def replicate(blockId: BlockId, data: ByteBuffer, level: StorageLevel) {
    val tLevel = StorageLevel(
      level.useDisk, level.useMemory, level.useOffHeap, level.deserialized, 1)
    if (cachedPeers == null) {
      cachedPeers = master.getPeers(blockManagerId, level.replication - 1)
    }
    for (peer: BlockManagerId <- cachedPeers) {
      val start = System.nanoTime
      data.rewind()
      logDebug("Try to replicate BlockId " + blockId + " once; The size of the data is "
        + data.limit() + " Bytes. To node: " + peer)
      if (!BlockManagerWorker.syncPutBlock(PutBlock(blockId, data, tLevel),
        new ConnectionManagerId(peer.host, peer.port))) {
        logError("Failed to call syncPutBlock to " + peer)
      }
      logDebug("Replicated BlockId " + blockId + " once used " +
        (System.nanoTime - start) / 1e6 + " s; The size of the data is " +
        data.limit() + " bytes.")
    }
  }

  /**
   * Read a block consisting of a single object.
   */
  def getSingle(blockId: BlockId): Option[Any] = {
    get(blockId).map(_.next())
  }

  /**
   * Write a block consisting of a single object.
   */
  def putSingle(
      blockId: BlockId,
      value: Any,
      level: StorageLevel,
      tellMaster: Boolean = true): Seq[(BlockId, BlockStatus)] = {
    put(blockId, Iterator(value), level, tellMaster)
  }

  /**
   * Drop a block from memory, possibly putting it on disk if applicable. Called when the memory
   * store reaches its limit and needs to free up space.
   *
   * Return the block status if the given block has been updated, else None.
   */
  def dropFromMemory(
      blockId: BlockId,
      data: Either[ArrayBuffer[Any], ByteBuffer]): Option[BlockStatus] = {

    logInfo("Dropping block " + blockId + " from memory")
    val info = blockInfo.get(blockId).orNull

    // If the block has not already been dropped
    if (info != null)  {
      info.synchronized {
        // required ? As of now, this will be invoked only for blocks which are ready
        // But in case this changes in future, adding for consistency sake.
        if (!info.waitForReady()) {
          // If we get here, the block write failed.
          logWarning("Block " + blockId + " was marked as failure. Nothing to drop")
          return None
        }

        var blockIsUpdated = false
        val level = info.level

        // Drop to disk, if storage level requires
        if (level.useDisk && !diskStore.contains(blockId)) {
          logInfo("Writing block " + blockId + " to disk")
          data match {
            case Left(elements) =>
              diskStore.putValues(blockId, elements, level, false)
            case Right(bytes) =>
              diskStore.putBytes(blockId, bytes, level)
          }
          blockIsUpdated = true
        }

        // Actually drop from memory store
        val droppedMemorySize =
          if (memoryStore.contains(blockId)) memoryStore.getSize(blockId) else 0L
        val blockIsRemoved = memoryStore.remove(blockId)
        if (blockIsRemoved) {
          blockIsUpdated = true
        } else {
          logWarning("Block " + blockId + " could not be dropped from memory as it does not exist")
        }

        val status = getCurrentBlockStatus(blockId, info)
        if (info.tellMaster) {
          reportBlockStatus(blockId, info, status, droppedMemorySize)
        }
        if (!level.useDisk) {
          // The block is completely gone from this node; forget it so we can put() it again later.
          blockInfo.remove(blockId)
        }
        if (blockIsUpdated) {
          return Some(status)
        }
      }
    }
    None
  }

  /**
   * Remove all blocks belonging to the given RDD.
   * @return The number of blocks removed.
   */
  def removeRdd(rddId: Int): Int = {
    // TODO: Instead of doing a linear scan on the blockInfo map, create another map that maps
    // from RDD.id to blocks.
    logInfo("Removing RDD " + rddId)
    val blocksToRemove = blockInfo.keys.flatMap(_.asRDDId).filter(_.rddId == rddId)
    blocksToRemove.foreach(blockId => removeBlock(blockId, tellMaster = false))
    blocksToRemove.size
  }

  /**
   * Remove a block from both memory and disk.
   */
  def removeBlock(blockId: BlockId, tellMaster: Boolean = true) {
    logInfo("Removing block " + blockId)
    val info = blockInfo.get(blockId).orNull
    if (info != null) info.synchronized {
      // Removals are idempotent in disk store and memory store. At worst, we get a warning.
      val removedFromMemory = memoryStore.remove(blockId)
      val removedFromDisk = diskStore.remove(blockId)
      val removedFromTachyon = if (tachyonInitialized) tachyonStore.remove(blockId) else false
      if (!removedFromMemory && !removedFromDisk && !removedFromTachyon) {
        logWarning("Block " + blockId + " could not be removed as it was not found in either " +
          "the disk, memory, or tachyon store")
      }
      blockInfo.remove(blockId)
      if (tellMaster && info.tellMaster) {
        val status = getCurrentBlockStatus(blockId, info)
        reportBlockStatus(blockId, info, status)
      }
    } else {
      // The block has already been removed; do nothing.
      logWarning("Asked to remove block " + blockId + ", which does not exist")
    }
  }

  private def dropOldNonBroadcastBlocks(cleanupTime: Long) {
    logInfo("Dropping non broadcast blocks older than " + cleanupTime)
    dropOldBlocks(cleanupTime, !_.isBroadcast)
  }

  private def dropOldBroadcastBlocks(cleanupTime: Long) {
    logInfo("Dropping broadcast blocks older than " + cleanupTime)
    dropOldBlocks(cleanupTime, _.isBroadcast)
  }

  private def dropOldBlocks(cleanupTime: Long, shouldDrop: (BlockId => Boolean)) {
    val iterator = blockInfo.internalMap.entrySet().iterator()
    while (iterator.hasNext) {
      val entry = iterator.next()
      val (id, info, time) = (entry.getKey, entry.getValue._1, entry.getValue._2)
      if (time < cleanupTime && shouldDrop(id)) {
        info.synchronized {
          val level = info.level
          if (level.useMemory) {
            memoryStore.remove(id)
          }
          if (level.useDisk) {
            diskStore.remove(id)
          }
          if (level.useOffHeap) {
            tachyonStore.remove(id)
          }
          iterator.remove()
          logInfo("Dropped block " + id)
        }
        val status = getCurrentBlockStatus(id, info)
        reportBlockStatus(id, info, status)
      }
    }
  }

  def shouldCompress(blockId: BlockId): Boolean = blockId match {
    case ShuffleBlockId(_, _, _) => compressShuffle
    case BroadcastBlockId(_) => compressBroadcast
    case RDDBlockId(_, _) => compressRdds
    case TempBlockId(_) => compressShuffleSpill
    case _ => false
  }

  /**
   * Wrap an output stream for compression if block compression is enabled for its block type
   */
  def wrapForCompression(blockId: BlockId, s: OutputStream): OutputStream = {
    if (shouldCompress(blockId)) compressionCodec.compressedOutputStream(s) else s
  }

  /**
   * Wrap an input stream for compression if block compression is enabled for its block type
   */
  def wrapForCompression(blockId: BlockId, s: InputStream): InputStream = {
    if (shouldCompress(blockId)) compressionCodec.compressedInputStream(s) else s
  }

  /** Serializes into a stream. */
  def dataSerializeStream(
      blockId: BlockId,
      outputStream: OutputStream,
      values: Iterator[Any],
      serializer: Serializer = defaultSerializer) {
    val byteStream = new FastBufferedOutputStream(outputStream)
    val ser = serializer.newInstance()
    ser.serializeStream(wrapForCompression(blockId, byteStream)).writeAll(values).close()
  }

  /** Serializes into a byte buffer. */
  def dataSerialize(
      blockId: BlockId,
      values: Iterator[Any],
      serializer: Serializer = defaultSerializer): ByteBuffer = {
    val byteStream = new FastByteArrayOutputStream(4096)
    dataSerializeStream(blockId, byteStream, values, serializer)
    byteStream.trim()
    ByteBuffer.wrap(byteStream.array)
  }

  /**
   * Deserializes a ByteBuffer into an iterator of values and disposes of it when the end of
   * the iterator is reached.
   */
  def dataDeserialize(
      blockId: BlockId,
      bytes: ByteBuffer,
      serializer: Serializer = defaultSerializer): Iterator[Any] = {
    bytes.rewind()
    val stream = wrapForCompression(blockId, new ByteBufferInputStream(bytes, true))
    serializer.newInstance().deserializeStream(stream).asIterator
  }

  def stop() {
    if (heartBeatTask != null) {
      heartBeatTask.cancel()
    }
    connectionManager.stop()
    actorSystem.stop(slaveActor)
    blockInfo.clear()
    memoryStore.clear()
    diskStore.clear()
    if (tachyonInitialized) {
      tachyonStore.clear() 
    }
    metadataCleaner.cancel()
    broadcastCleaner.cancel()
    logInfo("BlockManager stopped")
  }
}


private[spark] object BlockManager extends Logging {
  val ID_GENERATOR = new IdGenerator

  def getMaxMemory(conf: SparkConf): Long = {
    val memoryFraction = conf.getDouble("spark.storage.memoryFraction", 0.6)
    (Runtime.getRuntime.maxMemory * memoryFraction).toLong
  }

  def getHeartBeatFrequency(conf: SparkConf): Long =
    conf.getLong("spark.storage.blockManagerTimeoutIntervalMs", 60000) / 4

  def getDisableHeartBeatsForTesting(conf: SparkConf): Boolean =
    conf.getBoolean("spark.test.disableBlockManagerHeartBeat", false)

  /**
   * Attempt to clean up a ByteBuffer if it is memory-mapped. This uses an *unsafe* Sun API that
   * might cause errors if one attempts to read from the unmapped buffer, but it's better than
   * waiting for the GC to find it because that could lead to huge numbers of open files. There's
   * unfortunately no standard API to do this.
   */
  def dispose(buffer: ByteBuffer) {
    if (buffer != null && buffer.isInstanceOf[MappedByteBuffer]) {
      logTrace("Unmapping " + buffer)
      if (buffer.asInstanceOf[DirectBuffer].cleaner() != null) {
        buffer.asInstanceOf[DirectBuffer].cleaner().clean()
      }
    }
  }

  def blockIdsToBlockManagers(
      blockIds: Array[BlockId],
      env: SparkEnv,
      blockManagerMaster: BlockManagerMaster = null): Map[BlockId, Seq[BlockManagerId]] = {

    // blockManagerMaster != null is used in tests
    assert (env != null || blockManagerMaster != null)
    val blockLocations: Seq[Seq[BlockManagerId]] = if (blockManagerMaster == null) {
      env.blockManager.getLocationBlockIds(blockIds)
    } else {
      blockManagerMaster.getLocations(blockIds)
    }

    val blockManagers = new HashMap[BlockId, Seq[BlockManagerId]]
    for (i <- 0 until blockIds.length) {
      blockManagers(blockIds(i)) = blockLocations(i)
    }
    blockManagers.toMap
  }

  def blockIdsToExecutorIds(
      blockIds: Array[BlockId],
      env: SparkEnv,
      blockManagerMaster: BlockManagerMaster = null): Map[BlockId, Seq[String]] = {
    blockIdsToBlockManagers(blockIds, env, blockManagerMaster).mapValues(s => s.map(_.executorId))
  }

  def blockIdsToHosts(
      blockIds: Array[BlockId],
      env: SparkEnv,
      blockManagerMaster: BlockManagerMaster = null): Map[BlockId, Seq[String]] = {
    blockIdsToBlockManagers(blockIds, env, blockManagerMaster).mapValues(s => s.map(_.host))
  }
}
