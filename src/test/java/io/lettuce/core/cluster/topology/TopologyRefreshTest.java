/*
 * Copyright 2011-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.cluster.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.lettuce.Wait;
import io.lettuce.category.SlowTests;
import io.lettuce.core.*;
import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.AbstractClusterTest;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * Test for topology refreshing.
 *
 * @author Mark Paluch
 */
@SuppressWarnings({ "unchecked" })
@SlowTests
public class TopologyRefreshTest extends AbstractTest {

    public static final String host = TestSettings.hostAddr();
    private static RedisClient client = DefaultRedisClient.get();

    private RedisClusterClient clusterClient;
    private RedisCommands<String, String> redis1;
    private RedisCommands<String, String> redis2;

    @Before
    public void openConnection() {
        clusterClient = RedisClusterClient.create(client.getResources(), RedisURI.Builder
                .redis(host, AbstractClusterTest.port1).build());
        redis1 = client.connect(RedisURI.Builder.redis(AbstractClusterTest.host, AbstractClusterTest.port1).build()).sync();
        redis2 = client.connect(RedisURI.Builder.redis(AbstractClusterTest.host, AbstractClusterTest.port2).build()).sync();
    }

    @After
    public void closeConnection() {
        redis1.getStatefulConnection().close();
        redis2.getStatefulConnection().close();
        FastShutdown.shutdown(clusterClient);
    }

    @Test
    public void shouldUnsubscribeTopologyRefresh() {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(true) //
                .build();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());

        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = clusterClient.connect().async();

        AtomicBoolean clusterTopologyRefreshActivated = (AtomicBoolean) ReflectionTestUtils.getField(clusterClient,
                "clusterTopologyRefreshActivated");

        AtomicReference<ScheduledFuture<?>> clusterTopologyRefreshFuture = (AtomicReference) ReflectionTestUtils.getField(
                clusterClient, "clusterTopologyRefreshFuture");

        assertThat(clusterTopologyRefreshActivated.get()).isTrue();
        assertThat((Future) clusterTopologyRefreshFuture.get()).isNotNull();

        ScheduledFuture<?> scheduledFuture = clusterTopologyRefreshFuture.get();

        clusterConnection.getStatefulConnection().close();

        FastShutdown.shutdown(clusterClient);

