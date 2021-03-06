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
package org.terracotta.management.service.monitoring;

import org.terracotta.management.model.call.ContextualReturn;
import org.terracotta.management.model.capabilities.Capability;
import org.terracotta.management.model.cluster.ManagementRegistry;
import org.terracotta.management.model.context.ContextContainer;
import org.terracotta.management.model.notification.ContextualNotification;
import org.terracotta.management.model.stats.ContextualStatistics;
import org.terracotta.monitoring.IMonitoringProducer;

import static org.terracotta.management.service.monitoring.DefaultDataListener.TOPIC_SERVER_ENTITY_NOTIFICATION;
import static org.terracotta.management.service.monitoring.DefaultDataListener.TOPIC_SERVER_ENTITY_STATISTICS;

/**
 * @author Mathieu Carbou
 */
class DefaultPassiveEntityMonitoringService extends AbstractEntityMonitoringService implements PassiveEntityMonitoringService {

  private final IMonitoringProducer monitoringProducer;

  DefaultPassiveEntityMonitoringService(long consumerId, IMonitoringProducer monitoringProducer) {
    super(consumerId);
    this.monitoringProducer = monitoringProducer;
  }

  @Override
  public void exposeManagementRegistry(ContextContainer contextContainer, Capability... capabilities) {
    logger.trace("[{}] exposeManagementRegistry({})", getConsumerId(), contextContainer);
    ManagementRegistry registry = ManagementRegistry.create(contextContainer);
    registry.addCapabilities(capabilities);
    monitoringProducer.addNode(new String[0], "registry", registry);
  }

  @Override
  public void pushNotification(ContextualNotification notification) {
    logger.trace("[{}] pushNotification({})", getConsumerId(), notification);
    monitoringProducer.pushBestEffortsData(TOPIC_SERVER_ENTITY_NOTIFICATION, notification);
  }

  @Override
  public void pushStatistics(ContextualStatistics... statistics) {
    if (statistics.length > 0) {
      logger.trace("[{}] pushStatistics({})", getConsumerId(), statistics.length);
      monitoringProducer.pushBestEffortsData(TOPIC_SERVER_ENTITY_STATISTICS, statistics);
    }
  }

  @Override
  public void answerManagementCall(String managementCallIdentifier, ContextualReturn<?> contextualReturn) {
    logger.trace("[{}] answerManagementCall({}, executed={}, error={})", getConsumerId(), managementCallIdentifier, contextualReturn.hasExecuted(), contextualReturn.errorThrown());
    monitoringProducer.addNode(new String[]{"management", "answer"}, managementCallIdentifier, contextualReturn);
  }

}
