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

package org.apache.hadoop.fs.s3a;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.s3a.s3guard.NullMetadataStore;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.impl.ChangeDetectionPolicy;
import org.apache.hadoop.fs.s3a.impl.ChangeDetectionPolicy.Source;
import org.apache.hadoop.fs.s3a.s3guard.DirListingMetadata;
import org.apache.hadoop.fs.s3a.s3guard.MetadataStore;
import org.apache.hadoop.fs.s3a.s3guard.PathMetadata;
import org.apache.hadoop.fs.s3a.s3guard.ITtlTimeProvider;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.RemoteIterator;

import static org.apache.hadoop.fs.contract.ContractTestUtils.touch;
import static org.apache.hadoop.fs.s3a.S3ATestUtils.removeBaseAndBucketOverrides;
import static org.apache.hadoop.test.LambdaTestUtils.eventually;
import static org.junit.Assume.assumeTrue;
import static org.apache.hadoop.fs.contract.ContractTestUtils.readBytesToString;
import static org.apache.hadoop.fs.contract.ContractTestUtils.writeTextFile;
import static org.apache.hadoop.fs.s3a.Constants.METADATASTORE_AUTHORITATIVE;
import static org.apache.hadoop.fs.s3a.Constants.S3_METADATA_STORE_IMPL;
import static org.apache.hadoop.fs.s3a.S3ATestUtils.metadataStorePersistsAuthoritativeBit;
import static org.apache.hadoop.test.LambdaTestUtils.intercept;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 *
 * This integration test is for documenting and defining how S3Guard should
 * behave in case of out-of-band (OOB) operations.
 * <pre>
 * The behavior is the following in case of S3AFileSystem.getFileStatus:
 * A client with S3Guard
 * B client without S3Guard (Directly to S3)
 *
 * * OOB OVERWRITE, authoritative mode:
 * ** A client creates F1 file
 * ** B client overwrites F1 file with F2 (Same, or different file size)
 * ** A client's getFileStatus returns F1 metadata
 *
 * * OOB OVERWRITE, NOT authoritative mode:
 * ** A client creates F1 file
 * ** B client overwrites F1 file with F2 (Same, or different file size)
 * ** A client's getFileStatus returns F2 metadata. In not authoritative
 * mode we check S3 for the file. If the modification time of the file in S3
 * is greater than in S3Guard, we can safely return the S3 file metadata and
 * update the cache.
 *
 * * OOB DELETE, authoritative mode:
 * ** A client creates F file
 * ** B client deletes F file
 * ** A client's getFileStatus returns that the file is still there
 *
 * * OOB DELETE, NOT authoritative mode:
 * ** A client creates F file
 * ** B client deletes F file
 * ** A client's getFileStatus returns that the file is still there
 *
 * As you can see, authoritative and NOT authoritative mode behaves the same
 * at OOB DELETE case.
 *
 * The behavior is the following in case of S3AFileSystem.listStatus:
 * * File status in metadata store gets updated during the listing (in
 * S3Guard.dirListingUnion) the same way as in getFileStatus.
 * </pre>
 */
@RunWith(Parameterized.class)
public class ITestS3GuardOutOfBandOperations extends AbstractS3ATestBase {

  public static final int TIMESTAMP_SLEEP = 2000;

  public static final int STABILIZATION_TIME = 20_000;

  public static final int PROBE_INTERVAL_MILLIS = 500;

  private S3AFileSystem guardedFs;
  private S3AFileSystem rawFS;

  private MetadataStore realMs;

  /**
   * Is the "real" FS Authoritative.
   */
  private final boolean authoritative;

  /**
   * Test array for parameterized test runs.
   * @return a list of parameter tuples.
   */
  @Parameterized.Parameters
  public static Collection<Object[]> params() {
    return Arrays.asList(new Object[][]{
        {true}, {false}
    });
  }

  public ITestS3GuardOutOfBandOperations(final boolean authoritative) {
    this.authoritative = authoritative;
  }