        assertThat(clusterTopologyRefreshActivated.get()).isFalse();
        assertThat((Future) clusterTopologyRefreshFuture.get()).isNull();
        assertThat(scheduledFuture.isCancelled()).isTrue();
    }

    @Test
    public void changeTopologyWhileOperations() {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(true)//
                .refreshPeriod(1, TimeUnit.SECONDS)//
                .build();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());
        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = clusterClient.connect().async();

        clusterClient.getPartitions().clear();

        Wait.untilTrue(() -> {
            return !clusterClient.getPartitions().isEmpty();
        }).waitOrTimeout();

        clusterConnection.getStatefulConnection().close();
    }

    @Test
    public void dynamicSourcesProvidesClientCountForAllNodes() {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.create();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());
        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = clusterClient.connect().async();

        for (RedisClusterNode redisClusterNode : clusterClient.getPartitions()) {
            assertThat(redisClusterNode).isInstanceOf(RedisClusterNodeSnapshot.class);

            RedisClusterNodeSnapshot snapshot = (RedisClusterNodeSnapshot) redisClusterNode;
            assertThat(snapshot.getConnectedClients()).isNotNull().isGreaterThanOrEqualTo(0);
        }

        clusterConnection.getStatefulConnection().close();
    }

    @Test
    public void staticSourcesProvidesClientCountForSeedNodes() {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                .dynamicRefreshSources(false).build();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());
        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = clusterClient.connect().async();

        Partitions partitions = clusterClient.getPartitions();
        RedisClusterNodeSnapshot node1 = (RedisClusterNodeSnapshot) partitions.getPartitionBySlot(0);
        assertThat(node1.getConnectedClients()).isGreaterThanOrEqualTo(1);

        RedisClusterNodeSnapshot node2 = (RedisClusterNodeSnapshot) partitions.getPartitionBySlot(15000);
        assertThat(node2.getConnectedClients()).isNull();

        clusterConnection.getStatefulConnection().close();
    }

    @Test
    public void adaptiveTopologyUpdateOnDisconnectNodeIdConnection() {

        runReconnectTest((clusterConnection, node) -> {
            RedisClusterAsyncCommands<String, String> connection = clusterConnection.getConnection(node.getUri().getHost(),
                    node.getUri().getPort());

            return connection;
        });
    }

    @Test
    public void adaptiveTopologyUpdateOnDisconnectHostAndPortConnection() {

        runReconnectTest((clusterConnection, node) -> {
            RedisClusterAsyncCommands<String, String> connection = clusterConnection.getConnection(node.getUri().getHost(),
                    node.getUri().getPort());

            return connection;
        });
    }

    @Test
    public void adaptiveTopologyUpdateOnDisconnectDefaultConnection() {

        runReconnectTest((clusterConnection, node) -> {
            return clusterConnection;
        });
    }

    @Test
    public void adaptiveTopologyUpdateIsRateLimited() throws Exception {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()//
                .adaptiveRefreshTriggersTimeout(1, TimeUnit.HOURS)//
                .refreshTriggersReconnectAttempts(0)//
                .enableAllAdaptiveRefreshTriggers()//
                .build();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());
        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = clusterClient.connect().async();

        clusterClient.getPartitions().clear();
        clusterConnection.quit();

        Wait.untilTrue(() -> {
            return !clusterClient.getPartitions().isEmpty();
        }).waitOrTimeout();

        clusterClient.getPartitions().clear();
        clusterConnection.quit();

        Thread.sleep(1000);

        assertThat(clusterClient.getPartitions()).isEmpty();

        clusterConnection.getStatefulConnection().close();
    }

    @Test
    public void adaptiveTopologyUpdatetUsesTimeout() throws Exception {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()//
                .adaptiveRefreshTriggersTimeout(500, TimeUnit.MILLISECONDS)//
                .refreshTriggersReconnectAttempts(0)//
                .enableAllAdaptiveRefreshTriggers()//
                .build();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());
        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = clusterClient.connect().async();

        clusterConnection.quit();
        Thread.sleep(1000);

        Wait.untilTrue(() -> {
            return !clusterClient.getPartitions().isEmpty();
        }).waitOrTimeout();

        clusterClient.getPartitions().clear();
        clusterConnection.quit();

        Wait.untilTrue(() -> {
            return !clusterClient.getPartitions().isEmpty();
        }).waitOrTimeout();

        clusterConnection.getStatefulConnection().close();
    }

    @Test
    public void adaptiveTriggerDoesNotFireOnSingleReconnect() throws Exception {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()//
                .enableAllAdaptiveRefreshTriggers()//
                .build();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());
        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = clusterClient.connect().async();

        clusterClient.getPartitions().clear();

        clusterConnection.quit();
        Thread.sleep(500);

        assertThat(clusterClient.getPartitions()).isEmpty();
        clusterConnection.getStatefulConnection().close();
    }

    @Test
    public void adaptiveTriggerOnMoveRedirection() {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()//
                .enableAdaptiveRefreshTrigger(ClusterTopologyRefreshOptions.RefreshTrigger.MOVED_REDIRECT)//
                .build();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());

        StatefulRedisClusterConnection<String, String> connection = clusterClient.connect();
        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = connection.async();

        Partitions partitions = connection.getPartitions();
        RedisClusterNode node1 = partitions.getPartitionBySlot(0);
        RedisClusterNode node2 = partitions.getPartitionBySlot(12000);

        List<Integer> slots = node2.getSlots();
        slots.addAll(node1.getSlots());
        node2.setSlots(slots);
        node1.setSlots(Collections.emptyList());
        partitions.updateCache();

        assertThat(clusterClient.getPartitions().getPartitionByNodeId(node1.getNodeId()).getSlots()).hasSize(0);
        assertThat(clusterClient.getPartitions().getPartitionByNodeId(node2.getNodeId()).getSlots()).hasSize(16384);

        clusterConnection.set("b", value); // slot 3300

        Wait.untilEquals(12000, new Wait.Supplier<Integer>() {
            @Override
            public Integer get() {
                return clusterClient.getPartitions().getPartitionByNodeId(node1.getNodeId()).getSlots().size();
            }
        }).waitOrTimeout();

        assertThat(clusterClient.getPartitions().getPartitionByNodeId(node1.getNodeId()).getSlots()).hasSize(12000);
        assertThat(clusterClient.getPartitions().getPartitionByNodeId(node2.getNodeId()).getSlots()).hasSize(4384);
        clusterConnection.getStatefulConnection().close();
    }

    private void runReconnectTest(
            BiFunction<RedisAdvancedClusterAsyncCommands<String, String>, RedisClusterNode, BaseRedisAsyncCommands> function) {

        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()//
                .refreshTriggersReconnectAttempts(0)//
                .enableAllAdaptiveRefreshTriggers()//
                .build();
        clusterClient.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(topologyRefreshOptions).build());
        RedisAdvancedClusterAsyncCommands<String, String> clusterConnection = clusterClient.connect().async();

        RedisClusterNode node = clusterClient.getPartitions().getPartition(0);
        BaseRedisAsyncCommands closeable = function.apply(clusterConnection, node);
        clusterClient.getPartitions().clear();

        closeable.quit();

        Wait.untilTrue(() -> {
            return !clusterClient.getPartitions().isEmpty();
        }).waitOrTimeout();

        if (closeable instanceof RedisAdvancedClusterCommands) {
            ((RedisAdvancedClusterCommands) closeable).getStatefulConnection().close();
        }
        clusterConnection.getStatefulConnection().close();
    }
}
