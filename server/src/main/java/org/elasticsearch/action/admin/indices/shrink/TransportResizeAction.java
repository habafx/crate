/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.admin.indices.shrink;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

import org.apache.lucene.index.IndexWriter;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.stats.IndexShardStats;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.shard.DocsStats;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import io.crate.metadata.NodeContext;

/**
 * Main class to initiate resizing (shrink / split) an index into a new index
 */
public class TransportResizeAction extends TransportMasterNodeAction<ResizeRequest, ResizeResponse> {
    private final MetadataCreateIndexService createIndexService;
    private final Client client;
    private final NodeContext nodeContext;

    @Inject
    public TransportResizeAction(TransportService transportService,
                                 ClusterService clusterService,
                                 ThreadPool threadPool,
                                 MetadataCreateIndexService createIndexService,
                                 IndexNameExpressionResolver indexNameExpressionResolver,
                                 NodeContext nodeContext,
                                 Client client) {
        this(
            ResizeAction.NAME,
            transportService,
            clusterService,
            threadPool,
            createIndexService,
            indexNameExpressionResolver,
            nodeContext,
            client
        );
    }

    protected TransportResizeAction(String actionName,
                                    TransportService transportService,
                                    ClusterService clusterService,
                                    ThreadPool threadPool,
                                    MetadataCreateIndexService createIndexService,
                                    IndexNameExpressionResolver indexNameExpressionResolver,
                                    NodeContext nodeContext,
                                    Client client) {
        super(actionName, transportService, clusterService, threadPool, ResizeRequest::new, indexNameExpressionResolver);
        this.createIndexService = createIndexService;
        this.client = client;
        this.nodeContext = nodeContext;
    }


    @Override
    protected String executor() {
        // we go async right away
        return ThreadPool.Names.SAME;
    }

    @Override
    protected ResizeResponse read(StreamInput in) throws IOException {
        return new ResizeResponse(in);
    }

    @Override
    protected ClusterBlockException checkBlock(ResizeRequest request, ClusterState state) {
        return state.blocks().indexBlockedException(ClusterBlockLevel.METADATA_WRITE, request.getTargetIndexRequest().index());
    }

    @Override
    protected void masterOperation(final ResizeRequest resizeRequest,
                                   final ClusterState state,
                                   final ActionListener<ResizeResponse> listener) {

        // there is no need to fetch docs stats for split but we keep it simple and do it anyway for simplicity of the code
        final String sourceIndex = resizeRequest.getSourceIndex();
        final String targetIndex = resizeRequest.getTargetIndexRequest().index();
        client.admin().indices()
            .prepareStats(sourceIndex)
            .clear()
            .setDocs(true)
            .execute(ActionListener.delegateFailure(
                listener,
                (delegate, indicesStatsResponse) -> {
                    CreateIndexClusterStateUpdateRequest updateRequest = prepareCreateIndexRequest(
                        resizeRequest,
                        state,
                        i -> {
                            IndexShardStats shard = indicesStatsResponse.getIndex(sourceIndex).getIndexShards().get(i);
                            return shard == null ? null : shard.getPrimary().getDocs();
                        },
                        sourceIndex,
                        targetIndex
                    );
                    createIndexService.createIndex(
                        nodeContext,
                        updateRequest,
                        ActionListener.map(delegate,
                            response -> new ResizeResponse(
                                response.isAcknowledged(),
                                response.isShardsAcknowledged(),
                                updateRequest.index()
                            )
                        )
                    );
                }
            ));
    }

    // static for unittesting this method
    static CreateIndexClusterStateUpdateRequest prepareCreateIndexRequest(final ResizeRequest resizeRequest,
                                                                          final ClusterState state,
                                                                          final IntFunction<DocsStats> perShardDocStats,
                                                                          String sourceIndexName,
                                                                          String targetIndexName) {
        final CreateIndexRequest targetIndex = resizeRequest.getTargetIndexRequest();
        final IndexMetadata metadata = state.metadata().index(sourceIndexName);
        if (metadata == null) {
            throw new IndexNotFoundException(sourceIndexName);
        }
        final Settings targetIndexSettings = Settings.builder().put(targetIndex.settings())
            .normalizePrefix(IndexMetadata.INDEX_SETTING_PREFIX).build();
        final int numShards;
        if (IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.exists(targetIndexSettings)) {
            numShards = IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.get(targetIndexSettings);
        } else {
            assert resizeRequest.getResizeType() == ResizeType.SHRINK : "split must specify the number of shards explicitly";
            numShards = 1;
        }

        for (int i = 0; i < numShards; i++) {
            if (resizeRequest.getResizeType() == ResizeType.SHRINK) {
                Set<ShardId> shardIds = IndexMetadata.selectShrinkShards(i, metadata, numShards);
                long count = 0;
                for (ShardId id : shardIds) {
                    DocsStats docsStats = perShardDocStats.apply(id.id());
                    if (docsStats != null) {
                        count += docsStats.getCount();
                    }
                    if (count > IndexWriter.MAX_DOCS) {
                        throw new IllegalStateException("Can't merge index with more than [" + IndexWriter.MAX_DOCS
                            + "] docs - too many documents in shards " + shardIds);
                    }
                }
            } else {
                Objects.requireNonNull(IndexMetadata.selectSplitShard(i, metadata, numShards));
                // we just execute this to ensure we get the right exceptions if the number of shards is wrong or less then etc.
            }
        }

        if (IndexMetadata.INDEX_ROUTING_PARTITION_SIZE_SETTING.exists(targetIndexSettings)) {
            throw new IllegalArgumentException("cannot provide a routing partition size value when resizing an index");
        }
        if (IndexMetadata.INDEX_NUMBER_OF_ROUTING_SHARDS_SETTING.exists(targetIndexSettings)) {
            throw new IllegalArgumentException("cannot provide index.number_of_routing_shards on resize");
        }
        if (IndexSettings.INDEX_SOFT_DELETES_SETTING.exists(metadata.getSettings()) &&
            IndexSettings.INDEX_SOFT_DELETES_SETTING.get(metadata.getSettings()) &&
            IndexSettings.INDEX_SOFT_DELETES_SETTING.exists(targetIndexSettings) &&
            IndexSettings.INDEX_SOFT_DELETES_SETTING.get(targetIndexSettings) == false) {
            throw new IllegalArgumentException("Can't disable [index.soft_deletes.enabled] setting on resize");
        }
        String cause = resizeRequest.getResizeType().name().toLowerCase(Locale.ROOT) + "_index";
        targetIndex.cause(cause);
        Settings.Builder settingsBuilder = Settings.builder().put(targetIndexSettings);
        settingsBuilder.put("index.number_of_shards", numShards);
        targetIndex.settings(settingsBuilder);

        return new CreateIndexClusterStateUpdateRequest(cause, targetIndex.index(), targetIndexName)
                // mappings are updated on the node when creating in the shards, this prevents race-conditions since all mapping must be
                // applied once we took the snapshot and if somebody messes things up and switches the index read/write and adds docs we
                // miss the mappings for everything is corrupted and hard to debug
                .ackTimeout(targetIndex.timeout())
                .masterNodeTimeout(targetIndex.masterNodeTimeout())
                .settings(targetIndex.settings())
                .aliases(targetIndex.aliases())
                .waitForActiveShards(targetIndex.waitForActiveShards())
                .recoverFrom(metadata.getIndex())
                .resizeType(resizeRequest.getResizeType())
                .copySettings(resizeRequest.getCopySettings() == null ? false : resizeRequest.getCopySettings());
    }
}