  /**
   * By changing the method name, the thread name is changed and
   * so you can see in the logs which mode is being tested.
   * @return a string to use for the thread namer.
   */
  @Override
  protected String getMethodName() {
    return super.getMethodName() +
        (authoritative ? "-auth" : "-nonauth");
  }

  @Before
  public void setup() throws Exception {
    super.setup();
    S3AFileSystem fs = getFileSystem();
    // These test will fail if no ms
    assumeTrue("FS needs to have a metadatastore.",
        fs.hasMetadataStore());
    assumeTrue("Metadatastore should persist authoritative bit",
        metadataStorePersistsAuthoritativeBit(fs.getMetadataStore()));

    // This test setup shares a single metadata store across instances,
    // so that test runs with a local FS work.
    // but this needs to be addressed in teardown, where the guarded fs
    // needs to be detached from the metadata store before it is closed,
    realMs = fs.getMetadataStore();
    // now we create a new FS with the auth parameter
    guardedFs = createGuardedFS(authoritative);
    assertTrue("No S3Guard store for " + guardedFs,
        guardedFs.hasMetadataStore());
    assertEquals("Authoritative status in " + guardedFs,
        authoritative, guardedFs.hasAuthoritativeMetadataStore());

    // create raw fs without s3guard
    rawFS = createUnguardedFS();
    assertFalse("Raw FS still has S3Guard " + rawFS,
        rawFS.hasMetadataStore());
  }

  @Override
  public void teardown() throws Exception {
    if (guardedFs != null) {
      // detach from the (shared) metadata store.
      guardedFs.setMetadataStore(new NullMetadataStore());
      // and only then close it.
      IOUtils.cleanupWithLogger(LOG, guardedFs);
    }
    IOUtils.cleanupWithLogger(LOG, rawFS);
    super.teardown();
  }

  /**
   * Create a new FS which is the same config as the test FS, except
   * that it is guarded with the specific authoritative mode.
   * @param authoritativeMode mode of the new FS's metastore
   * @return the new FS
   */
  private S3AFileSystem createGuardedFS(boolean authoritativeMode)
      throws Exception {
    S3AFileSystem testFS = getFileSystem();
    Configuration config = new Configuration(testFS.getConf());
    URI uri = testFS.getUri();

    removeBaseAndBucketOverrides(uri.getHost(), config,
        METADATASTORE_AUTHORITATIVE);
    config.setBoolean(METADATASTORE_AUTHORITATIVE, authoritativeMode);
    final S3AFileSystem gFs = createFS(uri, config);
    // set back the same metadata store instance
    gFs.setMetadataStore(realMs);
    return gFs;
  }

  /**
   * Create a test filesystem which is always unguarded.
   * This filesystem MUST be closed in test teardown.
   * @return the new FS
   */
  private S3AFileSystem createUnguardedFS() throws Exception {
    S3AFileSystem testFS = getFileSystem();
    Configuration config = new Configuration(testFS.getConf());
    URI uri = testFS.getUri();

    removeBaseAndBucketOverrides(uri.getHost(), config,
        S3_METADATA_STORE_IMPL);
    removeBaseAndBucketOverrides(uri.getHost(), config,
        METADATASTORE_AUTHORITATIVE);
    return createFS(uri, config);
  }

  /**
   * Create and initialize a new filesystem.
   * This filesystem MUST be closed in test teardown.
   * @param uri FS URI
   * @param config config.
   * @return new instance
   * @throws IOException failure
   */
  private S3AFileSystem createFS(final URI uri, final Configuration config)
      throws IOException {
    S3AFileSystem fs2 = new S3AFileSystem();
    fs2.initialize(uri, config);
    return fs2;
  }

  @Test
  public void testSameLengthOverwrite() throws Exception {
    String firstText = "hello, world!";
    String secondText = "HELLO, WORLD!";
    overwriteFile(firstText, secondText);
  }

