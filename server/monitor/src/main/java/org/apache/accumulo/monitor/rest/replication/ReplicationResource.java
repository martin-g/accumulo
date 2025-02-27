/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.monitor.rest.replication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.TableOfflineException;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.clientImpl.Tables;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.RootTable;
import org.apache.accumulo.core.replication.ReplicationSchema.WorkSection;
import org.apache.accumulo.core.replication.ReplicationTable;
import org.apache.accumulo.core.replication.ReplicationTarget;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.monitor.Monitor;
import org.apache.accumulo.server.replication.ReplicaSystem;
import org.apache.accumulo.server.replication.ReplicaSystemFactory;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates the replication table with information from the Monitor
 *
 * @since 2.0.0
 */
@Path("/replication")
@Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
@Deprecated
public class ReplicationResource {
  private static final Logger log = LoggerFactory.getLogger(ReplicationResource.class);

  @Inject
  private Monitor monitor;

  /**
   * Generates the replication table as a JSON object
   *
   * @return Replication list
   */
  @GET
  public List<ReplicationInformation> getReplicationInformation()
      throws AccumuloException, AccumuloSecurityException {
    final AccumuloClient client = monitor.getContext();

    final TableOperations tops = client.tableOperations();

    final Map<String,String> properties = client.instanceOperations().getSystemConfiguration();
    final Map<String,String> peers = new HashMap<>();
    final String definedPeersPrefix = Property.REPLICATION_PEERS.getKey();
    final ReplicaSystemFactory replicaSystemFactory = new ReplicaSystemFactory();

    // Get the defined peers and what ReplicaSystem impl they're using
    for (Entry<String,String> property : properties.entrySet()) {
      String key = property.getKey();
      // Filter out cruft that we don't want
      if (key.startsWith(definedPeersPrefix)
          && !key.startsWith(Property.REPLICATION_PEER_USER.getKey())
          && !key.startsWith(Property.REPLICATION_PEER_PASSWORD.getKey())) {
        String peerName = property.getKey().substring(definedPeersPrefix.length());
        ReplicaSystem replica;
        try {
          replica = replicaSystemFactory.get(monitor.getContext(), property.getValue());
        } catch (Exception e) {
          log.warn("Could not instantiate ReplicaSystem for {} with configuration {}",
              property.getKey(), property.getValue(), e);
          continue;
        }

        peers.put(peerName, replica.getClass().getName());
      }
    }

    final String targetPrefix = Property.TABLE_REPLICATION_TARGET.getKey();

    // The total set of configured targets
    Set<ReplicationTarget> allConfiguredTargets = new HashSet<>();

    // Number of files per target we have to replicate
    Map<ReplicationTarget,Long> targetCounts = new HashMap<>();

    Map<String,TableId> tableNameToId = Tables.getNameToIdMap(monitor.getContext());
    Map<TableId,String> tableIdToName = invert(tableNameToId);

    for (String table : tops.list()) {
      if (MetadataTable.NAME.equals(table) || RootTable.NAME.equals(table)) {
        continue;
      }
      TableId localId = tableNameToId.get(table);
      if (localId == null) {
        log.trace("Could not determine ID for {}", table);
        continue;
      }

      Map<String,String> propertiesForTable;
      try {
        propertiesForTable = tops.getConfiguration(table);
      } catch (TableNotFoundException e) {
        log.warn("Could not fetch properties for {}", table, e);
        continue;
      }
      propertiesForTable.forEach((key, value) -> {
        if (key.startsWith(targetPrefix)) {
          String peerName = key.substring(targetPrefix.length());
          String remoteIdentifier = value;
          ReplicationTarget target = new ReplicationTarget(peerName, remoteIdentifier, localId);

          allConfiguredTargets.add(target);
        }
      });
    }

    // Read over the queued work
    BatchScanner bs;
    try {
      bs = client.createBatchScanner(ReplicationTable.NAME, Authorizations.EMPTY, 4);
    } catch (TableOfflineException | TableNotFoundException e) {
      log.error("Could not read replication table", e);
      return Collections.emptyList();
    }

    bs.setRanges(Collections.singleton(new Range()));
    WorkSection.limit(bs);
    try {
      Text buffer = new Text();
      for (Entry<Key,Value> entry : bs) {
        Key k = entry.getKey();
        k.getColumnQualifier(buffer);
        ReplicationTarget target = ReplicationTarget.from(buffer);

        // TODO ACCUMULO-2835 once explicit lengths are tracked, we can give size-based estimates
        // instead of just file-based
        Long count = targetCounts.get(target);
        if (count == null) {
          targetCounts.put(target, 1L);
        } else {
          targetCounts.put(target, count + 1);
        }
      }
    } finally {
      bs.close();
    }

    List<ReplicationInformation> replicationInformation = new ArrayList<>();
    for (ReplicationTarget configuredTarget : allConfiguredTargets) {
      String tableName = tableIdToName.get(configuredTarget.getSourceTableId());
      if (tableName == null) {
        log.trace("Could not determine table name from id {}", configuredTarget.getSourceTableId());
        continue;
      }

      String replicaSystemClass = peers.get(configuredTarget.getPeerName());
      if (replicaSystemClass == null) {
        log.trace("Could not determine configured ReplicaSystem for {}",
            configuredTarget.getPeerName());
        continue;
      }

      Long numFiles = targetCounts.get(configuredTarget);

      replicationInformation.add(new ReplicationInformation(tableName,
          configuredTarget.getPeerName(), configuredTarget.getRemoteIdentifier(),
          replicaSystemClass, (numFiles == null) ? 0 : numFiles));
    }

    return replicationInformation;
  }

  protected Map<TableId,String> invert(Map<String,TableId> map) {
    Map<TableId,String> newMap = new HashMap<>(map.size());
    for (Entry<String,TableId> entry : map.entrySet()) {
      newMap.put(entry.getValue(), entry.getKey());
    }
    return newMap;
  }
}
