/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata.doc;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.crate.analyze.AlterPartitionedTableParameterInfo;
import io.crate.analyze.TableParameterInfo;
import io.crate.analyze.WhereClause;
import io.crate.exceptions.UnavailableShardsException;
import io.crate.metadata.*;
import io.crate.metadata.table.AbstractDynamicTableInfo;
import io.crate.metadata.table.ColumnPolicy;
import io.crate.planner.RowGranularity;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndexMissingException;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.*;


public class DocTableInfo extends AbstractDynamicTableInfo {

    private static final TimeValue ROUTING_FETCH_TIMEOUT = new TimeValue(5, TimeUnit.SECONDS);

    private final List<ReferenceInfo> columns;
    private final List<ReferenceInfo> partitionedByColumns;
    private final Map<ColumnIdent, IndexReferenceInfo> indexColumns;
    private final ImmutableMap<ColumnIdent, ReferenceInfo> references;
    private final TableIdent ident;
    private final List<ColumnIdent> primaryKeys;
    private final ColumnIdent clusteredBy;
    private final String[] concreteIndices;
    private final List<ColumnIdent> partitionedBy;
    private final int numberOfShards;
    private final BytesRef numberOfReplicas;
    private ExecutorService executorService;
    private final ClusterService clusterService;
    private final TableParameterInfo tableParameterInfo;
    private static final ESLogger logger = Loggers.getLogger(DocTableInfo.class);

    private final String[] indices;
    private final List<PartitionName> partitions;

    private final boolean isAlias;
    private final boolean hasAutoGeneratedPrimaryKey;
    private final boolean isPartitioned;

    private final ColumnPolicy columnPolicy;

    public DocTableInfo(DocSchemaInfo schemaInfo,
                        TableIdent ident,
                        List<ReferenceInfo> columns,
                        List<ReferenceInfo> partitionedByColumns,
                        ImmutableMap<ColumnIdent, IndexReferenceInfo> indexColumns,
                        ImmutableMap<ColumnIdent, ReferenceInfo> references,
                        List<ColumnIdent> primaryKeys,
                        ColumnIdent clusteredBy,
                        boolean isAlias,
                        boolean hasAutoGeneratedPrimaryKey,
                        String[] concreteIndices,
                        ClusterService clusterService,
                        int numberOfShards,
                        BytesRef numberOfReplicas,
                        List<ColumnIdent> partitionedBy,
                        List<PartitionName> partitions,
                        ColumnPolicy columnPolicy,
                        ExecutorService executorService) {
        super(schemaInfo);
        this.clusterService = clusterService;
        this.columns = columns;
        this.partitionedByColumns = partitionedByColumns;
        this.indexColumns = indexColumns;
        this.references = references;
        this.ident = ident;
        this.primaryKeys = primaryKeys;
        this.clusteredBy = clusteredBy;
        this.concreteIndices = concreteIndices;
        this.numberOfShards = numberOfShards;
        this.numberOfReplicas = numberOfReplicas;
        this.executorService = executorService;
        indices = new String[]{ident.esName()};
        this.isAlias = isAlias;
        this.hasAutoGeneratedPrimaryKey = hasAutoGeneratedPrimaryKey;
        isPartitioned = !partitionedByColumns.isEmpty();
        this.partitionedBy = partitionedBy;
        this.partitions = partitions;
        this.columnPolicy = columnPolicy;
        if (isPartitioned) {
            tableParameterInfo = new AlterPartitionedTableParameterInfo();
        } else {
            tableParameterInfo = new TableParameterInfo();
        }
    }

    @Override
    @Nullable
    public ReferenceInfo getReferenceInfo(ColumnIdent columnIdent) {
        return references.get(columnIdent);
    }

    @Override
    public Collection<ReferenceInfo> columns() {
        return columns;
    }

    @Override
    public RowGranularity rowGranularity() {
        return RowGranularity.DOC;
    }

    @Override
    public TableIdent ident() {
        return ident;
    }

    private void processShardRouting(Map<String, Map<String, List<Integer>>> locations, ShardRouting shardRouting) {
        String node = shardRouting.currentNodeId();
        Map<String, List<Integer>> nodeMap = locations.get(node);
        if (nodeMap == null) {
            nodeMap = new TreeMap<>();
            locations.put(shardRouting.currentNodeId(), nodeMap);
        }

        List<Integer> shards = nodeMap.get(shardRouting.getIndex());
        if (shards == null) {
            shards = new ArrayList<>();
            nodeMap.put(shardRouting.getIndex(), shards);
        }
        shards.add(shardRouting.id());
    }

    private GroupShardsIterator getShardIterators(WhereClause whereClause,
                                                  @Nullable String preference,
                                                  ClusterState clusterState) throws IndexMissingException {
        String[] routingIndices = concreteIndices;
        if (whereClause.partitions().size() > 0) {
            routingIndices = whereClause.partitions().toArray(new String[whereClause.partitions().size()]);
        }

        Map<String, Set<String>> routingMap = null;
        if (whereClause.clusteredBy().isPresent()) {
            routingMap = clusterState.metaData().resolveSearchRouting(
                    whereClause.routingValues(), routingIndices);
        }
        return clusterService.operationRouting().searchShards(
                clusterState,
                indices,
                routingIndices,
                routingMap,
                preference
        );
    }

    public Routing getRouting(ClusterState state, WhereClause whereClause, String preference, final List<ShardId> missingShards) {
        final Map<String, Map<String, List<Integer>>> locations = new TreeMap<>();
        GroupShardsIterator shardIterators;
        try {
            shardIterators = getShardIterators(whereClause, preference, state);
        } catch (IndexMissingException e) {
            return new Routing();
        }

        fillLocationsFromShardIterators(locations, shardIterators, missingShards);

        if (missingShards.isEmpty()) {
            return new Routing(locations);
        } else {
            return null;
        }
    }