  @Test
  public void testLongerLengthOverwrite() throws Exception {
    String firstText = "Hello, World!";
    String secondText = firstText + " " + firstText;
    overwriteFile(firstText, secondText);
  }

  @Test
  public void testOutOfBandDeletes() throws Exception {
    ChangeDetectionPolicy changeDetectionPolicy =
        ((S3AFileSystem) getFileSystem()).getChangeDetectionPolicy();
    Assume.assumeFalse("FNF not expected when using a bucket with"
            + " object versioning",
        changeDetectionPolicy.getSource() == Source.VersionId);

    Path testFileName = path("OutOfBandDelete-" + UUID.randomUUID());
    outOfBandDeletes(testFileName, authoritative);
  }

  @Test
  public void testListingSameLengthOverwrite() throws Exception {
    overwriteFileInListing("THE TEXT", "the text");
  }

  @Test
  public void testListingLongerLengthOverwrite() throws Exception {
    overwriteFileInListing("THE TEXT", "THE LONGER TEXT");
  }

  @Test
  public void testListingDelete() throws Exception {
    deleteFileInListing();
  }

  /**
   * Tests that tombstone expiry is implemented, so if a file is created raw
   * while the tombstone exist in ms for with the same name then S3Guard will
   * check S3 for the file.
   *
   * Seq: create guarded; delete guarded; create raw (same path); read guarded;
   * This will fail if no tombstone expiry is set
   *
   * @throws Exception
   */
  @Test
  public void testTombstoneExpiryGuardedDeleteRawCreate() throws Exception {
    boolean allowAuthoritative = authoritative;
    Path testFilePath = path("TEGDRC-" + UUID.randomUUID());
    LOG.info("Allow authoritative param: {}",  allowAuthoritative);
    String originalText = "some test";
    String newText = "the new originalText for test";

    final ITtlTimeProvider originalTimeProvider =
        guardedFs.getTtlTimeProvider();
    try {
      final AtomicLong now = new AtomicLong(1);
      final AtomicLong metadataTtl = new AtomicLong(1);

      // SET TTL TIME PROVIDER FOR TESTING
      ITtlTimeProvider testTimeProvider =
          new ITtlTimeProvider() {
            @Override public long getNow() {
              return now.get();
            }

            @Override public long getMetadataTtl() {
              return metadataTtl.get();
            }
          };
      guardedFs.setTtlTimeProvider(testTimeProvider);

      // CREATE GUARDED
      createAndAwaitFs(guardedFs, testFilePath, originalText);

      // DELETE GUARDED
      deleteGuardedTombstoned(guardedFs, testFilePath, now);

      // CREATE RAW
      createAndAwaitFs(rawFS, testFilePath, newText);

      // CHECK LISTING - THE FILE SHOULD NOT BE THERE, EVEN IF IT'S CREATED RAW
      checkListingDoesNotContainPath(guardedFs, testFilePath);

      // CHANGE TTL SO ENTRY (& TOMBSTONE METADATA) WILL EXPIRE
      long willExpire = now.get() + metadataTtl.get() + 1L;
      now.set(willExpire);
      LOG.info("willExpire: {}, ttlNow: {}; ttlTTL: {}", willExpire,
          testTimeProvider.getNow(), testTimeProvider.getMetadataTtl());

      // READ GUARDED
      String newRead = readBytesToString(guardedFs, testFilePath,
          newText.length());

      // CHECK LISTING - THE FILE SHOULD BE THERE, TOMBSTONE EXPIRED
      checkListingContainsPath(guardedFs, testFilePath);

      // we can assert that the originalText is the new one, which created raw
      LOG.info("Old: {}, New: {}, Read: {}", originalText, newText, newRead);
      assertEquals("The text should be modified with a new.", newText,
          newRead);
    } finally {
      guardedFs.delete(testFilePath, true);
      guardedFs.setTtlTimeProvider(originalTimeProvider);
    }
  }

