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
package io.trino.filesystem.hdfs;

import io.trino.filesystem.FileEntry;
import io.trino.filesystem.FileIterator;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;

import java.io.IOException;
import java.net.URI;

import static com.google.common.base.Verify.verify;
import static java.util.Objects.requireNonNull;

class HdfsFileIterator
        implements FileIterator
{
    private final String listingPath;
    private final URI listingUri;
    private final RemoteIterator<LocatedFileStatus> iterator;

    public HdfsFileIterator(String listingPath, FileSystem fs, RemoteIterator<LocatedFileStatus> iterator)
    {
        this.listingPath = requireNonNull(listingPath, "listingPath is null");
        this.listingUri = new Path(listingPath).makeQualified(fs.getUri(), fs.getWorkingDirectory()).toUri();
        this.iterator = requireNonNull(iterator, "iterator is null");
    }

    @Override
    public boolean hasNext()
            throws IOException
    {
        return iterator.hasNext();
    }

    @Override
    public FileEntry next()
            throws IOException
    {
        LocatedFileStatus status = iterator.next();

        verify(status.isFile(), "iterator returned a non-file: %s", status);

        URI pathUri = URI.create(status.getPath().toString());
        URI relativeUri = listingUri.relativize(pathUri);
        verify(!relativeUri.equals(pathUri), "cannot relativize [%s] against [%s]", pathUri, listingUri);

        String path = listingPath;
        if (!relativeUri.getPath().isEmpty()) {
            if (!path.endsWith("/")) {
                path += "/";
            }
            path += relativeUri.getPath();
        }

        return new FileEntry(path, status.getLen(), status.getModificationTime());
    }
}
