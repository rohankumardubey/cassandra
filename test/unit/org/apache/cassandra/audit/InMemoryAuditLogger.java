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
package org.apache.cassandra.audit;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import com.google.common.annotations.VisibleForTesting;

public class InMemoryAuditLogger implements IAuditLogger
{
    final Queue<AuditLogEntry> inMemQueue = new LinkedList<>();
    private boolean enabled = true;

    public InMemoryAuditLogger(Map<String, String> params)
    {

    }

    @Override
    public boolean isEnabled()
    {
        return enabled;
    }

    @Override
    public void log(AuditLogEntry logMessage)
    {
        inMemQueue.offer(logMessage);
    }

    @Override
    public void stop()
    {
        enabled = false;
        inMemQueue.clear();
    }

    @VisibleForTesting
    public Queue<AuditLogEntry> internalQueue()
    {
        return inMemQueue;
    }
}
