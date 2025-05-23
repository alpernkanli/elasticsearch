/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchShardsGroup;
import org.elasticsearch.action.search.SearchShardsRequest;
import org.elasticsearch.action.search.SearchShardsResponse;
import org.elasticsearch.action.search.TransportSearchShardsAction;
import org.elasticsearch.action.support.ChannelActionListener;
import org.elasticsearch.action.support.ContextPreservingActionListener;
import org.elasticsearch.action.support.RefCountingListener;
import org.elasticsearch.action.support.RefCountingRunnable;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.Driver;
import org.elasticsearch.compute.operator.DriverProfile;
import org.elasticsearch.compute.operator.DriverTaskRunner;
import org.elasticsearch.compute.operator.ResponseHeadersCollector;
import org.elasticsearch.compute.operator.exchange.ExchangeResponse;
import org.elasticsearch.compute.operator.exchange.ExchangeService;
import org.elasticsearch.compute.operator.exchange.ExchangeSinkHandler;
import org.elasticsearch.compute.operator.exchange.ExchangeSourceHandler;
import org.elasticsearch.compute.operator.exchange.RemoteSink;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardNotFoundException;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.internal.ShardSearchRequest;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskCancelledException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteClusterAware;
import org.elasticsearch.transport.RemoteClusterService;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.esql.action.EsqlQueryAction;
import org.elasticsearch.xpack.esql.enrich.EnrichLookupService;
import org.elasticsearch.xpack.esql.plan.physical.ExchangeSinkExec;
import org.elasticsearch.xpack.esql.plan.physical.ExchangeSourceExec;
import org.elasticsearch.xpack.esql.plan.physical.OutputExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.esql.planner.EsPhysicalOperationProviders;
import org.elasticsearch.xpack.esql.planner.LocalExecutionPlanner;
import org.elasticsearch.xpack.esql.planner.PlannerUtils;
import org.elasticsearch.xpack.esql.session.EsqlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.esql.plugin.EsqlPlugin.ESQL_THREAD_POOL_NAME;
import static org.elasticsearch.xpack.esql.plugin.EsqlPlugin.ESQL_WORKER_THREAD_POOL_NAME;

/**
 * Computes the result of a {@link PhysicalPlan}.
 */
public class ComputeService {
    public record Result(List<Page> pages, List<DriverProfile> profiles) {}

    private static final Logger LOGGER = LogManager.getLogger(ComputeService.class);
    private final SearchService searchService;
    private final BigArrays bigArrays;
    private final BlockFactory blockFactory;

    private final TransportService transportService;
    private final Executor esqlExecutor;
    private final DriverTaskRunner driverRunner;
    private final ExchangeService exchangeService;
    private final EnrichLookupService enrichLookupService;
    private final ClusterService clusterService;

    public ComputeService(
        SearchService searchService,
        TransportService transportService,
        ExchangeService exchangeService,
        EnrichLookupService enrichLookupService,
        ClusterService clusterService,
        ThreadPool threadPool,
        BigArrays bigArrays,
        BlockFactory blockFactory
    ) {
        this.searchService = searchService;
        this.transportService = transportService;
        this.bigArrays = bigArrays.withCircuitBreaking();
        this.blockFactory = blockFactory;
        this.esqlExecutor = threadPool.executor(ESQL_THREAD_POOL_NAME);
        transportService.registerRequestHandler(DATA_ACTION_NAME, this.esqlExecutor, DataNodeRequest::new, new DataNodeRequestHandler());
        transportService.registerRequestHandler(
            CLUSTER_ACTION_NAME,
            this.esqlExecutor,
            ClusterComputeRequest::new,
            new ClusterRequestHandler()
        );
        this.driverRunner = new DriverTaskRunner(transportService, this.esqlExecutor);
        this.exchangeService = exchangeService;
        this.enrichLookupService = enrichLookupService;
        this.clusterService = clusterService;
    }

