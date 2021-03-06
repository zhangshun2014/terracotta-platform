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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.entity.ClientDescriptor;
import org.terracotta.entity.PlatformConfiguration;
import org.terracotta.management.model.cluster.Client;
import org.terracotta.management.model.cluster.ClientIdentifier;
import org.terracotta.management.model.cluster.Cluster;
import org.terracotta.management.model.cluster.Connection;
import org.terracotta.management.model.cluster.Endpoint;
import org.terracotta.management.model.cluster.ManagementRegistry;
import org.terracotta.management.model.cluster.Server;
import org.terracotta.management.model.cluster.ServerEntity;
import org.terracotta.management.model.cluster.ServerEntityIdentifier;
import org.terracotta.management.model.cluster.Stripe;
import org.terracotta.management.model.context.Context;
import org.terracotta.management.model.notification.ContextualNotification;
import org.terracotta.management.sequence.TimeSource;
import org.terracotta.monitoring.PlatformConnectedClient;
import org.terracotta.monitoring.PlatformEntity;
import org.terracotta.monitoring.PlatformServer;
import org.terracotta.monitoring.ServerState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.terracotta.management.service.monitoring.TopologyService.Notification.CLIENT_CONNECTED;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.CLIENT_DISCONNECTED;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.SERVER_ENTITY_CREATED;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.SERVER_ENTITY_DESTROYED;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.SERVER_ENTITY_FAILOVER_COMPLETED;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.SERVER_ENTITY_FETCHED;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.SERVER_ENTITY_UNFETCHED;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.SERVER_JOINED;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.SERVER_LEFT;
import static org.terracotta.management.service.monitoring.TopologyService.Notification.SERVER_STATE_CHANGED;

/**
 * @author Mathieu Carbou
 */