  private void createAndAwaitFs(S3AFileSystem fs, Path testFilePath,
      String text) throws Exception {
    writeTextFile(fs, testFilePath, text, true);
    final FileStatus newStatus = awaitFileStatus(fs, testFilePath);
    assertNotNull("Newly created file status should not be null.", newStatus);
  }

  private void deleteGuardedTombstoned(S3AFileSystem guardedFs,
      Path testFilePath, AtomicLong now) throws Exception {
    guardedFs.delete(testFilePath, true);

    final PathMetadata metadata =
        guardedFs.getMetadataStore().get(testFilePath);
    assertNotNull("Created file metadata should not be null in ms",
        metadata);
    assertEquals("Created file metadata last_updated should equal with "
            + "mocked now", now.get(), metadata.getLastUpdated());

    intercept(FileNotFoundException.class, testFilePath.toString(),
        "This file should throw FNFE when reading through "
            + "the guarded fs, and the metadatastore tombstoned the file.",
        () -> guardedFs.getFileStatus(testFilePath));
  }

  /**
   * createNonRecursive must fail if the parent directory has been deleted,
   * and succeed if the tombstone has expired and the directory has been
   * created out of band.
   */
  @Test
  public void testCreateNonRecursiveFailsIfParentDeleted() throws Exception {
    LOG.info("Authoritative mode: {}", authoritative);

    String dirToDelete = methodName + UUID.randomUUID().toString();
    String fileToTry = dirToDelete + "/theFileToTry";

    final Path dirPath = path(dirToDelete);
    final Path filePath = path(fileToTry);

    // Create a directory with
    ITtlTimeProvider mockTimeProvider = mock(ITtlTimeProvider.class);
    ITtlTimeProvider originalTimeProvider = guardedFs.getTtlTimeProvider();

    try {
      guardedFs.setTtlTimeProvider(mockTimeProvider);
      when(mockTimeProvider.getNow()).thenReturn(100L);
      when(mockTimeProvider.getMetadataTtl()).thenReturn(5L);

      // CREATE DIRECTORY
      guardedFs.mkdirs(dirPath);

      // DELETE DIRECTORY
      guardedFs.delete(dirPath, true);

      // WRITE TO DELETED DIRECTORY - FAIL
      intercept(FileNotFoundException.class,
          dirToDelete,
          "createNonRecursive must fail if the parent directory has been deleted.",
          () -> createNonRecursive(guardedFs, filePath));

      // CREATE THE DIRECTORY RAW
      rawFS.mkdirs(dirPath);
      awaitFileStatus(rawFS, dirPath);

      // SET TIME SO METADATA EXPIRES
      when(mockTimeProvider.getNow()).thenReturn(110L);

      // WRITE TO DELETED DIRECTORY - SUCCESS
      createNonRecursive(guardedFs, filePath);

    } finally {
      guardedFs.delete(filePath, true);
      guardedFs.delete(dirPath, true);
      guardedFs.setTtlTimeProvider(originalTimeProvider);
    }
  }

  /**
   * When lastUpdated = 0 the entry should not expire. This is a special case
   * eg. for old metadata entries
   */
  @Test
  public void testLastUpdatedZeroWontExpire() throws Exception {
    LOG.info("Authoritative mode: {}", authoritative);

    String testFile = methodName + UUID.randomUUID().toString() +
        "/theFileToTry";

    long ttl = 10L;
    final Path filePath = path(testFile);

    // Create a directory with
    ITtlTimeProvider mockTimeProvider = mock(ITtlTimeProvider.class);
    ITtlTimeProvider originalTimeProvider = guardedFs.getTtlTimeProvider();

    try {
      guardedFs.setTtlTimeProvider(mockTimeProvider);
      when(mockTimeProvider.getMetadataTtl()).thenReturn(ttl);

      // create a file while the NOW is 0, so it will set 0 as the last_upadted
      when(mockTimeProvider.getNow()).thenReturn(0L);
      touch(guardedFs, filePath);
      deleteFile(guardedFs, filePath);

      final PathMetadata pathMetadata =
          guardedFs.getMetadataStore().get(filePath);
      assertNotNull("pathMetadata should not be null after deleting with "
          + "tombstones", pathMetadata);
      assertEquals("pathMetadata lastUpdated field should be 0", 0,
          pathMetadata.getLastUpdated());

      // set the time, so the metadata would expire
      when(mockTimeProvider.getNow()).thenReturn(2*ttl);
      intercept(FileNotFoundException.class, filePath.toString(),
          "This file should throw FNFE when reading through "
              + "the guarded fs, and the metadatastore tombstoned the file. "
              + "The tombstone won't expire if lastUpdated is set to 0.",
          () -> guardedFs.getFileStatus(filePath));

    } finally {
      guardedFs.delete(filePath, true);
      guardedFs.setTtlTimeProvider(originalTimeProvider);
    }
  }