    public void execute(
        String sessionId,
        CancellableTask rootTask,
        PhysicalPlan physicalPlan,
        EsqlConfiguration configuration,
        ActionListener<Result> listener
    ) {
        Tuple<PhysicalPlan, PhysicalPlan> coordinatorAndDataNodePlan = PlannerUtils.breakPlanBetweenCoordinatorAndDataNode(
            physicalPlan,
            configuration
        );
        final List<Page> collectedPages = Collections.synchronizedList(new ArrayList<>());
        listener = listener.delegateResponse((l, e) -> {
            collectedPages.forEach(p -> Releasables.closeExpectNoException(p::releaseBlocks));
            l.onFailure(e);
        });
        PhysicalPlan coordinatorPlan = new OutputExec(coordinatorAndDataNodePlan.v1(), collectedPages::add);
        PhysicalPlan dataNodePlan = coordinatorAndDataNodePlan.v2();
        if (dataNodePlan != null && dataNodePlan instanceof ExchangeSinkExec == false) {
            listener.onFailure(new IllegalStateException("expect data node plan starts with an ExchangeSink; got " + dataNodePlan));
            return;
        }
        Map<String, OriginalIndices> clusterToConcreteIndices = transportService.getRemoteClusterService()
            .groupIndices(SearchRequest.DEFAULT_INDICES_OPTIONS, PlannerUtils.planConcreteIndices(physicalPlan).toArray(String[]::new));
        QueryPragmas queryPragmas = configuration.pragmas();
        if (dataNodePlan == null || clusterToConcreteIndices.values().stream().allMatch(v -> v.indices().length == 0)) {
            var computeContext = new ComputeContext(sessionId, List.of(), configuration, null, null);
            runCompute(
                rootTask,
                computeContext,
                coordinatorPlan,
                listener.map(driverProfiles -> new Result(collectedPages, driverProfiles))
            );
            return;
        }
        Map<String, OriginalIndices> clusterToOriginalIndices = transportService.getRemoteClusterService()
            .groupIndices(SearchRequest.DEFAULT_INDICES_OPTIONS, PlannerUtils.planOriginalIndices(physicalPlan));
        var localOriginalIndices = clusterToOriginalIndices.remove(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY);
        var localConcreteIndices = clusterToConcreteIndices.remove(RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY);
        if (clusterToOriginalIndices.isEmpty() == false && PlannerUtils.hasEnrich(physicalPlan)) {
            listener.onFailure(new IllegalArgumentException("cross clusters query doesn't support enrich yet"));
            return;
        }
        final var responseHeadersCollector = new ResponseHeadersCollector(transportService.getThreadPool().getThreadContext());
        listener = ActionListener.runBefore(listener, responseHeadersCollector::finish);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<DriverProfile> collectedProfiles = configuration.profile() ? Collections.synchronizedList(new ArrayList<>()) : List.of();
        final var exchangeSource = new ExchangeSourceHandler(
            queryPragmas.exchangeBufferSize(),
            transportService.getThreadPool().executor(ESQL_THREAD_POOL_NAME)
        );
        try (
            Releasable ignored = exchangeSource::decRef;
            RefCountingListener refs = new RefCountingListener(listener.map(unused -> new Result(collectedPages, collectedProfiles)))
        ) {
            // wait until the source handler is completed
            exchangeSource.addCompletionListener(refs.acquire());
            // run compute on the coordinator
            runCompute(
                rootTask,
                new ComputeContext(sessionId, List.of(), configuration, exchangeSource, null),
                coordinatorPlan,
                cancelOnFailure(rootTask, cancelled, refs.acquire()).map(driverProfiles -> {
                    responseHeadersCollector.collect();
                    if (configuration.profile()) {
                        collectedProfiles.addAll(driverProfiles);
                    }
                    return null;
                })
            );
            // starts computes on data nodes on the main cluster
            if (localConcreteIndices != null && localConcreteIndices.indices().length > 0) {
                startComputeOnDataNodes(
                    sessionId,
                    RemoteClusterAware.LOCAL_CLUSTER_GROUP_KEY,
                    rootTask,
                    configuration,
                    dataNodePlan,
                    Set.of(localConcreteIndices.indices()),
                    localOriginalIndices.indices(),
                    exchangeSource,
                    () -> cancelOnFailure(rootTask, cancelled, refs.acquire()).map(response -> {
                        responseHeadersCollector.collect();
                        if (configuration.profile()) {
                            collectedProfiles.addAll(response.getProfiles());
                        }
                        return null;
                    })
                );
            }
            // starts computes on remote cluster
            startComputeOnRemoteClusters(
                sessionId,
                rootTask,
                configuration,
                dataNodePlan,
                exchangeSource,
                getRemoteClusters(clusterToConcreteIndices, clusterToOriginalIndices),
                () -> cancelOnFailure(rootTask, cancelled, refs.acquire()).map(response -> {
                    responseHeadersCollector.collect();
                    if (configuration.profile()) {
                        collectedProfiles.addAll(response.getProfiles());
                    }
                    return null;
                })
            );
        }
    }

