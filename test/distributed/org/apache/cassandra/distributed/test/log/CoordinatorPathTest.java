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

package org.apache.cassandra.distributed.test.log;


import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;

import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.ReadResponse;
import org.apache.cassandra.distributed.api.ConsistencyLevel;
import org.apache.cassandra.harry.SchemaSpec;
import org.apache.cassandra.harry.ValueGeneratorHelper;
import org.apache.cassandra.harry.cql.WriteHelper;
import org.apache.cassandra.harry.dsl.HistoryBuilder;
import org.apache.cassandra.harry.execution.CompiledStatement;
import org.apache.cassandra.harry.gen.Generator;
import org.apache.cassandra.harry.gen.SchemaGenerators;
import org.apache.cassandra.harry.model.TokenPlacementModel;
import org.apache.cassandra.harry.model.TokenPlacementModel.Replica;
import org.apache.cassandra.harry.op.Operations;
import org.apache.cassandra.harry.util.ByteUtils;
import org.apache.cassandra.harry.util.TokenUtil;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.NoPayload;
import org.apache.cassandra.net.Verb;
import org.apache.cassandra.utils.concurrent.Future;

import static org.apache.cassandra.harry.checker.TestHelper.withRandom;

public class CoordinatorPathTest extends CoordinatorPathTestBase
{
    private static final TokenPlacementModel.SimpleReplicationFactor RF = new TokenPlacementModel.SimpleReplicationFactor(3);

    @Test
    public void writeConsistencyTest() throws Throwable
    {
        Generator<SchemaSpec> schemaGen = SchemaGenerators.schemaSpecGen(KEYSPACE, "write_consistency_test", 1000);
        coordinatorPathTest(RF, (cluster, simulatedCluster) -> {
            for (int ignored : new int[]{ 2, 3, 4, 5 })
                simulatedCluster.createNode().register();

            for (int idx : new int[]{ 2, 3, 4, 5 })
                simulatedCluster.node(idx).join();

            VirtualSimulatedCluster prediction = simulatedCluster.asVirtual();
            prediction.createNode();
            prediction.node(6).register();
            prediction.node(6).lazyJoin()
                      .prepareJoin()
                      .startJoin();

            withRandom(rng -> {
                SchemaSpec schema = schemaGen.generate(rng);
                cluster.schemaChange("CREATE KEYSPACE " + schema.keyspace +
                                     " WITH replication = {'class': 'SimpleStrategy', 'replication_factor' : 3};");
                cluster.schemaChange(schema.compile());

                HistoryBuilder.IndexedValueGenerators valueGenerators = (HistoryBuilder.IndexedValueGenerators) schema.valueGenerators;
                for (int i = 0; i < valueGenerators.pkPopulation(); i++)
                {
                    long pd = valueGenerators.pkGen().descriptorAt(i);

                    ByteBuffer[] pk = ByteUtils.objectsToBytes(valueGenerators.pkGen().inflate(pd));
                    long token = TokenUtil.token(ByteUtils.compose(pk));
                    if (!prediction.state.get().isWriteTargetFor(token, prediction.node(6).matcher))
                        continue;

                    simulatedCluster.waitForQuiescense();
                    List<Replica> replicas = simulatedCluster.state.get().writePlacementsFor(token);
                    // At most 2 replicas should respond, so that when the pending node is added, results would be insufficient for recomputed blockFor
                    BooleanSupplier shouldRespond = atMostResponses(simulatedCluster.state.get().isWriteTargetFor(token, simulatedCluster.node(1).matcher) ? 1 : 2);
                    List<WaitingAction<?,?>> waiting = simulatedCluster
                                                       .filter((n) -> replicas.stream().map(Replica::node).anyMatch(n.matcher) && n.node.idx() != 1)
                                                       .map((nodeToBlockOn) -> nodeToBlockOn.blockOnReplica((node) -> new MutationAction(node, shouldRespond)))
                                                       .collect(Collectors.toList());

                    long lts = 1L;
                    Future<?> writeQuery = async(() -> {

                        CompiledStatement s = WriteHelper.inflateInsert(new Operations.WriteOp(lts, pd, 0,
                                                                                               ValueGeneratorHelper.randomDescriptors(rng, valueGenerators::regularColumnGen, valueGenerators.regularColumnCount()),
                                                                                               ValueGeneratorHelper.randomDescriptors(rng, valueGenerators::staticColumnGen, valueGenerators.staticColumnCount()),
                                                                                               Operations.Kind.INSERT),
                                                                        schema,
                                                                        lts);
                        cluster.coordinator(1).execute(s.cql(), ConsistencyLevel.QUORUM, s.bindings());
                        return null;
                    });

                    waiting.forEach(WaitingAction::waitForMessage);

                    simulatedCluster.createNode().register();
                    simulatedCluster.node(6)
                                    .lazyJoin()
                                    .prepareJoin()
                                    .startJoin();

                    simulatedCluster.waitForQuiescense();

                    waiting.forEach(WaitingAction::resume);

                    try
                    {
                        writeQuery.get();
                        Assert.fail("Should have thrown");
                    }
                    catch (Throwable t)
                    {
                        if (t.getMessage() == null)
                            throw t;
                        Assert.assertTrue("Expected a different error message, but got " + t.getMessage(),
                                          t.getMessage().contains("the ring has changed"));
                        return;
                    }
                }

            });
        });
    }