  private void checkListingDoesNotContainPath(S3AFileSystem fs, Path filePath)
      throws IOException {
    final RemoteIterator<LocatedFileStatus> listIter =
        fs.listFiles(filePath.getParent(), false);
    while (listIter.hasNext()) {
      final LocatedFileStatus lfs = listIter.next();
      assertNotEquals("The tombstone has not been expired, so must not be"
          + " listed.", filePath, lfs.getPath());
    }
    LOG.info("{}; file omitted from listFiles listing as expected.", filePath);

    final FileStatus[] fileStatuses = fs.listStatus(filePath.getParent());
    for (FileStatus fileStatus : fileStatuses) {
      assertNotEquals("The tombstone has not been expired, so must not be"
          + " listed.", filePath, fileStatus.getPath());
    }
    LOG.info("{}; file omitted from listStatus as expected.", filePath);
  }

  private void checkListingContainsPath(S3AFileSystem fs, Path filePath)
      throws IOException {
    final RemoteIterator<LocatedFileStatus> listIter =
        fs.listFiles(filePath.getParent(), false);

    boolean lfsHit = false;
    while (listIter.hasNext()) {
      final LocatedFileStatus lfs = listIter.next();
      if (lfs.getPath().equals(filePath)) {
        lfsHit = true;
        LOG.info("{}; file found in listFiles as expected.", filePath);
        break;
      }
    }
    assertTrue("The file should be listed in fs.listFiles: ", lfsHit);

    boolean lsHit = false;
    final FileStatus[] fileStatuses = fs.listStatus(filePath.getParent());
    for (FileStatus fileStatus : fileStatuses) {
      if (fileStatus.getPath().equals(filePath)) {
        lsHit = true;
        LOG.info("{}; file found in listStatus as expected.", filePath);
        break;
      }
    }
    assertTrue("The file should be listed in fs.listStatus: ", lsHit);
  }

  /**
   * Perform an out-of-band delete.
   * @param testFilePath filename
   * @param allowAuthoritative  is the store authoritative
   * @throws Exception failure
   */
  private void outOfBandDeletes(
      final Path testFilePath,
      final boolean allowAuthoritative)
      throws Exception {
    try {
      // Create initial file
      String text = "Hello, World!";
      writeTextFile(guardedFs, testFilePath, text, true);
      awaitFileStatus(rawFS, testFilePath);

      // Delete the file without S3Guard (raw)
      deleteFile(rawFS, testFilePath);

      // The check is the same if s3guard is authoritative and if it's not
      // it should be in the ms
      FileStatus status = guardedFs.getFileStatus(testFilePath);
      LOG.info("Authoritative: {} status path: {}",
          allowAuthoritative, status.getPath());
      expectExceptionWhenReading(testFilePath, text);
      expectExceptionWhenReadingOpenFileAPI(testFilePath, text);
    } finally {
      guardedFs.delete(testFilePath, true);
    }
  }