class TopologyService implements PlatformListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(TopologyService.class);

  private final Cluster cluster;
  private final Stripe stripe;
  private final Map<Long, Map<ClientDescriptor, ClientIdentifier>> fetches = new HashMap<>();
  private final EventService eventService;
  private final TimeSource timeSource;
  private final PlatformConfiguration platformConfiguration;
  private final List<EntityListener> entityListeners = new CopyOnWriteArrayList<>();
  private final Map<ServerEntityIdentifier, Long> failoverEntities = new HashMap<>();

  private volatile Server currentActive;

  TopologyService(EventService eventService, TimeSource timeSource, PlatformConfiguration platformConfiguration) {
    this.eventService = Objects.requireNonNull(eventService);
    this.timeSource = Objects.requireNonNull(timeSource);
    this.platformConfiguration = platformConfiguration;
    this.cluster = Cluster.create().addStripe(stripe = Stripe.create("SINGLE"));
  }

  // ================================================
  // PLATFORM CALLBACKS: only called on active server
  // ================================================

  @SuppressWarnings("OptionalGetWithoutIsPresent")
  @Override
  public synchronized void serverDidBecomeActive(PlatformServer self) {
    LOGGER.trace("[0] serverDidBecomeActive({})", self.getServerName());

    serverDidJoinStripe(self);

    currentActive = stripe.getServerByName(self.getServerName()).get();
    currentActive.setState(Server.State.ACTIVE);
    currentActive.setActivateTime(timeSource.getTimestamp());
  }

  @Override
  public synchronized void serverDidJoinStripe(PlatformServer platformServer) {
    LOGGER.trace("[0] serverDidJoinStripe({})", platformServer.getServerName());

    Server server = Server.create(platformServer.getServerName())
        .setBindAddress(platformServer.getBindAddress())
        .setBindPort(platformServer.getBindPort())
        .setBuildId(platformServer.getBuild())
        .setGroupPort(platformServer.getGroupPort())
        .setHostName(platformServer.getHostName())
        .setStartTime(platformServer.getStartTime())
        .setHostAddress(platformServer.getHostAddress())
        .setVersion(platformServer.getVersion())
        .computeUpTime();

    stripe.addServer(server);

    eventService.fireNotification(new ContextualNotification(server.getContext(), SERVER_JOINED.name()));
  }

  @Override
  public synchronized void serverDidLeaveStripe(PlatformServer platformServer) {
    LOGGER.trace("[0] serverDidLeaveStripe({})", platformServer.getServerName());

    Server server = stripe.getServerByName(platformServer.getServerName())
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing server: " + platformServer.getServerName()));

    Context context = server.getContext();
    server.remove();

    eventService.fireNotification(new ContextualNotification(context, SERVER_LEFT.name()));
  }

  @Override
  public synchronized void serverEntityCreated(PlatformServer sender, PlatformEntity platformEntity) {
    LOGGER.trace("[0] serverEntityCreated({}, {})", sender.getServerName(), platformEntity);

    if (platformEntity.isActive && !sender.getServerName().equals(getActiveServer().getServerName())) {
      throw newIllegalTopologyState("Server " + sender.getServerName() + " is not current active server but it created an active entity " + platformEntity);
    }

    if (!platformEntity.isActive && sender.getServerName().equals(getActiveServer().getServerName())) {
      throw newIllegalTopologyState("Server " + sender.getServerName() + " is the current active server but it created a passive entity " + platformEntity);
    }

    Server server = stripe.getServerByName(sender.getServerName())
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing server: " + sender.getServerName()));
    ServerEntityIdentifier identifier = ServerEntityIdentifier.create(platformEntity.name, platformEntity.typeName);
    ServerEntity entity = ServerEntity.create(identifier)
        .setConsumerId(platformEntity.consumerID);

    server.addServerEntity(entity);

    if (isCurrentServerActive() && sender.getServerName().equals(currentActive.getServerName())) {
      // keep track of fetches per entity for an active server
      fetches.put(platformEntity.consumerID, new HashMap<>());
    }

    eventService.fireNotification(new ContextualNotification(entity.getContext(), SERVER_ENTITY_CREATED.name()));

    if (failoverEntities.remove(identifier) != null) {
      eventService.fireNotification(new ContextualNotification(entity.getContext(), SERVER_ENTITY_FAILOVER_COMPLETED.name()));
    }
  }

  @Override
  public synchronized void serverEntityDestroyed(PlatformServer sender, PlatformEntity platformEntity) {
    LOGGER.trace("[0] serverEntityDestroyed({}, {})", sender.getServerName(), platformEntity);

    if (platformEntity.isActive && !sender.getServerName().equals(getActiveServer().getServerName())) {
      throw newIllegalTopologyState("Server " + sender.getServerName() + " is not current active server but it destroyed an active entity " + platformEntity);
    }

    if (!platformEntity.isActive && sender.getServerName().equals(getActiveServer().getServerName())) {
      throw newIllegalTopologyState("Server " + sender.getServerName() + " is the current active server but it destroyed a passive entity " + platformEntity);
    }

    Server server = stripe.getServerByName(sender.getServerName())
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing server: " + sender.getServerName()));

    ServerEntity entity = server.getServerEntity(platformEntity.name, platformEntity.typeName)
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing entity: " + platformEntity + " on server " + sender.getServerName()));

    Context context = entity.getContext();
    entity.remove();

    if (isCurrentServerActive() && sender.getServerName().equals(currentActive.getServerName())) {
      fetches.remove(platformEntity.consumerID);

      entityListeners.forEach(listener -> listener.onEntityDestroyed(platformEntity.consumerID));
    }

    eventService.fireNotification(new ContextualNotification(context, SERVER_ENTITY_DESTROYED.name()));
  }

  @Override
  public synchronized void serverEntityFailover(PlatformServer sender, PlatformEntity platformEntity) {
    LOGGER.trace("[0] serverEntityFailover({}, {})", sender.getServerName(), platformEntity);

    if (platformEntity.isActive || !sender.getServerName().equals(getActiveServer().getServerName())) {
      throw newIllegalTopologyState("Server " + sender.getServerName() + " should be active and should receive a passive entity " + platformEntity);
    }

    // we cannot fire any event because the monitoring tree is not constructed yet, and the TmsEntity is not yet connected
    // we are still transitioning from passive entities to active entities
    // so we can keep track of those and send an event after, when they become active
    failoverEntities.put(ServerEntityIdentifier.create(platformEntity.name, platformEntity.typeName), platformEntity.consumerID);

    entityListeners.forEach(listener -> listener.onEntityFailover(platformEntity.consumerID));
  }

  @Override
  public synchronized void clientConnected(PlatformConnectedClient platformConnectedClient) {
    LOGGER.trace("[0] clientConnected({})", platformConnectedClient);

    ClientIdentifier clientIdentifier = toClientIdentifier(platformConnectedClient);
    Endpoint endpoint = Endpoint.create(platformConnectedClient.remoteAddress.getHostAddress(), platformConnectedClient.remotePort);

    Client client = Client.create(clientIdentifier)
        .setHostName(platformConnectedClient.remoteAddress.getHostName());
    cluster.addClient(client);

    client.addConnection(Connection.create(clientIdentifier.getConnectionUid(), getActiveServer(), endpoint));

    eventService.fireNotification(new ContextualNotification(client.getContext(), CLIENT_CONNECTED.name()));
  }

  @Override
  public synchronized void clientDisconnected(PlatformConnectedClient platformConnectedClient) {
    LOGGER.trace("[0] clientDisconnected({})", platformConnectedClient);

    ClientIdentifier clientIdentifier = toClientIdentifier(platformConnectedClient);
    Client client = cluster.getClient(clientIdentifier)
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing client: " + clientIdentifier));
    Context context = client.getContext();

    client.remove();

    eventService.fireNotification(new ContextualNotification(context, CLIENT_DISCONNECTED.name()));
  }

  @Override
  public synchronized void clientFetch(PlatformConnectedClient platformConnectedClient, PlatformEntity platformEntity, ClientDescriptor clientDescriptor) {
    LOGGER.trace("[0] clientFetch({}, {})", platformConnectedClient, platformEntity);

    Server currentActive = getActiveServer();
    ClientIdentifier clientIdentifier = toClientIdentifier(platformConnectedClient);
    Endpoint endpoint = Endpoint.create(platformConnectedClient.remoteAddress.getHostAddress(), platformConnectedClient.remotePort);

    Client client = cluster.getClient(clientIdentifier)
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing client: " + clientIdentifier));

    Connection connection = client.getConnection(currentActive, endpoint)
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing connection between server " + currentActive + " and client " + clientIdentifier));
    ServerEntity entity = currentActive.getServerEntity(platformEntity.name, platformEntity.typeName)
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing entity: name=" + platformEntity.name + ", type=" + platformEntity.typeName));

    if (!connection.fetchServerEntity(platformEntity.name, platformEntity.typeName)) {
      throw newIllegalTopologyState("Unable to fetch entity " + platformEntity + " from client " + client);
    }

    fetches.get(platformEntity.consumerID).put(clientDescriptor, clientIdentifier);

    entityListeners.forEach(listener -> listener.onFetch(platformEntity.consumerID, clientDescriptor));

    eventService.fireNotification(new ContextualNotification(entity.getContext(), SERVER_ENTITY_FETCHED.name(), client.getContext()));
  }

  @Override
  public synchronized void clientUnfetch(PlatformConnectedClient platformConnectedClient, PlatformEntity platformEntity, ClientDescriptor clientDescriptor) {
    LOGGER.trace("[0] clientUnfetch({}, {})", platformConnectedClient, platformEntity);

    Server currentActive = getActiveServer();
    ClientIdentifier clientIdentifier = toClientIdentifier(platformConnectedClient);
    Endpoint endpoint = Endpoint.create(platformConnectedClient.remoteAddress.getHostAddress(), platformConnectedClient.remotePort);

    ServerEntity entity = currentActive.getServerEntity(platformEntity.name, platformEntity.typeName)
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing entity: name=" + platformEntity.name + ", type=" + platformEntity.typeName));

    Client client = cluster.getClient(clientIdentifier)
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing client: " + clientIdentifier));
    Connection connection = client.getConnection(currentActive, endpoint)
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing connection: " + endpoint + " to server " + currentActive.getServerName() + " from client " + clientIdentifier));

    fetches.get(platformEntity.consumerID).remove(clientDescriptor);

    entityListeners.forEach(listener -> listener.onUnfetch(platformEntity.consumerID, clientDescriptor));

    if (connection.unfetchServerEntity(platformEntity.name, platformEntity.typeName)) {
      eventService.fireNotification(new ContextualNotification(entity.getContext(), SERVER_ENTITY_UNFETCHED.name(), client.getContext()));
    }
  }

  @Override
  public synchronized void serverStateChanged(PlatformServer sender, ServerState serverState) {
    LOGGER.trace("[0] serverStateChanged({}, {})", sender.getServerName(), serverState.getState());

    Server server = stripe.getServerByName(sender.getServerName())
        .<IllegalStateException>orElseThrow(() -> newIllegalTopologyState("Missing server: " + sender.getServerName()));

    server.setState(Server.State.parse(serverState.getState()));
    server.setActivateTime(serverState.getActivate());

    Map<String, String> attrs = new HashMap<>();
    attrs.put("state", serverState.getState());
    attrs.put("activateTime", serverState.getActivate() > 0 ? String.valueOf(serverState.getActivate()) : "0");

    eventService.fireNotification(new ContextualNotification(server.getContext(), SERVER_STATE_CHANGED.name(), attrs));
  }

  synchronized void setEntityManagementRegistry(long consumerId, String serverName, ManagementRegistry newRegistry) {
    stripe.getServerByName(serverName)
        .flatMap(server -> server.getServerEntity(consumerId))
        .ifPresent(serverEntity -> {
          String notif = serverEntity.getManagementRegistry().map(current -> current.equals(newRegistry) ? "" : "ENTITY_REGISTRY_UPDATED").orElse("ENTITY_REGISTRY_AVAILABLE");
          if (!notif.isEmpty()) {
            serverEntity.setManagementRegistry(newRegistry);
            eventService.fireNotification(new ContextualNotification(serverEntity.getContext(), notif));
          }
        });
  }

  synchronized void setClientManagementRegistry(long consumerId, ClientDescriptor clientDescriptor, ManagementRegistry newRegistry) {
    getClient(consumerId, clientDescriptor).ifPresent(client -> {
      String notif = client.getManagementRegistry().map(current -> current.equals(newRegistry) ? "" : "CLIENT_REGISTRY_UPDATED").orElse("CLIENT_REGISTRY_AVAILABLE");
      if (!notif.isEmpty()) {
        client.setManagementRegistry(newRegistry);
        eventService.fireNotification(new ContextualNotification(client.getContext(), notif));
      }
    });
  }

  synchronized void setClientTags(long consumerId, ClientDescriptor clientDescriptor, String[] tags) {
    getClient(consumerId, clientDescriptor).ifPresent(client -> {
      Set<String> currtags = new HashSet<>(client.getTags());
      Set<String> newTags = new HashSet<>(Arrays.asList(tags));
      if (!currtags.equals(newTags)) {
        client.setTags(tags);
        eventService.fireNotification(new ContextualNotification(client.getContext(), "CLIENT_TAGS_UPDATED"));
      }
    });
  }

  synchronized Optional<Context> getEntityContext(String serverName, long consumerId) {
    return stripe.getServerByName(serverName)
        .flatMap(server -> server.getServerEntity(consumerId))
        .map(ServerEntity::getContext);
  }

  synchronized Optional<Context> getManageableEntityContext(String serverName, long consumerId) {
    return stripe.getServerByName(serverName)
        .flatMap(server -> server.getServerEntity(consumerId))
        .filter(ServerEntity::isManageable)
        .map(ServerEntity::getContext);
  }

  synchronized Optional<Context> getManageableEntityContext(String serverName, String entityName, String entityType) {
    return stripe.getServerByName(serverName)
        .flatMap(server -> server.getServerEntity(entityName, entityType))
        .filter(ServerEntity::isManageable)
        .map(ServerEntity::getContext);
  }

  synchronized Optional<Context> getClientContext(long consumerId, ClientDescriptor clientDescriptor) {
    return getClient(consumerId, clientDescriptor)
        .map(Client::getContext);
  }

  synchronized Optional<Context> getManageableClientContext(ClientIdentifier clientIdentifier) {
    return cluster.getClient(clientIdentifier)
        .filter(Client::isManageable)
        .map(Client::getContext);
  }

  synchronized Optional<Context> getManageableClientContext(long consumerId, ClientDescriptor clientDescriptor) {
    return getClient(consumerId, clientDescriptor)
        .filter(Client::isManageable)
        .map(Client::getContext);
  }

  synchronized Cluster getClusterCopy() {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
        oos.writeObject(cluster);
        oos.flush();
      }
      try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
        return (Cluster) ois.readObject();
      }
    } catch (IOException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  boolean isCurrentServerActive() {
    return currentActive != null && currentActive.getServerName().equals(platformConfiguration.getServerName());
  }

  String getCurrentServerName() {
    return platformConfiguration.getServerName();
  }

  void addEntityListener(EntityListener entityListener) {
    entityListeners.add(Objects.requireNonNull(entityListener));
  }

  void removeEntityListener(EntityListener entityListener) {
    if (entityListener != null) {
      entityListeners.remove(entityListener);
    }
  }

  private Server getActiveServer() {
    if (currentActive == null) {
      throw newIllegalTopologyState("No active server defined!");
    }
    return currentActive;
  }

  private Optional<Client> getClient(long consumerId, ClientDescriptor clientDescriptor) {
    Map<ClientDescriptor, ClientIdentifier> map = fetches.get(consumerId);
    if (map == null) {
      return Optional.empty();
    }
    ClientIdentifier clientIdentifier = map.get(clientDescriptor);
    return clientIdentifier == null ? Optional.empty() : cluster.getClient(clientIdentifier);
  }

  private IllegalStateException newIllegalTopologyState(String message) {
    return new IllegalStateException("Illegal monitoring topology state: " + message + "\n- currentActive: " + currentActive + "\n- cluster:" + cluster);
  }

  private static ClientIdentifier toClientIdentifier(PlatformConnectedClient connection) {
    return ClientIdentifier.create(
        connection.clientPID,
        connection.remoteAddress.getHostAddress(),
        connection.name == null || connection.name.isEmpty() ? "UNKNOWN" : connection.name,
        connection.uuid);
  }

  enum Notification {
    SERVER_ENTITY_CREATED,
    SERVER_ENTITY_DESTROYED,
    SERVER_ENTITY_FAILOVER_COMPLETED,

    SERVER_ENTITY_FETCHED,
    SERVER_ENTITY_UNFETCHED,

    CLIENT_CONNECTED,
    CLIENT_DISCONNECTED,

    SERVER_JOINED,
    SERVER_LEFT,
    SERVER_STATE_CHANGED,
  }

}
