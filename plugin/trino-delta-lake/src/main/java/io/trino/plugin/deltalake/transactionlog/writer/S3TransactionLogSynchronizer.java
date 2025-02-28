/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.deltalake.transactionlog.writer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;
import io.trino.hdfs.HdfsContext;
import io.trino.hdfs.HdfsEnvironment;
import io.trino.spi.connector.ConnectorSession;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;

import javax.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.Objects.requireNonNull;
import static org.apache.parquet.Preconditions.checkState;

public class S3TransactionLogSynchronizer
        implements TransactionLogSynchronizer
{
    public static final Logger LOG = Logger.get(S3TransactionLogSynchronizer.class);

    // TODO: add refreshing of log expiration time (https://github.com/trinodb/trino/issues/12008)
    private static final Duration EXPIRATION_DURATION = Duration.of(5, MINUTES);
    private static final String LOCK_DIRECTORY = "_sb_lock";
    private static final String LOCK_INFIX = "sb-lock_";
    private static final Pattern LOCK_FILENAME_PATTERN = Pattern.compile("(.*)\\." + LOCK_INFIX + ".*");

    private final HdfsEnvironment hdfsEnvironment;
    private final JsonCodec<LockFileContents> lockFileContentsJsonCodec;

    @Inject
    public S3TransactionLogSynchronizer(HdfsEnvironment hdfsEnvironment, JsonCodec<LockFileContents> lockFileContentesCodec)
    {
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.lockFileContentsJsonCodec = requireNonNull(lockFileContentesCodec, "lockFileContentesCodec is null");
    }

    @Override
    public boolean isUnsafe()
    {
        return true;
    }

    @Override
    public void write(ConnectorSession session, String clusterId, Path newLogEntryPath, byte[] entryContents)
    {
        FileSystem fileSystem = getFileSystem(session, newLogEntryPath);
        Path locksDirectory = new Path(newLogEntryPath.getParent(), LOCK_DIRECTORY);
        String newEntryFilename = newLogEntryPath.getName();
        Optional<LockInfo> myLockInfo = Optional.empty();

        try {
            if (fileSystem.exists(newLogEntryPath)) {
                throw new TransactionConflictException(newLogEntryPath + " already exists");
            }

            List<LockInfo> lockInfos = listLockInfos(fileSystem, locksDirectory);

            Optional<LockInfo> currentLock = Optional.empty();
            for (LockInfo lockInfo : lockInfos) {
                if (lockInfo.getExpirationTime().isBefore(Instant.now())) {
                    deleteLock(fileSystem, locksDirectory, lockInfo);
                }
                else {
                    if (lockInfo.getEntryFilename().equals(newEntryFilename)) {
                        if (currentLock.isPresent()) {
                            throw new IllegalStateException(format(
                                    "Multiple live locks found for: %s; lock1: %s; lock2: %s",
                                    newLogEntryPath,
                                    currentLock.get().getLockFilename(),
                                    lockInfo.getLockFilename()));
                        }
                        currentLock = Optional.of(lockInfo);
                    }
                }
            }

            currentLock.ifPresent(lock -> {
                throw new TransactionConflictException(format(
                        "Transaction log locked(1); lockingCluster=%s; lockingQuery=%s; expires=%s",
                        lock.getClusterId(),
                        lock.getOwningQuery(),
                        lock.getExpirationTime()));
            });

            myLockInfo = Optional.of(writeNewLockInfo(fileSystem, locksDirectory, newEntryFilename, clusterId, session.getQueryId()));

            // final check if our lock file is only one
            lockInfos = listLockInfos(fileSystem, locksDirectory);
            String myLockFilename = myLockInfo.get().getLockFilename();
            currentLock = lockInfos.stream()
                    .filter(lockInfo -> lockInfo.getEntryFilename().equals(newEntryFilename))
                    .filter(lockInfo -> !lockInfo.getLockFilename().equals(myLockFilename))
                    .findFirst();

            if (currentLock.isPresent()) {
                throw new TransactionConflictException(format(
                        "Transaction log locked(2); lockingCluster=%s; lockingQuery=%s; expires=%s",
                        currentLock.get().getClusterId(),
                        currentLock.get().getOwningQuery(),
                        currentLock.get().getExpirationTime()));
            }

            // extra check if target file did not appear concurrently; e.g due to conflict with TL writer which uses different synchronization mechanism (like DB)
            checkState(!fileSystem.exists(newLogEntryPath), format("Target file %s was created during locking", newLogEntryPath));

            // write transaction log entry
            try (OutputStream outputStream = fileSystem.create(newLogEntryPath, false)) {
                outputStream.write(entryContents);
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException("Internal error while writing " + newLogEntryPath, e);
        }
        finally {
            if (myLockInfo.isPresent()) {
                try {
                    deleteLock(fileSystem, locksDirectory, myLockInfo.get());
                }
                catch (IOException e) {
                    // Transaction already committed here; we should not throw.
                    LOG.warn(e, "Could not delete lockfile %s", myLockInfo.get().lockFilename);
                }
            }
        }
    }

    private FileSystem getFileSystem(ConnectorSession session, Path newLogEntryPath)
    {
        try {
            return hdfsEnvironment.getFileSystem(new HdfsContext(session), newLogEntryPath);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private LockInfo writeNewLockInfo(FileSystem fileSystem, Path lockDirectory, String logEntryFilename, String clusterId, String queryId)
            throws IOException
    {
        String lockFilename = logEntryFilename + "." + LOCK_INFIX + queryId;
        Instant expiration = Instant.now().plus(EXPIRATION_DURATION);
        LockFileContents contents = new LockFileContents(clusterId, queryId, expiration.toEpochMilli());
        Path lockPath = new Path(lockDirectory, lockFilename);
        byte[] contentsBytes = lockFileContentsJsonCodec.toJsonBytes(contents);
        try (FSDataOutputStream outputStream = fileSystem.create(lockPath, false)) {
            outputStream.write(contentsBytes);
        }
        return new LockInfo(lockFilename, contents);
    }

    private void deleteLock(FileSystem fileSystem, Path lockDirectoryPath, LockInfo lockInfo)
            throws IOException
    {
        Path lockPath = new Path(lockDirectoryPath, lockInfo.getLockFilename());
        fileSystem.delete(lockPath, false);
    }

    private List<LockInfo> listLockInfos(FileSystem fileSystem, Path lockDirectoryPath)
            throws IOException
    {
        RemoteIterator<LocatedFileStatus> files = fileSystem.listFiles(lockDirectoryPath, false);
        ImmutableList.Builder<LockInfo> lockInfos = ImmutableList.builder();

        while (files.hasNext()) {
            LocatedFileStatus fileStatus = files.next();
            if (LOCK_FILENAME_PATTERN.matcher(fileStatus.getPath().getName()).matches()) {
                Optional<LockInfo> lockInfo = parseLockFile(fileSystem, fileStatus.getPath());
                lockInfo.ifPresent(lockInfos::add);
            }
        }

        return lockInfos.build();
    }

    private Optional<LockInfo> parseLockFile(FileSystem fileSystem, Path path)
            throws IOException
    {
        byte[] bytes = null;
        try (InputStream inputStream = fileSystem.open(path)) {
            bytes = inputStream.readAllBytes();
            LockFileContents lockFileContents = lockFileContentsJsonCodec.fromJson(bytes);
            return Optional.of(new LockInfo(path.getName(), lockFileContents));
        }
        catch (IllegalArgumentException e) {
            String content = null;
            if (bytes != null) {
                content = Base64.getEncoder().encodeToString(bytes);
            }
            LOG.warn(e, "Could not parse lock file: %s; contents=%s", path, content);
            return Optional.empty();
        }
        catch (IOException e) {
            if (e.getMessage().contains("The specified key does not exist.")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public static String parseEntryFilename(String lockFilename)
    {
        Matcher matcher = LOCK_FILENAME_PATTERN.matcher(lockFilename);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Lock filename " + lockFilename + " does not match expected pattern");
        }
        return matcher.group(1);
    }

    private static class LockInfo
    {
        private final String lockFilename;
        private final String entryFilename;
        private final LockFileContents contents;

        public LockInfo(String lockFilename, LockFileContents contents)
        {
            this.lockFilename = requireNonNull(lockFilename, "lockFilename is null");
            this.entryFilename = parseEntryFilename(lockFilename);
            this.contents = requireNonNull(contents, "contents is null");
        }

        public String getLockFilename()
        {
            return lockFilename;
        }

        public String getEntryFilename()
        {
            return entryFilename;
        }

        public String getClusterId()
        {
            return contents.getClusterId();
        }

        public String getOwningQuery()
        {
            return contents.getOwningQuery();
        }

        public Instant getExpirationTime()
        {
            return Instant.ofEpochMilli(contents.getExpirationEpochMillis());
        }
    }

    public static class LockFileContents
    {
        private final String clusterId;
        private final String owningQuery;
        private final long expirationEpochMillis;

        @JsonCreator
        public LockFileContents(
                @JsonProperty("clusterId") String clusterId,
                @JsonProperty("owningQuery") String owningQuery,
                @JsonProperty("expirationEpochMillis") long expirationEpochMillis)
        {
            this.clusterId = requireNonNull(clusterId, "clusterId is null");
            this.owningQuery = requireNonNull(owningQuery, "owningQuery is null");
            this.expirationEpochMillis = expirationEpochMillis;
        }

        @JsonProperty
        public String getClusterId()
        {
            return clusterId;
        }

        @JsonProperty
        public String getOwningQuery()
        {
            return owningQuery;
        }

        @JsonProperty
        public long getExpirationEpochMillis()
        {
            return expirationEpochMillis;
        }
    }
}