  /**
   * Overwrite a file out of band.
   * @param firstText first text
   * @param secondText second text
   * @throws Exception failure
   */
  private void overwriteFile(String firstText, String secondText)
      throws Exception {
    boolean allowAuthoritative = authoritative;
    Path testFilePath = path("OverwriteFileTest-" + UUID.randomUUID());
    LOG.info("Allow authoritative param: {}",  allowAuthoritative);
    try {
      // Create initial file
      writeTextFile(
          guardedFs, testFilePath, firstText, true);
      // and cache the value for later
      final FileStatus origStatus = awaitFileStatus(rawFS, testFilePath);
      waitForDifferentTimestamps();
      // Overwrite the file without S3Guard
      writeTextFile(
          rawFS, testFilePath, secondText, true);

      // Read the file and verify the data
      eventually(STABILIZATION_TIME, PROBE_INTERVAL_MILLIS,
          () -> {
            FileStatus rawFileStatus = rawFS.getFileStatus(testFilePath);
            final FileStatus guardedFileStatus =
                guardedFs.getFileStatus(testFilePath);
            verifyFileStatusAsExpected(firstText, secondText,
                allowAuthoritative,
                origStatus,
                rawFileStatus,
                guardedFileStatus);
          });
    } finally {
      guardedFs.delete(testFilePath, true);
    }
  }

  /**
   * Assert that an array has a given size; in failure the full string values
   * of the array will be included, one per line.
   * @param message message for errors.
   * @param expected expected length.
   * @param array the array to probe
   */
  private <T> void assertArraySize(
      final String message,
      final int expected,
      final T[] array) {
    if (expected != array.length) {
      // condition is not met, build an error which includes all the entries
      String listing = Arrays.stream(array)
          .map(Object::toString)
          .collect(Collectors.joining("\n"));
      fail(message + ": expected " + expected + " elements but found "
          + array.length
          + "\n" + listing);
    }
  }

  /**
   * Overwrite a file, verify that the text is different as is the timestamp.
   * There are some pauses in the test to ensure that timestamps are different.
   * @param firstText first text to write
   * @param secondText second text to write
   */
  private void overwriteFileInListing(String firstText, String secondText)
      throws Exception {
    boolean allowAuthoritative = authoritative;

    LOG.info("Authoritative mode enabled: {}", allowAuthoritative);
    String rUUID = UUID.randomUUID().toString();
    String testDir = "dir-" + rUUID + "/";
    String testFile = testDir + "file-1-" + rUUID;
    Path testDirPath = path(testDir);
    Path testFilePath = guardedFs.qualify(path(testFile));

    try {
      // Create initial statusIterator with guarded ms
      writeTextFile(guardedFs, testFilePath, firstText, true);
      // and cache the value for later
      final FileStatus origStatus = awaitFileStatus(rawFS, testFilePath);

      // Do a listing to cache the lists. Should be authoritative if it's set.
      final FileStatus[] origList = guardedFs.listStatus(testDirPath);
      assertArraySize("Added one file to the new dir, so the number of "
              + "files in the dir should be one.", 1, origList);
      final DirListingMetadata dirListingMetadata =
          realMs.listChildren(guardedFs.qualify(testDirPath));
      assertListingAuthority(allowAuthoritative, dirListingMetadata);

      // a brief pause to guarantee timestamps are different.
      waitForDifferentTimestamps();

      // Update file with second text without S3Guard (raw)
      deleteFile(rawFS, testFilePath);

      // write to the test path with the second text
      writeTextFile(rawFS, testFilePath, secondText, true);
      // and await it becoming visible again.
      final FileStatus rawFileStatus = awaitFileStatus(rawFS, testFilePath);

      // check listing in guarded store.
      final FileStatus[] modList = guardedFs.listStatus(testDirPath);
      assertArraySize("Added one file to the new dir then modified it, "
          + "so the number of files in the dir should be one.", 1,
          modList);
      assertEquals("The only file path in the directory listing should be "
              + "equal to the testFilePath.", testFilePath,
          modList[0].getPath());

      // Read the file and verify the data
      eventually(STABILIZATION_TIME, PROBE_INTERVAL_MILLIS,
          () -> {
            final FileStatus guardedFileStatus =
                guardedFs.getFileStatus(testFilePath);
            verifyFileStatusAsExpected(firstText, secondText,
                allowAuthoritative,
                origStatus,
                rawFileStatus,
                guardedFileStatus);
          });
    } finally {
      guardedFs.delete(testDirPath, true);
    }
  }