    private List<RemoteCluster> getRemoteClusters(
        Map<String, OriginalIndices> clusterToConcreteIndices,
        Map<String, OriginalIndices> clusterToOriginalIndices
    ) {
        List<RemoteCluster> remoteClusters = new ArrayList<>(clusterToConcreteIndices.size());
        RemoteClusterService remoteClusterService = transportService.getRemoteClusterService();
        for (Map.Entry<String, OriginalIndices> e : clusterToConcreteIndices.entrySet()) {
            String clusterAlias = e.getKey();
            OriginalIndices concreteIndices = clusterToConcreteIndices.get(clusterAlias);
            OriginalIndices originalIndices = clusterToOriginalIndices.get(clusterAlias);
            if (originalIndices == null) {
                assert false : "can't find original indices for cluster " + clusterAlias;
                throw new IllegalStateException("can't find original indices for cluster " + clusterAlias);
            }
            if (concreteIndices.indices().length > 0) {
                Transport.Connection connection = remoteClusterService.getConnection(clusterAlias);
                remoteClusters.add(new RemoteCluster(clusterAlias, connection, concreteIndices.indices(), originalIndices.indices()));
            }
        }
        return remoteClusters;
    }

    static final class EmptyRemoteSink implements RemoteSink {
        final SubscribableListener<Void> future = new SubscribableListener<>();

        @Override
        public void fetchPageAsync(boolean allSourcesFinished, ActionListener<ExchangeResponse> listener) {
            future.addListener(listener.map(ignored -> new ExchangeResponse(null, true)));
        }

        void finish() {
            future.onResponse(null);
        }
    }

    private void startComputeOnDataNodes(
        String sessionId,
        String clusterAlias,
        CancellableTask parentTask,
        EsqlConfiguration configuration,
        PhysicalPlan dataNodePlan,
        Set<String> concreteIndices,
        String[] originalIndices,
        ExchangeSourceHandler exchangeSource,
        Supplier<ActionListener<ComputeResponse>> listener
    ) {
        // Do not complete the exchange sources until we have linked all remote sinks
        final EmptyRemoteSink emptyRemoteSink = new EmptyRemoteSink();
        exchangeSource.addRemoteSink(emptyRemoteSink, 1);
        QueryBuilder requestFilter = PlannerUtils.requestFilter(dataNodePlan);
        lookupDataNodes(parentTask, clusterAlias, requestFilter, concreteIndices, originalIndices, ActionListener.wrap(dataNodes -> {
            try (RefCountingRunnable refs = new RefCountingRunnable(emptyRemoteSink::finish)) {
                // For each target node, first open a remote exchange on the remote node, then link the exchange source to
                // the new remote exchange sink, and initialize the computation on the target node via data-node-request.
                for (DataNode node : dataNodes) {
                    var dataNodeListener = ActionListener.releaseAfter(listener.get(), refs.acquire());
                    var queryPragmas = configuration.pragmas();
                    ExchangeService.openExchange(
                        transportService,
                        node.connection,
                        sessionId,
                        queryPragmas.exchangeBufferSize(),
                        esqlExecutor,
                        dataNodeListener.delegateFailureAndWrap((delegate, unused) -> {
                            var remoteSink = exchangeService.newRemoteSink(parentTask, sessionId, transportService, node.connection);
                            exchangeSource.addRemoteSink(remoteSink, queryPragmas.concurrentExchangeClients());
                            transportService.sendChildRequest(
                                node.connection,
                                DATA_ACTION_NAME,
                                new DataNodeRequest(sessionId, configuration, clusterAlias, node.shardIds, node.aliasFilters, dataNodePlan),
                                parentTask,
                                TransportRequestOptions.EMPTY,
                                new ActionListenerResponseHandler<>(delegate, ComputeResponse::new, esqlExecutor)
                            );
                        })
                    );
                }
            }
        }, e -> {
            emptyRemoteSink.finish();
            listener.get().onFailure(e);
        }));
    }

