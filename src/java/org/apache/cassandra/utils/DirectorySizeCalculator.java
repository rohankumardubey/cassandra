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

package org.apache.cassandra.utils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Walks directory recursively, summing up total contents of files within.
 */
public class DirectorySizeCalculator extends SimpleFileVisitor<Path>
{
    private volatile long size = 0;

    public DirectorySizeCalculator()
    {
        super();
    }

    public boolean isAcceptable(Path file)
    {
        return true;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
    {
        if (isAcceptable(file))
            size += attrs.size();
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc)
    {
        return FileVisitResult.CONTINUE;
    }

    public long getAllocatedSize()
    {
        return size;
    }

    /**
     * Reset the size to 0 in case that the size calculator is used multiple times
     */
    public void resetSize()
    {
        size = 0;
    }
}