  private void deleteFile(final S3AFileSystem fs, final Path testFilePath)
      throws Exception {
    fs.delete(testFilePath, true);
    awaitDeletedFileDisappearance(fs, testFilePath);
  }


  /**
   * Verify that the file status of a file which has been overwritten
   * is as expected, throwing informative exceptions if not.
   * @param firstText text of the first write
   * @param secondText text of the second
   * @param allowAuthoritative is S3Guard being authoritative
   * @param origStatus filestatus of the first written file
   * @param rawFileStatus status of the updated file from the raw FS
   * @param guardedFileStatus status of the updated file from the guarded FS
   */
  private void verifyFileStatusAsExpected(final String firstText,
      final String secondText,
      final boolean allowAuthoritative,
      final FileStatus origStatus,
      final FileStatus rawFileStatus,
      final FileStatus guardedFileStatus) {
    String stats = "\nRaw: " + rawFileStatus.toString() +
        "\nGuarded: " + guardedFileStatus.toString();
    if (firstText.length() != secondText.length()) {
      // the file lengths are different, so compare that first.
      // it's not going to be brittle to timestamps, and easy to understand
      // when there is an error.

      // check the file length in the raw FS To verify that status is actually
      // stabilized w.r.t the last write.
      long expectedLength = secondText.length();
      assertEquals("Length of raw file status did not match the updated text "
              + rawFileStatus,
          expectedLength, rawFileStatus.getLen());
      // now compare the lengths of the the raw and guarded files
      long guardedLength = guardedFileStatus.getLen();
      if (allowAuthoritative) {
        // expect the length to be out of sync
        assertNotEquals(
            "File length in authoritative table with " + stats,
            expectedLength, guardedLength);
      } else {
        assertEquals(
            "File length in authoritative table with " + stats,
            expectedLength, guardedLength);
      }
    }
    // Next: modification time.
    long rawModTime = rawFileStatus.getModificationTime();
    long guardedModTime = guardedFileStatus.getModificationTime();
    assertNotEquals(
        "Updated file still has original timestamp\n"
            + " original " + origStatus + stats,
        origStatus.getModificationTime(), rawModTime);
    if (allowAuthoritative) {
      // If authoritative is allowed metadata is not updated, so mod_time
      // won't match
      assertNotEquals("Authoritative is enabled, so metadata is not "
              + "updated in ms, so mod_time won't match. Expecting "
              + "different values for raw and guarded filestatus."
              + stats,
          rawModTime,
          guardedModTime);
    } else {
      // If authoritative is not enabled metadata is updated, mod_time
      // will match
      assertEquals("Authoritative is disabled, so metadata is"
              + " updated in ms, so mod_time must match. Expecting "
              + " same values for raw and guarded filestatus."
              + stats,
          rawModTime,
          guardedModTime);
    }
  }

  /**
   * A brief pause to guarantee timestamps are different.
   * This doesn't have to be as long as a stabilization delay.
   */
  private void waitForDifferentTimestamps() throws InterruptedException {
    Thread.sleep(TIMESTAMP_SLEEP);
  }

  /**
   * Assert that a listing has the specific authority.
   * @param expectAuthoritative expect authority bit of listing
   * @param dirListingMetadata listing to check
   */
  private void assertListingAuthority(final boolean expectAuthoritative,
      final DirListingMetadata dirListingMetadata) {
    if (expectAuthoritative) {
      assertTrue("DirListingMeta should be authoritative if authoritative "
              + "mode is enabled.",
          dirListingMetadata.isAuthoritative());
    } else {
      assertFalse("DirListingMeta should not be authoritative if "
              + "authoritative mode is disabled.",
          dirListingMetadata.isAuthoritative());
    }
  }