    @Override
    public Routing getRouting(final WhereClause whereClause, @Nullable final String preference) {
        Routing routing = getRouting(clusterService.state(), whereClause, preference, new ArrayList<ShardId>(0));
        if (routing != null) return routing;

        ClusterStateObserver observer = new ClusterStateObserver(clusterService, ROUTING_FETCH_TIMEOUT, logger);
        final SettableFuture<Routing> routingSettableFuture = SettableFuture.create();
        observer.waitForNextChange(
                new FetchRoutingListener(routingSettableFuture, whereClause, preference),
                new ClusterStateObserver.ChangePredicate() {

                    @Override
                    public boolean apply(ClusterState previousState, ClusterState.ClusterStateStatus previousStatus, ClusterState newState, ClusterState.ClusterStateStatus newStatus) {
                        return validate(newState);
                    }

                    @Override
                    public boolean apply(ClusterChangedEvent changedEvent) {
                        return validate(changedEvent.state());
                    }

                    private boolean validate(ClusterState state) {
                        final Map<String, Map<String, List<Integer>>> locations = new TreeMap<>();

                        GroupShardsIterator shardIterators;
                        try {
                            shardIterators = getShardIterators(whereClause, preference, state);
                        } catch (IndexMissingException e) {
                            return true;
                        }

                        final List<ShardId> missingShards = new ArrayList<>(0);
                        fillLocationsFromShardIterators(locations, shardIterators, missingShards);

                        return missingShards.isEmpty();
                    }

                });

        try {
            return routingSettableFuture.get();
        } catch (ExecutionException e) {
            throw Throwables.propagate(e.getCause());
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private void fillLocationsFromShardIterators(Map<String, Map<String, List<Integer>>> locations,
                                                 GroupShardsIterator shardIterators,
                                                 List<ShardId> missingShards) {
        ShardRouting shardRouting;
        for (ShardIterator shardIterator : shardIterators) {
            shardRouting = shardIterator.nextOrNull();
            if (shardRouting != null) {
                if (shardRouting.active()) {
                    processShardRouting(locations, shardRouting);
                } else {
                    missingShards.add(shardIterator.shardId());
                }
            } else {
                if (isPartitioned) {
                    // if the table is partitioned maybe a index/shard just got newly created ...
                    missingShards.add(shardIterator.shardId());
                } else {
                    throw new UnavailableShardsException(shardIterator.shardId());
                }
            }
        }
    }

    public List<ColumnIdent> primaryKey() {
        return primaryKeys;
    }

    @Override
    public int numberOfShards() {
        return numberOfShards;
    }

    @Override
    public BytesRef numberOfReplicas() {
        return numberOfReplicas;
    }

    @Override
    public boolean hasAutoGeneratedPrimaryKey() {
        return hasAutoGeneratedPrimaryKey;
    }

    @Override
    public ColumnIdent clusteredBy() {
        return clusteredBy;
    }

    @Override
    public boolean isAlias() {
        return isAlias;
    }

    @Override
    public String[] concreteIndices() {
        return concreteIndices;
    }

    /**
     * columns this table is partitioned by.
     *
     * guaranteed to be in the same order as defined in CREATE TABLE statement
     * @return always a list, never null
     */
    public List<ReferenceInfo> partitionedByColumns() {
        return partitionedByColumns;
    }

    /**
     * column names of columns this table is partitioned by (in dotted syntax).
     *
     * guaranteed to be in the same order as defined in CREATE TABLE statement
     * @return always a list, never null
     */
    @Override
    public List<ColumnIdent> partitionedBy() {
        return partitionedBy;
    }

    @Override
    public List<PartitionName> partitions() {
        return partitions;
    }

    @Override
    public boolean isPartitioned() {
        return isPartitioned;
    }

    @Override
    public IndexReferenceInfo indexColumn(ColumnIdent ident) {
        return indexColumns.get(ident);
    }

    @Override
    public Iterator<ReferenceInfo> iterator() {
        return references.values().iterator();
    }

    @Override
    public ColumnPolicy columnPolicy() {
        return columnPolicy;
    }

    @Override
    public TableParameterInfo tableParameterInfo () {
        return tableParameterInfo;
    }

    private class FetchRoutingListener implements ClusterStateObserver.Listener {

        private final SettableFuture<Routing> routingFuture;
        private final WhereClause whereClause;
        private final String preference;
        Future<?> innerTaskFuture;

        public FetchRoutingListener(SettableFuture<Routing> routingFuture, WhereClause whereClause, String preference) {
            this.routingFuture = routingFuture;
            this.whereClause = whereClause;
            this.preference = preference;
        }

        @Override
        public void onNewClusterState(final ClusterState state) {
            try {
                innerTaskFuture = executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        final List<ShardId> missingShards = new ArrayList<>(0);
                        Routing routing = getRouting(state, whereClause, preference, missingShards);
                        if (routing == null) {
                            routingFuture.setException(new UnavailableShardsException(missingShards.get(0)));
                        } else {
                            routingFuture.set(routing);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                routingFuture.setException(e);
            }
        }

        @Override
        public void onClusterServiceClose() {
            if (innerTaskFuture != null) {
                innerTaskFuture.cancel(true);
            }
            routingFuture.setException(new IllegalStateException("ClusterService closed"));
        }

        @Override
        public void onTimeout(TimeValue timeout) {
            if (innerTaskFuture != null) {
                innerTaskFuture.cancel(true);
            }
            routingFuture.setException(new IllegalStateException("Fetching table info routing timed out."));
        }
    }
}
