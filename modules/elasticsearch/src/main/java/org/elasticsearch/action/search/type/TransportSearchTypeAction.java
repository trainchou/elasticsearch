/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.search.type;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.support.BaseAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.Node;
import org.elasticsearch.cluster.node.Nodes;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardsIterator;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.action.SearchServiceListener;
import org.elasticsearch.search.action.SearchServiceTransportAction;
import org.elasticsearch.search.controller.SearchPhaseController;
import org.elasticsearch.search.controller.ShardDoc;
import org.elasticsearch.search.internal.InternalSearchRequest;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.util.settings.Settings;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.action.Actions.*;
import static org.elasticsearch.action.search.type.TransportSearchHelper.*;

/**
 * @author kimchy (Shay Banon)
 */
public abstract class TransportSearchTypeAction extends BaseAction<SearchRequest, SearchResponse> {

    protected final ThreadPool threadPool;

    protected final ClusterService clusterService;

    protected final IndicesService indicesService;

    protected final SearchServiceTransportAction searchService;

    protected final SearchPhaseController searchPhaseController;

    protected final TransportSearchCache searchCache;

    public TransportSearchTypeAction(Settings settings, ThreadPool threadPool, ClusterService clusterService, IndicesService indicesService,
                                     TransportSearchCache searchCache, SearchServiceTransportAction searchService, SearchPhaseController searchPhaseController) {
        super(settings);
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.searchCache = searchCache;
        this.indicesService = indicesService;
        this.searchService = searchService;
        this.searchPhaseController = searchPhaseController;
    }

    protected abstract class BaseAsyncAction<FirstResult> {

        protected final ActionListener<SearchResponse> listener;

        protected final GroupShardsIterator shardsIts;

        protected final SearchRequest request;

        protected final Nodes nodes;

        protected final int expectedSuccessfulOps;

        protected final int expectedTotalOps;

        protected final AtomicInteger successulOps = new AtomicInteger();

        protected final AtomicInteger totalOps = new AtomicInteger();

        protected final Collection<ShardSearchFailure> shardFailures = searchCache.obtainShardFailures();

        protected volatile ShardDoc[] sortedShardList;

        protected BaseAsyncAction(SearchRequest request, ActionListener<SearchResponse> listener) {
            this.request = request;
            this.listener = listener;

            ClusterState clusterState = clusterService.state();

            nodes = clusterState.nodes();

            shardsIts = indicesService.searchShards(clusterState, processIndices(clusterState, request.indices()), request.queryHint());
            expectedSuccessfulOps = shardsIts.size();
            expectedTotalOps = shardsIts.totalSize();
        }

        public void start() {
            // count the local operations, and perform the non local ones
            int localOperations = 0;
            for (final ShardsIterator shardIt : shardsIts) {
                final ShardRouting shard = shardIt.next();
                if (shard.active()) {
                    if (shard.currentNodeId().equals(nodes.localNodeId())) {
                        localOperations++;
                    } else {
                        // do the remote operation here, the localAsync flag is not relevant
                        performFirstPhase(shardIt.reset());
                    }
                } else {
                    // as if we have a "problem", so we iterate to the next one and maintain counts
                    onFirstPhaseResult(shard, shardIt, null);
                }
            }
            // we have local operations, perform them now
            if (localOperations > 0) {
                if (request.operationThreading() == SearchOperationThreading.SINGLE_THREAD) {
                    threadPool.execute(new Runnable() {
                        @Override public void run() {
                            for (final ShardsIterator shardIt : shardsIts) {
                                final ShardRouting shard = shardIt.reset().next();
                                if (shard.active()) {
                                    if (shard.currentNodeId().equals(nodes.localNodeId())) {
                                        performFirstPhase(shardIt.reset());
                                    }
                                }
                            }
                        }
                    });
                } else {
                    boolean localAsync = request.operationThreading() == SearchOperationThreading.THREAD_PER_SHARD;
                    for (final ShardsIterator shardIt : shardsIts) {
                        final ShardRouting shard = shardIt.reset().next();
                        if (shard.active()) {
                            if (shard.currentNodeId().equals(nodes.localNodeId())) {
                                if (localAsync) {
                                    threadPool.execute(new Runnable() {
                                        @Override public void run() {
                                            performFirstPhase(shardIt.reset());
                                        }
                                    });
                                } else {
                                    performFirstPhase(shardIt.reset());
                                }
                            }
                        }
                    }
                }
            }
        }