    @Test
    public void readConsistencyTest() throws Throwable
    {
        coordinatorPathTest(RF, (cluster, simulatedCluster) -> {
            Random random = new Random(0);
            cluster.schemaChange("CREATE KEYSPACE IF NOT EXISTS " + KEYSPACE + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': " + 3 + "};", true, cluster.get(1));
            cluster.schemaChange("CREATE TABLE IF NOT EXISTS " + KEYSPACE + ".tbl (pk int, ck int, v int, PRIMARY KEY (pk, ck))", true, cluster.get(1));

            for (int ignored : new int[]{ 2, 3, 4, 5 })
                simulatedCluster.createNode().register();

            for (int idx : new int[]{ 2, 3, 4, 5 })
                simulatedCluster.node(idx).join();

            while (true)
            {
                int pk = random.nextInt();
                if (!simulatedCluster.state.get().isReadReplicaFor(token(pk), simulatedCluster.node(4).matcher) ||
                    !simulatedCluster.state.get().isReadReplicaFor(token(pk), simulatedCluster.node(1).matcher))
                    continue;

                simulatedCluster.waitForQuiescense();

                List<Replica> replicas = simulatedCluster.state.get().readReplicasFor(token(pk));
                Function<Integer, BooleanSupplier> shouldRespond = respondFrom(1, 4);
                List<WaitingAction<?,?>> waiting = simulatedCluster
                                                   .filter((n) -> replicas.stream().map(Replica::node).anyMatch(n.matcher) && n.node.idx() != 1)
                                                   .map((nodeToBlockOn) -> nodeToBlockOn.blockOnReplica((node) -> new ReadAction(node, shouldRespond.apply(nodeToBlockOn.node.idx()))))
                                                   .collect(Collectors.toList());

                Future<?> readQuery = async(() -> cluster.coordinator(1).execute("select * from distributed_test_keyspace.tbl where pk = ?", ConsistencyLevel.QUORUM, pk));

                waiting.forEach(WaitingAction::waitForMessage);

                simulatedCluster.node(4)
                                .lazyLeave()
                                .prepareLeave()
                                .startLeave()
                                .midLeave()
                                .finishLeave();

                simulatedCluster.waitForQuiescense();

                waiting.forEach(WaitingAction::resume);

                try
                {
                    readQuery.get();
                    Assert.fail();
                }
                catch (Throwable t)
                {
                    if (t.getMessage() == null)
                        throw t;
                    Assert.assertTrue(String.format("Got exception: %s", t),
                                      t.getMessage().contains("the ring has changed"));
                    return;
                }
            }
        });
    }

    @Test
    public void coordinatorReadWriteTest() throws Throwable
    {
        coordinatorPathTest(RF, (cluster, simulatedCluster) -> {
            cluster.schemaChange("CREATE KEYSPACE IF NOT EXISTS " + KEYSPACE + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': " + 3 + "};");
            cluster.schemaChange("CREATE TABLE IF NOT EXISTS " + KEYSPACE + ".tbl (pk int, ck int, v int, PRIMARY KEY (pk, ck))");

            for (int ignored : new int[]{ 2, 3, 4, 5 })
                simulatedCluster.createNode().register();
            for (int idx : new int[]{ 2, 3, 4, 5 })
                simulatedCluster.node(idx).join();

            simulatedCluster.createNode();
            simulatedCluster.node(6).register();
            simulatedCluster.node(6).lazyJoin()
                            .prepareJoin()
                            .startJoin();

            simulatedCluster.waitForQuiescense();

            AtomicInteger reads = new AtomicInteger();
            AtomicInteger writes = new AtomicInteger();
            simulatedCluster.node(6).clean(Verb.READ_REQ);
            simulatedCluster.node(6).on(Verb.READ_REQ, new ReadAction(simulatedCluster.node(6)) {
                public Message<ReadResponse> respondTo(Message<ReadCommand> request)
                {
                    reads.incrementAndGet();
                    return super.respondTo(request);
                }
            });
            simulatedCluster.node(6).clean(Verb.MUTATION_REQ);
            simulatedCluster.node(6).on(Verb.MUTATION_REQ, new MutationAction(simulatedCluster.node(6)) {
                public Message<NoPayload> respondTo(Message<Mutation> request)
                {
                    writes.incrementAndGet();
                    return super.respondTo(request);
                }
            });
            int expectedWrites = 0;
            for (int i = 0; i < 500; i++)
            {
                if (simulatedCluster.state.get().isWriteTargetFor(token(i), simulatedCluster.node(6).matcher))
                    expectedWrites++;
                cluster.coordinator(1).execute("insert into distributed_test_keyspace.tbl (pk, ck) values (" + i + ", 1)", ConsistencyLevel.ALL);
                cluster.coordinator(1).execute("select * from distributed_test_keyspace.tbl where pk = " + i, ConsistencyLevel.ALL);
            }
            Assert.assertEquals(0, reads.get());
            Assert.assertEquals(expectedWrites, writes.get());
        });
    }

}