    private void startComputeOnRemoteClusters(
        String sessionId,
        CancellableTask rootTask,
        EsqlConfiguration configuration,
        PhysicalPlan plan,
        ExchangeSourceHandler exchangeSource,
        List<RemoteCluster> clusters,
        Supplier<ActionListener<ComputeResponse>> listener
    ) {
        // Do not complete the exchange sources until we have linked all remote sinks
        final EmptyRemoteSink emptyRemoteSink = new EmptyRemoteSink();
        exchangeSource.addRemoteSink(emptyRemoteSink, 1);
        try (RefCountingRunnable refs = new RefCountingRunnable(emptyRemoteSink::finish)) {
            for (RemoteCluster cluster : clusters) {
                var targetNodeListener = ActionListener.releaseAfter(listener.get(), refs.acquire());
                var queryPragmas = configuration.pragmas();
                ExchangeService.openExchange(
                    transportService,
                    cluster.connection,
                    sessionId,
                    queryPragmas.exchangeBufferSize(),
                    esqlExecutor,
                    targetNodeListener.delegateFailureAndWrap((l, unused) -> {
                        var remoteSink = exchangeService.newRemoteSink(rootTask, sessionId, transportService, cluster.connection);
                        exchangeSource.addRemoteSink(remoteSink, queryPragmas.concurrentExchangeClients());
                        var clusterRequest = new ClusterComputeRequest(
                            cluster.clusterAlias,
                            sessionId,
                            configuration,
                            plan,
                            cluster.concreteIndices,
                            cluster.originalIndices
                        );
                        transportService.sendChildRequest(
                            cluster.connection,
                            CLUSTER_ACTION_NAME,
                            clusterRequest,
                            rootTask,
                            TransportRequestOptions.EMPTY,
                            new ActionListenerResponseHandler<>(l, ComputeResponse::new, esqlExecutor)
                        );
                    })
                );
            }
        }
    }

    private ActionListener<Void> cancelOnFailure(CancellableTask task, AtomicBoolean cancelled, ActionListener<Void> listener) {
        return listener.delegateResponse((l, e) -> {
            l.onFailure(e);
            if (cancelled.compareAndSet(false, true)) {
                LOGGER.debug("cancelling ESQL task {} on failure", task);
                transportService.getTaskManager().cancelTaskAndDescendants(task, "cancelled", false, ActionListener.noop());
            }
        });
    }