        private void performFirstPhase(final Iterator<ShardRouting> shardIt) {
            final ShardRouting shard = shardIt.next();
            if (!shard.active()) {
                // as if we have a "problem", so we iterate to the next one and maintain counts
                onFirstPhaseResult(shard, shardIt, null);
            } else {
                Node node = nodes.get(shard.currentNodeId());
                sendExecuteFirstPhase(node, internalSearchRequest(shard, request), new SearchServiceListener<FirstResult>() {
                    @Override public void onResult(FirstResult result) {
                        onFirstPhaseResult(shard, result, shardIt);
                    }

                    @Override public void onFailure(Throwable t) {
                        onFirstPhaseResult(shard, shardIt, t);
                    }
                });
            }
        }

        private void onFirstPhaseResult(ShardRouting shard, FirstResult result, Iterator<ShardRouting> shardIt) {
            processFirstPhaseResult(shard, result);
            // increment all the "future" shards to update the total ops since we some may work and some may not...
            // and when that happens, we break on total ops, so we must maintain them
            while (shardIt.hasNext()) {
                totalOps.incrementAndGet();
                shardIt.next();
            }
            if (successulOps.incrementAndGet() == expectedSuccessfulOps ||
                    totalOps.incrementAndGet() == expectedTotalOps) {
                try {
                    moveToSecondPhase();
                } catch (Exception e) {
                    invokeListener(new ReduceSearchPhaseException(firstPhaseName(), "", e, buildShardFailures()));
                }
            }
        }

        private void onFirstPhaseResult(ShardRouting shard, final Iterator<ShardRouting> shardIt, Throwable t) {
            if (logger.isDebugEnabled()) {
                if (t != null) {
                    logger.debug(shard.shortSummary() + ": Failed to search [" + request + "]", t);
                }
            }
            if (totalOps.incrementAndGet() == expectedTotalOps) {
                // no more shards, add a failure
                shardFailures.add(new ShardSearchFailure(t));
                if (successulOps.get() == 0) {
                    // no successful ops, raise an exception
                    invokeListener(new SearchPhaseExecutionException(firstPhaseName(), "total failure", buildShardFailures()));
                } else {
                    try {
                        moveToSecondPhase();
                    } catch (Exception e) {
                        invokeListener(new ReduceSearchPhaseException(firstPhaseName(), "", e, buildShardFailures()));
                    }
                }
            } else {
                if (shardIt.hasNext()) {
                    performFirstPhase(shardIt);
                } else {
                    // no more shards, add a failure
                    shardFailures.add(new ShardSearchFailure(t));
                }
            }
        }

        /**
         * Builds the shard failures, and releases the cache (meaning this should only be called once!).
         */
        protected ShardSearchFailure[] buildShardFailures() {
            return TransportSearchHelper.buildShardFailures(shardFailures, searchCache);
        }

        protected void invokeListener(final SearchResponse response) {
            if (request.listenerThreaded()) {
                threadPool.execute(new Runnable() {
                    @Override public void run() {
                        listener.onResponse(response);
                    }
                });
            } else {
                listener.onResponse(response);
            }
        }

        protected void invokeListener(final Throwable t) {
            if (request.listenerThreaded()) {
                threadPool.execute(new Runnable() {
                    @Override public void run() {
                        listener.onFailure(t);
                    }
                });
            } else {
                listener.onFailure(t);
            }
        }

        protected abstract void sendExecuteFirstPhase(Node node, InternalSearchRequest request, SearchServiceListener<FirstResult> listener);

        protected abstract void processFirstPhaseResult(ShardRouting shard, FirstResult result);

        protected abstract void moveToSecondPhase();

        protected abstract String firstPhaseName();
    }
}
