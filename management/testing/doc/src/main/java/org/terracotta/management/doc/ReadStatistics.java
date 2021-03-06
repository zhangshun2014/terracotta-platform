/*
 * Copyright Terracotta, Inc.
 *
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
package org.terracotta.management.doc;

import org.slf4j.LoggerFactory;
import org.terracotta.connection.Connection;
import org.terracotta.connection.ConnectionException;
import org.terracotta.exception.EntityConfigurationException;
import org.terracotta.management.entity.tms.TmsAgentConfig;
import org.terracotta.management.entity.tms.client.IllegalManagementCallException;
import org.terracotta.management.entity.tms.client.TmsAgentService;
import org.terracotta.management.model.cluster.Cluster;
import org.terracotta.management.model.cluster.ServerEntity;
import org.terracotta.management.model.context.Context;
import org.terracotta.management.model.message.Message;
import org.terracotta.management.model.stats.ContextualStatistics;
import org.terracotta.management.model.stats.Statistic;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Arrays.asList;

/**
 * @author Mathieu Carbou
 */
public class ReadStatistics {
  public static void main(String[] args) throws ConnectionException, EntityConfigurationException, IOException, InterruptedException, ExecutionException, TimeoutException, IllegalManagementCallException {
    String className = ReadStatistics.class.getSimpleName();

    Connection connection = Utils.createConnection(className, args.length == 1 ? args[0] : "terracotta://localhost:9510");
    TmsAgentService tmsAgentService = Utils.createTmsAgentService(connection, className);

    Cluster cluster = tmsAgentService.readTopology();

    // trigger stats computation on server-side

    // 1. find the tms entity (management)
    ServerEntity serverEntity = cluster
        .activeServerEntityStream()
        .filter(e -> e.getType().equals(TmsAgentConfig.ENTITY_TYPE))
        .findFirst()
        .get();

    // 2. create a routing context
    Context context = serverEntity.getContext();

    // 3. collect stats on 3 capabilities (pool, stores, offheap)
    tmsAgentService.updateCollectedStatistics(context, "PoolStatistics", asList(
        "Pool:AllocatedSize"
    )).waitForReturn();
    tmsAgentService.updateCollectedStatistics(context, "ServerStoreStatistics", asList(
        "Store:AllocatedMemory",
        "Store:DataAllocatedMemory",
        "Store:OccupiedMemory",
        "Store:DataOccupiedMemory",
        "Store:Entries",
        "Store:UsedSlotCount",
        "Store:DataVitalMemory",
        "Store:VitalMemory",
        "Store:ReprobeLength",
        "Store:RemovedSlotCount",
        "Store:DataSize",
        "Store:TableCapacity"
    )).waitForReturn();
    tmsAgentService.updateCollectedStatistics(context, "OffHeapResourceStatistics", asList(
        "OffHeapResource:AllocatedMemory"
    )).waitForReturn();

    // trigger stats computation on client-side

    // 1. find ehcache clients in topology
    cluster
        .clientStream()
        .filter(c -> c.getName().startsWith("Ehcache:"))
        .forEach(ehcache -> {

          // 2. create a routing context
          Context ctx = ehcache.getContext()
              .with("cacheManagerName", "my-super-cache-manager");

          try {
            // 3. collect stats on client-side
            tmsAgentService.updateCollectedStatistics(ctx, "StatisticsCapability", asList("Cache:HitCount", "Clustered:HitCount", "Cache:MissCount", "Clustered:MissCount")).waitForReturn();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    ScheduledFuture<?> task = executorService.scheduleWithFixedDelay(() -> {
      try {

        List<Message> messages = tmsAgentService.readMessages();
        System.out.println(messages.size() + " messages");
        messages
            .stream()
            .filter(message -> message.getType().equals("STATISTICS"))
            .flatMap(message -> message.unwrap(ContextualStatistics.class).stream())
            .forEach(statistics -> {
              System.out.println(statistics.getContext());
              for (Map.Entry<String, Statistic<?, ?>> entry : statistics.getStatistics().entrySet()) {
                System.out.println(" - " + entry.getKey() + "=" + entry.getValue());
              }
            });

      } catch (InterruptedException | ExecutionException | TimeoutException e) {
        LoggerFactory.getLogger(className).error("ERR: " + e.getMessage(), e);
      }
    }, 0, 5, TimeUnit.SECONDS);

    System.in.read();

    task.cancel(false);
    executorService.shutdown();
    connection.close();
  }
}