    void runCompute(CancellableTask task, ComputeContext context, PhysicalPlan plan, ActionListener<List<DriverProfile>> listener) {
        listener = ActionListener.runAfter(listener, () -> Releasables.close(context.searchContexts));
        final List<Driver> drivers;
        try {
            LocalExecutionPlanner planner = new LocalExecutionPlanner(
                context.sessionId,
                task,
                bigArrays,
                blockFactory,
                clusterService.getSettings(),
                context.configuration,
                context.exchangeSource(),
                context.exchangeSink(),
                enrichLookupService,
                new EsPhysicalOperationProviders(context.searchContexts)
            );

            LOGGER.debug("Received physical plan:\n{}", plan);
            plan = PlannerUtils.localPlan(context.searchContexts, context.configuration, plan);
            LocalExecutionPlanner.LocalExecutionPlan localExecutionPlan = planner.plan(plan);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Local execution plan:\n{}", localExecutionPlan.describe());
            }
            drivers = localExecutionPlan.createDrivers(context.sessionId);
            if (drivers.isEmpty()) {
                throw new IllegalStateException("no drivers created");
            }
            LOGGER.debug("using {} drivers", drivers.size());
        } catch (Exception e) {
            listener.onFailure(e);
            return;
        }
        ActionListener<Void> listenerCollectingStatus = listener.map(ignored -> {
            if (context.configuration.profile()) {
                return drivers.stream().map(d -> new DriverProfile(d.status().completedOperators())).toList();
            }
            return null;
        });
        listenerCollectingStatus = ActionListener.releaseAfter(listenerCollectingStatus, () -> Releasables.close(drivers));
        driverRunner.executeDrivers(
            task,
            drivers,
            transportService.getThreadPool().executor(ESQL_WORKER_THREAD_POOL_NAME),
            listenerCollectingStatus
        );
    }

    private void acquireSearchContexts(
        String clusterAlias,
        List<ShardId> shardIds,
        EsqlConfiguration configuration,
        Map<Index, AliasFilter> aliasFilters,
        ActionListener<List<SearchContext>> listener
    ) {
        try {
            List<IndexShard> targetShards = new ArrayList<>();
            for (ShardId shardId : shardIds) {
                var indexShard = searchService.getIndicesService().indexServiceSafe(shardId.getIndex()).getShard(shardId.id());
                targetShards.add(indexShard);
            }
            if (targetShards.isEmpty()) {
                listener.onResponse(List.of());
                return;
            }
            CountDown countDown = new CountDown(targetShards.size());
            for (IndexShard targetShard : targetShards) {
                targetShard.ensureShardSearchActive(ignored -> {
                    if (countDown.countDown()) {
                        ActionListener.completeWith(listener, () -> {
                            final List<SearchContext> searchContexts = new ArrayList<>(targetShards.size());
                            boolean success = false;
                            try {
                                for (IndexShard shard : targetShards) {
                                    var aliasFilter = aliasFilters.getOrDefault(shard.shardId().getIndex(), AliasFilter.EMPTY);
                                    var shardRequest = new ShardSearchRequest(
                                        shard.shardId(),
                                        configuration.absoluteStartedTimeInMillis(),
                                        aliasFilter,
                                        clusterAlias
                                    );
                                    SearchContext context = searchService.createSearchContext(shardRequest, SearchService.NO_TIMEOUT);
                                    searchContexts.add(context);
                                }
                                for (SearchContext searchContext : searchContexts) {
                                    searchContext.preProcess();
                                }
                                success = true;
                                return searchContexts;
                            } finally {
                                if (success == false) {
                                    IOUtils.close(searchContexts);
                                }
                            }
                        });
                    }
                });
            }
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    record DataNode(Transport.Connection connection, List<ShardId> shardIds, Map<Index, AliasFilter> aliasFilters) {

    }

    record RemoteCluster(String clusterAlias, Transport.Connection connection, String[] concreteIndices, String[] originalIndices) {

    }

    /**
     * Performs can_match and find the target nodes for the given target indices and filter.
     * <p>
     * Ideally, the search_shards API should be called before the field-caps API; however, this can lead
     * to a situation where the column structure (i.e., matched data types) differs depending on the query.
     */
    void lookupDataNodes(
        Task parentTask,
        String clusterAlias,
        QueryBuilder filter,
        Set<String> concreteIndices,
        String[] originalIndices,
        ActionListener<List<DataNode>> listener
    ) {
        ThreadContext threadContext = transportService.getThreadPool().getThreadContext();
        ActionListener<SearchShardsResponse> preservingContextListener = ContextPreservingActionListener.wrapPreservingContext(
            listener.map(resp -> {
                Map<String, DiscoveryNode> nodes = new HashMap<>();
                for (DiscoveryNode node : resp.getNodes()) {
                    nodes.put(node.getId(), node);
                }
                Map<String, List<ShardId>> nodeToShards = new HashMap<>();
                Map<String, Map<Index, AliasFilter>> nodeToAliasFilters = new HashMap<>();
                for (SearchShardsGroup group : resp.getGroups()) {
                    var shardId = group.shardId();
                    if (group.skipped()) {
                        continue;
                    }
                    if (group.allocatedNodes().isEmpty()) {
                        throw new ShardNotFoundException(group.shardId(), "no shard copies found {}", group.shardId());
                    }
                    if (concreteIndices.contains(shardId.getIndexName()) == false) {
                        continue;
                    }
                    String targetNode = group.allocatedNodes().get(0);
                    nodeToShards.computeIfAbsent(targetNode, k -> new ArrayList<>()).add(shardId);
                    AliasFilter aliasFilter = resp.getAliasFilters().get(shardId.getIndex().getUUID());
                    if (aliasFilter != null) {
                        nodeToAliasFilters.computeIfAbsent(targetNode, k -> new HashMap<>()).put(shardId.getIndex(), aliasFilter);
                    }
                }
                List<DataNode> dataNodes = new ArrayList<>(nodeToShards.size());
                for (Map.Entry<String, List<ShardId>> e : nodeToShards.entrySet()) {
                    DiscoveryNode node = nodes.get(e.getKey());
                    Map<Index, AliasFilter> aliasFilters = nodeToAliasFilters.getOrDefault(e.getKey(), Map.of());
                    dataNodes.add(new DataNode(transportService.getConnection(node), e.getValue(), aliasFilters));
                }
                return dataNodes;
            }),
            threadContext
        );
        try (ThreadContext.StoredContext ignored = threadContext.newStoredContextPreservingResponseHeaders()) {
            threadContext.markAsSystemContext();
            SearchShardsRequest searchShardsRequest = new SearchShardsRequest(
                originalIndices,
                SearchRequest.DEFAULT_INDICES_OPTIONS,
                filter,
                null,
                null,
                false,
                clusterAlias
            );
            transportService.sendChildRequest(
                transportService.getLocalNode(),
                TransportSearchShardsAction.TYPE.name(),
                searchShardsRequest,
                parentTask,
                TransportRequestOptions.EMPTY,
                new ActionListenerResponseHandler<>(preservingContextListener, SearchShardsResponse::new, esqlExecutor)
            );
        }
    }

    // TODO: Use an internal action here
    public static final String DATA_ACTION_NAME = EsqlQueryAction.NAME + "/data";

    private class DataNodeRequestHandler implements TransportRequestHandler<DataNodeRequest> {
        @Override
        public void messageReceived(DataNodeRequest request, TransportChannel channel, Task task) {
            final var parentTask = (CancellableTask) task;
            final var sessionId = request.sessionId();
            final var exchangeSink = exchangeService.getSinkHandler(sessionId);
            parentTask.addListener(() -> exchangeService.finishSinkHandler(sessionId, new TaskCancelledException("task cancelled")));
            final ActionListener<ComputeResponse> listener = new ChannelActionListener<>(channel);
            final EsqlConfiguration configuration = request.configuration();
            acquireSearchContexts(
                request.clusterAlias(),
                request.shardIds(),
                configuration,
                request.aliasFilters(),
                ActionListener.wrap(searchContexts -> {
                    var computeContext = new ComputeContext(sessionId, searchContexts, configuration, null, exchangeSink);
                    runCompute(parentTask, computeContext, request.plan(), ActionListener.wrap(driverProfiles -> {
                        // don't return until all pages are fetched
                        exchangeSink.addCompletionListener(
                            ContextPreservingActionListener.wrapPreservingContext(
                                ActionListener.releaseAfter(
                                    listener.map(nullValue -> new ComputeResponse(driverProfiles)),
                                    () -> exchangeService.finishSinkHandler(sessionId, null)
                                ),
                                transportService.getThreadPool().getThreadContext()
                            )
                        );
                    }, e -> {
                        exchangeService.finishSinkHandler(sessionId, e);
                        listener.onFailure(e);
                    }));
                }, e -> {
                    exchangeService.finishSinkHandler(sessionId, e);
                    listener.onFailure(e);
                })
            );
        }
    }

    public static final String CLUSTER_ACTION_NAME = EsqlQueryAction.NAME + "/cluster";

    private class ClusterRequestHandler implements TransportRequestHandler<ClusterComputeRequest> {
        @Override
        public void messageReceived(ClusterComputeRequest request, TransportChannel channel, Task task) {
            ChannelActionListener<ComputeResponse> listener = new ChannelActionListener<>(channel);
            if (request.plan() instanceof ExchangeSinkExec == false) {
                listener.onFailure(new IllegalStateException("expected exchange sink for a remote compute; got " + request.plan()));
                return;
            }
            runComputeOnRemoteCluster(
                request.clusterAlias(),
                request.sessionId(),
                (CancellableTask) task,
                request.configuration(),
                (ExchangeSinkExec) request.plan(),
                Set.of(request.indices()),
                request.originalIndices(),
                listener
            );
        }
    }

    /**
     * Performs a compute on a remote cluster. The output pages are placed in an exchange sink specified by
     * {@code globalSessionId}. The coordinator on the main cluster will poll pages from there.
     * <p>
     * Currently, the coordinator on the remote cluster simply collects pages from data nodes in the remote cluster
     * and places them in the exchange sink. We can achieve this by using a single exchange buffer to minimize overhead.
     * However, here we use two exchange buffers so that we can run an actual plan on this coordinator to perform partial
     * reduce operations, such as limit, topN, and partial-to-partial aggregation in the future.
     */
    void runComputeOnRemoteCluster(
        String clusterAlias,
        String globalSessionId,
        CancellableTask parentTask,
        EsqlConfiguration configuration,
        ExchangeSinkExec plan,
        Set<String> concreteIndices,
        String[] originalIndices,
        ActionListener<ComputeResponse> listener
    ) {
        final var exchangeSink = exchangeService.getSinkHandler(globalSessionId);
        parentTask.addListener(() -> exchangeService.finishSinkHandler(globalSessionId, new TaskCancelledException("request cancelled")));
        ThreadPool threadPool = transportService.getThreadPool();
        final var responseHeadersCollector = new ResponseHeadersCollector(threadPool.getThreadContext());
        listener = ActionListener.runBefore(listener, responseHeadersCollector::finish);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final List<DriverProfile> collectedProfiles = configuration.profile() ? Collections.synchronizedList(new ArrayList<>()) : List.of();
        final String localSessionId = clusterAlias + ":" + globalSessionId;
        var exchangeSource = new ExchangeSourceHandler(
            configuration.pragmas().exchangeBufferSize(),
            transportService.getThreadPool().executor(ESQL_THREAD_POOL_NAME)
        );
        try (
            Releasable ignored = exchangeSource::decRef;
            RefCountingListener refs = new RefCountingListener(listener.map(unused -> new ComputeResponse(collectedProfiles)))
        ) {
            exchangeSource.addCompletionListener(refs.acquire());
            exchangeSink.addCompletionListener(refs.acquire());
            PhysicalPlan coordinatorPlan = new ExchangeSinkExec(
                plan.source(),
                plan.output(),
                plan.isIntermediateAgg(),
                new ExchangeSourceExec(plan.source(), plan.output(), plan.isIntermediateAgg())
            );
            runCompute(
                parentTask,
                new ComputeContext(localSessionId, List.of(), configuration, exchangeSource, exchangeSink),
                coordinatorPlan,
                cancelOnFailure(parentTask, cancelled, refs.acquire()).map(driverProfiles -> {
                    responseHeadersCollector.collect();
                    if (configuration.profile()) {
                        collectedProfiles.addAll(driverProfiles);
                    }
                    return null;
                })
            );
            startComputeOnDataNodes(
                localSessionId,
                clusterAlias,
                parentTask,
                configuration,
                plan,
                concreteIndices,
                originalIndices,
                exchangeSource,
                () -> cancelOnFailure(parentTask, cancelled, refs.acquire()).map(r -> {
                    responseHeadersCollector.collect();
                    if (configuration.profile()) {
                        collectedProfiles.addAll(r.getProfiles());
                    }
                    return null;
                })
            );
        }
    }

    record ComputeContext(
        String sessionId,
        List<SearchContext> searchContexts,
        EsqlConfiguration configuration,
        ExchangeSourceHandler exchangeSource,
        ExchangeSinkHandler exchangeSink
    ) {}
}
