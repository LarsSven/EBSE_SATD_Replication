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

package org.apache.hadoop.fs.s3a.s3guard;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.Retries;
import org.apache.hadoop.fs.s3a.Retries.RetryTranslated;
import org.apache.hadoop.fs.s3a.S3AFileStatus;
import org.apache.hadoop.fs.s3a.S3AInstrumentation;
import org.apache.hadoop.fs.s3a.Tristate;
import org.apache.hadoop.util.ReflectionUtils;

import javax.annotation.Nullable;

import static org.apache.hadoop.fs.s3a.Constants.DEFAULT_METADATASTORE_METADATA_TTL;
import static org.apache.hadoop.fs.s3a.Constants.METADATASTORE_METADATA_TTL;
import static org.apache.hadoop.fs.s3a.Constants.S3_METADATA_STORE_IMPL;
import static org.apache.hadoop.fs.s3a.Statistic.S3GUARD_METADATASTORE_PUT_PATH_LATENCY;
import static org.apache.hadoop.fs.s3a.Statistic.S3GUARD_METADATASTORE_PUT_PATH_REQUEST;
import static org.apache.hadoop.fs.s3a.S3AUtils.createUploadFileStatus;

/**
 * Logic for integrating MetadataStore with S3A.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public final class S3Guard {
  private static final Logger LOG = LoggerFactory.getLogger(S3Guard.class);

  @InterfaceAudience.Private
  @InterfaceStability.Unstable
  @VisibleForTesting
  public static final String S3GUARD_DDB_CLIENT_FACTORY_IMPL =
      "fs.s3a.s3guard.ddb.client.factory.impl";

  static final Class<? extends DynamoDBClientFactory>
      S3GUARD_DDB_CLIENT_FACTORY_IMPL_DEFAULT =
      DynamoDBClientFactory.DefaultDynamoDBClientFactory.class;
  private static final S3AFileStatus[] EMPTY_LISTING = new S3AFileStatus[0];

  // Utility class.  All static functions.
  private S3Guard() { }

  /* Utility functions. */

  /**
   * Create a new instance of the configured MetadataStore.
   * The returned MetadataStore will have been initialized via
   * {@link MetadataStore#initialize(FileSystem)} by this function before
   * returning it.  Callers must clean up by calling
   * {@link MetadataStore#close()} when done using the MetadataStore.
   *
   * @param fs  FileSystem whose Configuration specifies which
   *            implementation to use.
   * @return Reference to new MetadataStore.
   * @throws IOException if the metadata store cannot be instantiated
   */
  @Retries.OnceTranslated
  public static MetadataStore getMetadataStore(FileSystem fs)
      throws IOException {
    Preconditions.checkNotNull(fs);
    Configuration conf = fs.getConf();
    Preconditions.checkNotNull(conf);
    MetadataStore msInstance;
    try {
      Class<? extends MetadataStore> msClass = getMetadataStoreClass(conf);
      msInstance = ReflectionUtils.newInstance(msClass, conf);
      LOG.debug("Using {} metadata store for {} filesystem",
          msClass.getSimpleName(), fs.getScheme());
      msInstance.initialize(fs);
      return msInstance;
    } catch (FileNotFoundException e) {
      // Don't log this exception as it means the table doesn't exist yet;
      // rely on callers to catch and treat specially
      throw e;
    } catch (RuntimeException | IOException e) {
      String message = "Failed to instantiate metadata store " +
          conf.get(S3_METADATA_STORE_IMPL)
          + " defined in " + S3_METADATA_STORE_IMPL
          + ": " + e;
      LOG.error(message, e);
      if (e instanceof IOException) {
        throw e;
      } else {
        throw new IOException(message, e);
      }
    }
  }

  static Class<? extends MetadataStore> getMetadataStoreClass(
      Configuration conf) {
    if (conf == null) {
      return NullMetadataStore.class;
    }
    if (conf.get(S3_METADATA_STORE_IMPL) != null && LOG.isDebugEnabled()) {
      LOG.debug("Metastore option source {}",
          conf.getPropertySources(S3_METADATA_STORE_IMPL));
    }

    Class<? extends MetadataStore> aClass = conf.getClass(
        S3_METADATA_STORE_IMPL, NullMetadataStore.class,
        MetadataStore.class);
    return aClass;
  }


  /**
   * Helper function which puts a given S3AFileStatus into the MetadataStore and
   * returns the same S3AFileStatus. Instrumentation monitors the put operation.
   * @param ms MetadataStore to {@code put()} into.
   * @param status status to store
   * @param instrumentation instrumentation of the s3a file system
   * @return The same status as passed in
   * @throws IOException if metadata store update failed
   */
  @RetryTranslated
  public static S3AFileStatus putAndReturn(MetadataStore ms,
      S3AFileStatus status,
      S3AInstrumentation instrumentation,
      ITtlTimeProvider timeProvider) throws IOException {
    long startTimeNano = System.nanoTime();
    S3Guard.putWithTtl(ms, new PathMetadata(status), timeProvider);
    instrumentation.addValueToQuantiles(S3GUARD_METADATASTORE_PUT_PATH_LATENCY,
        (System.nanoTime() - startTimeNano));
    instrumentation.incrementCounter(S3GUARD_METADATASTORE_PUT_PATH_REQUEST, 1);
    return status;
  }

  /**
   * Convert the data of a directory listing to an array of {@link FileStatus}
   * entries. Tombstones are filtered out at this point. If the listing is null
   * an empty array is returned.
   * @param dirMeta directory listing -may be null
   * @return a possibly-empty array of file status entries
   */
  public static S3AFileStatus[] dirMetaToStatuses(DirListingMetadata dirMeta)  {
    if (dirMeta == null) {
      return EMPTY_LISTING;
    }

    Collection<PathMetadata> listing = dirMeta.getListing();
    List<FileStatus> statuses = new ArrayList<>();

    for (PathMetadata pm : listing) {
      if (!pm.isDeleted()) {
        statuses.add(pm.getFileStatus());
      }
    }

    return statuses.toArray(new S3AFileStatus[0]);
  }

  /**
   * Given directory listing metadata from both the backing store and the
   * MetadataStore, merge the two sources of truth to create a consistent
   * view of the current directory contents, which can be returned to clients.
   *
   * Also update the MetadataStore to reflect the resulting directory listing.
   *
   * In not authoritative case: update file metadata if mod_time in listing
   * of a file is greater then what is currently in the ms
   *
   * @param ms MetadataStore to use.
   * @param path path to directory
   * @param backingStatuses Directory listing from the backing store.
   * @param dirMeta  Directory listing from MetadataStore.  May be null.
   * @param isAuthoritative State of authoritative mode
   * @param timeProvider Time provider to use when updating entries
   * @return Final result of directory listing.
   * @throws IOException if metadata store update failed
   */
  public static FileStatus[] dirListingUnion(MetadataStore ms, Path path,
      List<S3AFileStatus> backingStatuses, DirListingMetadata dirMeta,
      boolean isAuthoritative, ITtlTimeProvider timeProvider)
      throws IOException {

    // Fast-path for NullMetadataStore
    if (isNullMetadataStore(ms)) {
      return backingStatuses.toArray(new FileStatus[backingStatuses.size()]);
    }

    assertQualified(path);

    if (dirMeta == null) {
      // The metadataStore had zero state for this directory
      dirMeta = new DirListingMetadata(path, DirListingMetadata.EMPTY_DIR,
          false);
    }

    Set<Path> deleted = dirMeta.listTombstones();

    // Since we treat the MetadataStore as a "fresher" or "consistent" view
    // of metadata, we always use its metadata first.

    // Since the authoritative case is already handled outside this function,
    // we will basically start with the set of directory entries in the
    // DirListingMetadata, and add any that only exist in the backingStatuses.
    boolean changed = false;
    final Map<Path, FileStatus> dirMetaMap = dirMeta.getListing().stream()
        .collect(Collectors.toMap(
            pm -> pm.getFileStatus().getPath(), PathMetadata::getFileStatus)
        );

    for (S3AFileStatus s : backingStatuses) {
      if (deleted.contains(s.getPath())) {
        continue;
      }

      if (!isAuthoritative){
        FileStatus status = dirMetaMap.get(s.getPath());
        if (status != null
            && s.getModificationTime() > status.getModificationTime()) {
          LOG.debug("Update ms with newer metadata of: {}", status);
          S3Guard.putWithTtl(ms, new PathMetadata(s), timeProvider);
        }
      }

      // Minor race condition here.  Multiple threads could add to this
      // mutable DirListingMetadata.  Since it is backed by a
      // ConcurrentHashMap, the last put() wins.
      // More concerning is two threads racing on listStatus() and delete().
      // Any FileSystem has similar race conditions, but we could persist
      // a stale entry longer.  We could expose an atomic
      // DirListingMetadata#putIfNotPresent()
      boolean updated = dirMeta.put(s);
      changed = changed || updated;
    }

    // If dirMeta is not authoritative, but isAuthoritative is true the
    // directory metadata should be updated. Treat it as a change.
    changed = changed || (!dirMeta.isAuthoritative() && isAuthoritative);

    if (changed && isAuthoritative) {
      dirMeta.setAuthoritative(true); // This is the full directory contents
      S3Guard.putWithTtl(ms, dirMeta, timeProvider);
    }

    return dirMetaToStatuses(dirMeta);
  }

  /**
   * Although NullMetadataStore does nothing, callers may wish to avoid work
   * (fast path) when the NullMetadataStore is in use.
   * @param ms The MetadataStore to test
   * @return true iff the MetadataStore is the null, or no-op, implementation.
   */
  public static boolean isNullMetadataStore(MetadataStore ms) {
    return (ms instanceof NullMetadataStore);
  }

  /**
   * Update MetadataStore to reflect creation of the given  directories.
   *
   * If an IOException is raised while trying to update the entry, this
   * operation catches the exception, swallows it and returns.
   *
   * @deprecated this is no longer called by {@code S3AFilesystem.innerMkDirs}.
   * See: HADOOP-15079 (January 2018).
   * It is currently retained because of its discussion in the method on
   * atomicity and in case we need to reinstate it or adapt the current
   * process of directory marker creation.
   * But it is not being tested and so may age with time...consider
   * deleting it in future if it's clear there's no need for it.
   * @param ms    MetadataStore to update.
   * @param dirs  null, or an ordered list of directories from leaf to root.
   *              E.g. if /a/ exists, and  mkdirs(/a/b/c/d) is called, this
   *              list will contain [/a/b/c/d, /a/b/c, /a/b].   /a/b/c/d is
   *              an empty, dir, and the other dirs only contain their child
   *              dir.
   * @param owner Hadoop user name.
   * @param authoritative Whether to mark new directories as authoritative.
   * @param timeProvider Time provider for testing.
   */
  @Deprecated
  @Retries.OnceExceptionsSwallowed
  public static void makeDirsOrdered(MetadataStore ms, List<Path> dirs,
      String owner, boolean authoritative, ITtlTimeProvider timeProvider) {
    if (dirs == null) {
      return;
    }

    /* We discussed atomicity of this implementation.
     * The concern is that multiple clients could race to write different
     * cached directories to the MetadataStore.  Two solutions are proposed:
     * 1. Move mkdirs() into MetadataStore interface and let implementations
     *    ensure they are atomic.
     * 2. Specify that the semantics of MetadataStore#putListStatus() is
     *    always additive,  That is, if MetadataStore has listStatus() state
     *    for /a/b that contains [/a/b/file0, /a/b/file1], and we then call
     *    putListStatus(/a/b -> [/a/b/file2, /a/b/file3], isAuthoritative=true),
     *    then we will end up with final state of
     *    [/a/b/file0, /a/b/file1, /a/b/file2, /a/b/file3], isAuthoritative =
     *    true
     */
    S3AFileStatus prevStatus = null;

    // Use new batched put to reduce round trips.
    List<PathMetadata> pathMetas = new ArrayList<>(dirs.size());

    try {
      // Iterate from leaf to root
      for (int i = 0; i < dirs.size(); i++) {
        boolean isLeaf = (prevStatus == null);
        Path f = dirs.get(i);
        assertQualified(f);
        S3AFileStatus status =
            createUploadFileStatus(f, true, 0, 0, owner, null, null);

        // We only need to put a DirListingMetadata if we are setting
        // authoritative bit
        DirListingMetadata dirMeta = null;
        if (authoritative) {
          Collection<PathMetadata> children;
          if (isLeaf) {
            children = DirListingMetadata.EMPTY_DIR;
          } else {
            children = new ArrayList<>(1);
            children.add(new PathMetadata(prevStatus));
          }
          dirMeta = new DirListingMetadata(f, children, authoritative);
          S3Guard.putWithTtl(ms, dirMeta, timeProvider);
        }

        pathMetas.add(new PathMetadata(status));
        prevStatus = status;
      }

      // Batched put
      S3Guard.putWithTtl(ms, pathMetas, timeProvider);
    } catch (IOException ioe) {
      LOG.error("MetadataStore#put() failure:", ioe);
    }
  }

  /**
   * Helper function that records the move of directory paths, adding
   * resulting metadata to the supplied lists.
   * Does not store in MetadataStore.
   * @param ms  MetadataStore, used to make this a no-op, when it is
   *            NullMetadataStore.
   * @param srcPaths stores the source path here
   * @param dstMetas stores destination metadata here
   * @param srcPath  source path to store
   * @param dstPath  destination path to store
   * @param owner file owner to use in created records
   */
  public static void addMoveDir(MetadataStore ms, Collection<Path> srcPaths,
      Collection<PathMetadata> dstMetas, Path srcPath, Path dstPath,
      String owner) {
    if (isNullMetadataStore(ms)) {
      return;
    }
    assertQualified(srcPath, dstPath);

    S3AFileStatus dstStatus = createUploadFileStatus(dstPath, true, 0,
        0, owner, null, null);
    addMoveStatus(srcPaths, dstMetas, srcPath, dstStatus);
  }

  /**
   * Like {@link #addMoveDir(MetadataStore, Collection, Collection, Path,
   * Path, String)} (), but for files.
   * @param ms  MetadataStore, used to make this a no-op, when it is
   *            NullMetadataStore.
   * @param srcPaths stores the source path here
   * @param dstMetas stores destination metadata here
   * @param srcPath  source path to store
   * @param dstPath  destination path to store
   * @param size length of file moved
   * @param blockSize  blocksize to associate with destination file
   * @param owner file owner to use in created records
   * @param eTag the s3 object eTag of file moved
   * @param versionId the s3 object versionId of file moved
   */
  public static void addMoveFile(MetadataStore ms, Collection<Path> srcPaths,
      Collection<PathMetadata> dstMetas, Path srcPath, Path dstPath,
      long size, long blockSize, String owner, String eTag, String versionId) {
    if (isNullMetadataStore(ms)) {
      return;
    }
    assertQualified(srcPath, dstPath);
    S3AFileStatus dstStatus = createUploadFileStatus(dstPath, false,
        size, blockSize, owner, eTag, versionId);
    addMoveStatus(srcPaths, dstMetas, srcPath, dstStatus);
  }

  /**
   * Helper method that records the move of all ancestors of a path.
   *
   * In S3A, an optimization is to delete unnecessary fake directory objects if
   * the directory is non-empty. In that case, for a nested child to move, S3A
   * is not listing and thus moving all its ancestors (up to source root). So we
   * take care of those inferred directories of this path explicitly.
   *
   * As {@link #addMoveFile} and {@link #addMoveDir}, this method adds resulting
   * metadata to the supplied lists. It does not store in MetadataStore.
   *
   * @param ms MetadataStore, no-op if it is NullMetadataStore
   * @param srcPaths stores the source path here
   * @param dstMetas stores destination metadata here
   * @param srcRoot source root up to which (exclusive) should we add ancestors
   * @param srcPath source path of the child to add ancestors
   * @param dstPath destination path of the child to add ancestors
   * @param owner Hadoop user name
   */
  public static void addMoveAncestors(MetadataStore ms,
      Collection<Path> srcPaths, Collection<PathMetadata> dstMetas,
      Path srcRoot, Path srcPath, Path dstPath, String owner) {
    if (isNullMetadataStore(ms)) {
      return;
    }

    assertQualified(srcRoot, srcPath, dstPath);

    if (srcPath.equals(srcRoot)) {
      LOG.debug("Skip moving ancestors of source root directory {}", srcRoot);
      return;
    }

    Path parentSrc = srcPath.getParent();
    Path parentDst = dstPath.getParent();
    while (parentSrc != null
        && !parentSrc.isRoot()
        && !parentSrc.equals(srcRoot)
        && !srcPaths.contains(parentSrc)) {
      LOG.debug("Renaming non-listed parent {} to {}", parentSrc, parentDst);
      S3Guard.addMoveDir(ms, srcPaths, dstMetas, parentSrc, parentDst, owner);
      parentSrc = parentSrc.getParent();
      parentDst = parentDst.getParent();
    }
  }

  public static void addAncestors(MetadataStore metadataStore,
      Path qualifiedPath, String username, ITtlTimeProvider timeProvider)
      throws IOException {
    Collection<PathMetadata> newDirs = new ArrayList<>();
    Path parent = qualifiedPath.getParent();
    while (!parent.isRoot()) {
      PathMetadata directory = metadataStore.get(parent);
      if (directory == null || directory.isDeleted()) {
        S3AFileStatus s3aStatus = new S3AFileStatus(Tristate.FALSE, parent, username);
        PathMetadata meta = new PathMetadata(s3aStatus, Tristate.FALSE, false);
        newDirs.add(meta);
      } else {
        break;
      }
      parent = parent.getParent();
    }
    S3Guard.putWithTtl(metadataStore, newDirs, timeProvider);
  }

  private static void addMoveStatus(Collection<Path> srcPaths,
      Collection<PathMetadata> dstMetas,
      Path srcPath,
      S3AFileStatus dstStatus) {
    srcPaths.add(srcPath);
    dstMetas.add(new PathMetadata(dstStatus));
  }

  /**
   * Assert that the path is qualified with a host and scheme.
   * @param p path to check
   * @throws NullPointerException if either argument does not hold
   */
  public static void assertQualified(Path p) {
    URI uri = p.toUri();
    // Paths must include bucket in case MetadataStore is shared between
    // multiple S3AFileSystem instances
    Preconditions.checkNotNull(uri.getHost(), "Null host in " + uri);

    // This should never fail, but is retained for completeness.
    Preconditions.checkNotNull(uri.getScheme(), "Null scheme in " + uri);
  }

  /**
   * Assert that all paths are valid.
   * @param paths path to check
   * @throws NullPointerException if either argument does not hold
   */
  public static void assertQualified(Path...paths) {
    for (Path path : paths) {
      assertQualified(path);
    }
  }

  /**
   * Runtime implementation for TTL Time Provider interface.
   */
  public static class TtlTimeProvider implements ITtlTimeProvider {
    private long authoritativeDirTtl;

    public TtlTimeProvider(long authoritativeDirTtl) {
      this.authoritativeDirTtl = authoritativeDirTtl;
    }

    public TtlTimeProvider(Configuration conf) {
      this.authoritativeDirTtl =
          conf.getTimeDuration(METADATASTORE_METADATA_TTL,
              DEFAULT_METADATASTORE_METADATA_TTL, TimeUnit.SECONDS);
    }

    @Override
    public long getNow() {
      return System.currentTimeMillis();
    }

    @Override public long getMetadataTtl() {
      return authoritativeDirTtl;
    }
  }

  public static void putWithTtl(MetadataStore ms, DirListingMetadata dirMeta,
      ITtlTimeProvider timeProvider)
      throws IOException {
    dirMeta.setLastUpdated(timeProvider.getNow());
    dirMeta.getListing()
        .forEach(pm -> pm.setLastUpdated(timeProvider.getNow()));
    ms.put(dirMeta);
  }

  public static void putWithTtl(MetadataStore ms, PathMetadata fileMeta,
      @Nullable ITtlTimeProvider timeProvider) throws IOException {
    if (timeProvider != null) {
      fileMeta.setLastUpdated(timeProvider.getNow());
    } else {
      LOG.debug("timeProvider is null, put {} without setting last_updated",
          fileMeta);
    }
    ms.put(fileMeta);
  }

  public static void putWithTtl(MetadataStore ms,
      Collection<PathMetadata> fileMetas,
      @Nullable ITtlTimeProvider timeProvider)
      throws IOException {
    if (timeProvider != null) {
      final long now = timeProvider.getNow();
      fileMetas.forEach(fileMeta -> fileMeta.setLastUpdated(now));
    } else {
      LOG.debug("timeProvider is null, put {} without setting last_updated",
          fileMetas);
    }
    ms.put(fileMetas);
  }

  public static PathMetadata getWithTtl(MetadataStore ms, Path path,
      @Nullable ITtlTimeProvider timeProvider) throws IOException {
    final PathMetadata pathMetadata = ms.get(path);
    // if timeProvider is null let's return with what the ms has
    if (timeProvider == null) {
      LOG.debug("timeProvider is null, returning pathMetadata as is");
      return pathMetadata;
    }

    long ttl = timeProvider.getMetadataTtl();

    if (pathMetadata != null) {
      // Special case: the pathmetadata's last updated is 0. This can happen
      // eg. with an old db using this implementation
      if (pathMetadata.getLastUpdated() == 0) {
        LOG.debug("PathMetadata TTL for {} is 0, so it will be returned as "
            + "not expired.");
        return pathMetadata;
      }

      if (!pathMetadata.isExpired(ttl, timeProvider.getNow())) {
        return pathMetadata;
      } else {
        LOG.debug("PathMetadata TTl for {} is expired in metadata store.",
            path);
        return null;
      }
    }

    return null;
  }

  public static DirListingMetadata listChildrenWithTtl(MetadataStore ms,
      Path path, @Nullable ITtlTimeProvider timeProvider)
      throws IOException {
    DirListingMetadata dlm = ms.listChildren(path);

    if (timeProvider == null) {
      LOG.debug("timeProvider is null, returning DirListingMetadata as is");
      return dlm;
    }

    long ttl = timeProvider.getMetadataTtl();

    if (dlm != null && dlm.isAuthoritative()
        && dlm.isExpired(ttl, timeProvider.getNow())) {
      dlm.setAuthoritative(false);
    }
    return dlm;
  }

}