  /**
   * Delete a file and use listStatus to build up the S3Guard cache.
   */
  private void deleteFileInListing()
      throws Exception {

    boolean allowAuthoritative = authoritative;
    LOG.info("Authoritative mode enabled: {}", allowAuthoritative);
    String rUUID = UUID.randomUUID().toString();
    String testDir = "dir-" + rUUID + "/";
    String testFile = testDir + "file-1-" + rUUID;
    Path testDirPath = path(testDir);
    Path testFilePath = guardedFs.qualify(path(testFile));
    String text = "Some random text";

    try {
      // Create initial statusIterator with real ms
      writeTextFile(
          guardedFs, testFilePath, text, true);
      awaitFileStatus(rawFS, testFilePath);

      // Do a listing to cache the lists. Should be authoritative if it's set.
      final FileStatus[] origList = guardedFs.listStatus(testDirPath);
      assertEquals("Added one file to the new dir, so the number of "
          + "files in the dir should be one.", 1, origList.length);
      final DirListingMetadata dirListingMetadata =
          realMs.listChildren(guardedFs.qualify(testDirPath));
      assertListingAuthority(allowAuthoritative, dirListingMetadata);

      // Delete the file without S3Guard (raw)
      deleteFile(rawFS, testFilePath);

      // File status will be still readable from s3guard
      FileStatus status = guardedFs.getFileStatus(testFilePath);
      LOG.info("authoritative: {} status: {}", allowAuthoritative, status);
      expectExceptionWhenReading(testFilePath, text);
      expectExceptionWhenReadingOpenFileAPI(testFilePath, text);
    } finally {
      guardedFs.delete(testDirPath, true);
    }
  }

  /**
   * We expect the read to fail with an FNFE: open will be happy.
   * @param testFilePath path of the test file
   * @param text the context in the file.
   * @throws Exception failure other than the FNFE
   */
  private void expectExceptionWhenReading(Path testFilePath, String text)
      throws Exception {
    try (FSDataInputStream in = guardedFs.open(testFilePath)) {
      intercept(FileNotFoundException.class, () -> {
        byte[] bytes = new byte[text.length()];
        return in.read(bytes, 0, bytes.length);
      });
    }
  }

  /**
   * We expect the read to fail with an FNFE: open will be happy.
   * @param testFilePath path of the test file
   * @param text the context in the file.
   * @throws Exception failure other than the FNFE
   */
  private void expectExceptionWhenReadingOpenFileAPI(
      Path testFilePath, String text)
      throws Exception {
    try (
        FSDataInputStream in = guardedFs.openFile(testFilePath).build().get()
    ) {
      intercept(FileNotFoundException.class, () -> {
        byte[] bytes = new byte[text.length()];
        return in.read(bytes, 0, bytes.length);
      });
    }
  }

  /**
   * Wait for a deleted file to no longer be visible.
   * @param fs filesystem
   * @param testFilePath path to query
   * @throws Exception failure
   */
  private void awaitDeletedFileDisappearance(final S3AFileSystem fs,
      final Path testFilePath) throws Exception {
    eventually(
        STABILIZATION_TIME, PROBE_INTERVAL_MILLIS,
        () -> intercept(FileNotFoundException.class,
            () -> fs.getFileStatus(testFilePath)));
  }

  /**
   * Wait for a file to be visible.
   * @param fs filesystem
   * @param testFilePath path to query
   * @return the file status.
   * @throws Exception failure
   */
  private FileStatus awaitFileStatus(S3AFileSystem fs,
      final Path testFilePath)
      throws Exception {
    return eventually(
        STABILIZATION_TIME, PROBE_INTERVAL_MILLIS,
        () -> fs.getFileStatus(testFilePath));
  }

  private FSDataOutputStream createNonRecursive(FileSystem fs, Path path)
      throws Exception {
    return fs
        .createNonRecursive(path, false, 4096, (short) 3, (short) 4096, null);
  }

}
