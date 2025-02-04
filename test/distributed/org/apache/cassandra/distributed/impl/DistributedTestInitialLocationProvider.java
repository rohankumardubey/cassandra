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

package org.apache.cassandra.distributed.impl;


import org.apache.cassandra.distributed.shared.NetworkTopology;
import org.apache.cassandra.locator.InitialLocationProvider;
import org.apache.cassandra.tcm.membership.Location;
import org.apache.cassandra.utils.FBUtilities;

public class DistributedTestInitialLocationProvider implements InitialLocationProvider
{
    private static NetworkTopology mapping = null;
    public static void assign(NetworkTopology topology)
    {
        mapping = topology;
    }

    @Override
    public Location initialLocation()
    {
        String dc = mapping.localDC(FBUtilities.getBroadcastAddressAndPort());
        String rack = mapping.localRack(FBUtilities.getBroadcastAddressAndPort());
        return new Location(dc, rack);
    }
}
