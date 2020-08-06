/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.newrelic.agent.service.analytics;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.newrelic.agent.Agent;
import com.newrelic.agent.Harvestable;
import com.newrelic.agent.MetricNames;
import com.newrelic.agent.TransactionData;
import com.newrelic.agent.TransactionListener;
import com.newrelic.agent.attributes.AttributesUtils;
import com.newrelic.agent.config.AgentConfig;
import com.newrelic.agent.config.AgentConfigListener;
import com.newrelic.agent.config.TransactionEventsConfig;
import com.newrelic.agent.database.DatastoreMetrics;
import com.newrelic.agent.model.CountedDuration;
import com.newrelic.agent.model.PathHashes;
import com.newrelic.agent.model.SyntheticsIds;
import com.newrelic.agent.service.AbstractService;
import com.newrelic.agent.service.EventService;
import com.newrelic.agent.service.ServiceFactory;
import com.newrelic.agent.stats.AbstractStats;
import com.newrelic.agent.stats.CountStats;
import com.newrelic.agent.stats.StatsBase;
import com.newrelic.agent.stats.StatsEngine;
import com.newrelic.agent.stats.StatsWork;
import com.newrelic.agent.stats.TransactionStats;
import com.newrelic.agent.tracing.DistributedTracePayloadImpl;
import com.newrelic.agent.transport.HttpError;
import com.newrelic.agent.util.TimeConversion;

import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The {@link TransactionEventsService} collects per-transaction analytic events and transmits them to the collectors.
 * This class implements parts of both the <b>Recording Analytics Events for Transactions<b> and <b>Agent Support for
 * Synthetics<b> specifications. Both specifications can be found in Confluence.
 * <p>
 * Ideally, all analytic events are stored until harvest and then transmitted. If the number of events exceeds a
 * configurable limit, events are replaced using a "reservoir" sampling algorithm.
 * <p>
 * If the transaction was generated by New Relic Synthetics, however, events are stored deterministically up to a fixed
 * limit after which the reservoir storage is used as a fall-back mechanism.
 * <p>
 * This service can be configured using {@code transaction_events} with {@code enabled} or {@code max_samples_stored}.
 */
public class TransactionEventsService extends AbstractService implements EventService, TransactionListener, AgentConfigListener {
    private final TransactionDataToDistributedTraceIntrinsics transactionDataToDistributedTraceIntrinsics;

    // number of events in the reservoir sampling buffer per-app. All apps get the same value. Synthetics are buffered
    // separately per app using a deterministic algorithm.

    private volatile TransactionEventsConfig config;

    // key is app name, value is collection of per-transaction analytic events for next harvest for that app.
    private final ConcurrentHashMap<String, DistributedSamplingPriorityQueue<TransactionEvent>> reservoirForApp = new ConcurrentHashMap<>();
    // key is app name, value is collection of per-synthetic-transaction analytic events for next harvest for that app
    private final ConcurrentHashMap<String, DistributedSamplingPriorityQueue<TransactionEvent>> syntheticsListForApp = new ConcurrentHashMap<>();
    // key is the app name, value is if it is enabled - should be a limited number of names
    private final ConcurrentMap<String, Boolean> isEnabledForApp = new ConcurrentHashMap<>();

    /*
     * We maintain a single (not per-app) queue of unsent synthetic event holders for the synthetic events, each holding
     * at most MAX_SYNTHETIC_EVENTS_PER_APP. We place a fixed limit on the total number of queued (unsent) holders. The
     * intent is to store a bounded (but relatively large) number of unsent holders during an outage, while allocating
     * the storage to the app(s) producing the most events (rather than statically partitioning the storage space
     * between apps, some of which may not even be reporting). Constants are package visibility for unit tests.
     */
    final ArrayDeque<DistributedSamplingPriorityQueue<TransactionEvent>> pendingSyntheticsHeaps = new ArrayDeque<>();
    static final int MAX_UNSENT_SYNTHETICS_HOLDERS = 25; // 5 apps * 5 minutes each, or fewer apps for longer

    // Number of analytic events stored for transactions generated by New Relic Synthetics.
    static final int MAX_SYNTHETIC_EVENTS_PER_APP = 200;

    /**
     * Cache the transaction names. The key and the value is the name. We expire items in the cache 5 minutes after the
     * last access, and set the max size to {@link #maxSamplesStored}.
     *
     * Multiple events for the same transaction name will reference a single instance of the transaction name string
     * using this cache.
     *
     * @see CacheBuilder#maximumSize(long)
     * @see CacheBuilder#expireAfterAccess(long, TimeUnit)
     */
    private volatile LoadingCache<String, String> transactionNameCache;

    private volatile int maxSamplesStored;
    private List<Harvestable> harvestables = new ArrayList<>();

    public TransactionEventsService(TransactionDataToDistributedTraceIntrinsics transactionDataToDistributedTraceIntrinsics) {
        super(TransactionEventsService.class.getSimpleName());
        this.transactionDataToDistributedTraceIntrinsics = transactionDataToDistributedTraceIntrinsics;
        AgentConfig agentConfig = ServiceFactory.getConfigService().getDefaultAgentConfig();
        config = agentConfig.getTransactionEventsConfig();
        maxSamplesStored = config.getMaxSamplesStored();
        isEnabledForApp.put(agentConfig.getApplicationName(), config.isEnabled());
        transactionNameCache = createTransactionNameCache(maxSamplesStored);
        ServiceFactory.getConfigService().addIAgentConfigListener(this);
    }

    public void addHarvestableToService(String appName) {
        Harvestable harvestable = new TransactionEventHarvestableImpl(this, appName);
        ServiceFactory.getHarvestService().addHarvestable(harvestable);
        harvestables.add(harvestable);
    }

    private static LoadingCache<String, String> createTransactionNameCache(int maxSamplesStored) {
        return CacheBuilder.newBuilder()
                .maximumSize(maxSamplesStored)
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .build(new CacheLoader<String, String>() {
                    @Override
                    public String load(String key) throws Exception {
                        return key;
                    }
                });
    }

    @VisibleForTesting
    void configureHarvestables(long reportPeriodInMillis, int maxSamplesStored) {
        for (Harvestable h : harvestables) {
            h.configure(reportPeriodInMillis, maxSamplesStored);
        }
    }

    public void clearReservoir() {
        reservoirForApp.clear();
    }

    @Override
    public final boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * Register appropriate listeners.
     */
    @Override
    protected void doStart() throws Exception {
        if (config.isEnabled()) {
            ServiceFactory.getTransactionService().addTransactionListener(this);
            ServiceFactory.getConfigService().addIAgentConfigListener(this);
        }
    }

    /**
     * Unregister listeners and clear state.
     */
    @Override
    protected void doStop() throws Exception {
        removeHarvestables();
        ServiceFactory.getTransactionService().removeTransactionListener(this);
        ServiceFactory.getConfigService().removeIAgentConfigListener(this);
        reservoirForApp.clear();
    }

    private void removeHarvestables() {
        for (Harvestable harvestable : harvestables) {
            ServiceFactory.getHarvestService().removeHarvestable(harvestable);
        }
    }

    public int getMaxSamplesStored() {
        return maxSamplesStored;
    }

    public void setMaxSamplesStored(int maxSamplesStored) {
        this.maxSamplesStored = maxSamplesStored;
        this.transactionNameCache = createTransactionNameCache(maxSamplesStored);
    }

    public void harvestEvents(final String appName) {
        long startTimeInNanos = System.nanoTime();
        beforeHarvestSynthetics(appName);

        int targetStored = config.getTargetSamplesStored();
        DistributedSamplingPriorityQueue<TransactionEvent> currentReservoir = reservoirForApp.get(appName);
        int decidedLast = AdaptiveSampling.decidedLast(currentReservoir, targetStored);

        // Now the reservoir for per-transaction analytic events from ordinary non-synthetic transactions
        final DistributedSamplingPriorityQueue<TransactionEvent> reservoirToSend = reservoirForApp.put(appName,
                new DistributedSamplingPriorityQueue<TransactionEvent>(appName, "Transaction Event Service", maxSamplesStored, decidedLast, targetStored));

        if (reservoirToSend != null && reservoirToSend.size() > 0) {
            try {
                ServiceFactory.getRPMServiceManager()
                        .getOrCreateRPMService(appName)
                        .sendAnalyticsEvents(maxSamplesStored, reservoirToSend.getNumberOfTries(), Collections.unmodifiableList(reservoirToSend.asList()));
                final long durationInNanos = System.nanoTime() - startTimeInNanos;
                ServiceFactory.getStatsService().doStatsWork(new StatsWork() {
                    @Override
                    public void doWork(StatsEngine statsEngine) {
                        recordSupportabilityMetrics(statsEngine, durationInNanos, reservoirToSend);
                    }

                    @Override
                    public String getAppName() {
                        return appName;
                    }
                });
            } catch (HttpError e) {
                if (!e.discardHarvestData()) {
                    Agent.LOG.log(Level.FINE,
                            "Unable to send events for regular transactions. Data for this harvest will be resampled and the operation will be retried.", e);
                    // Save unsent data by merging it with current data using reservoir algorithm
                    currentReservoir = reservoirForApp.get(appName);
                    currentReservoir.retryAll(reservoirToSend);
                } else {
                    // discard harvest data
                    reservoirToSend.clear();
                    Agent.LOG.log(Level.FINE, "Unable to send events for regular transactions. Data for this harvest will be dropped.", e);
                }
            } catch (Exception e) {
                // discard harvest data
                reservoirToSend.clear();
                Agent.LOG.log(Level.FINE, "Unable to send events for regular transactions. Data for this harvest will be dropped.", e);
            }
        }
    }

    @Override
    public String getEventHarvestIntervalMetric() {
        return MetricNames.SUPPORTABILITY_TRANSACTION_EVENT_SERVICE_EVENT_HARVEST_INTERVAL;
    }

    @Override
    public String getReportPeriodInSecondsMetric() {
        return MetricNames.SUPPORTABILITY_ANALYTIC_EVENT_SERVICE_REPORT_PERIOD_IN_SECONDS;
    }

    @Override
    public String getEventHarvestLimitMetric() {
        return MetricNames.SUPPORTABILITY_ANALYTIC_EVENT_DATA_HARVEST_LIMIT;
    }

    private void recordSupportabilityMetrics(StatsEngine statsEngine, long durationInNanos,
            DistributedSamplingPriorityQueue<TransactionEvent> reservoir) {
        statsEngine.getStats(MetricNames.SUPPORTABILITY_TRANSACTION_EVENT_SERVICE_TRANSACTION_EVENT_SENT)
                .incrementCallCount(reservoir.size());
        statsEngine.getStats(MetricNames.SUPPORTABILITY_TRANSACTION_EVENT_SERVICE_TRANSACTION_EVENT_SEEN)
                .incrementCallCount(reservoir.getNumberOfTries());
        statsEngine.getResponseTimeStats(MetricNames.SUPPORTABILITY_TRANSACTION_EVENT_SERVICE_EVENT_HARVEST_TRANSMIT)
                .recordResponseTime(durationInNanos, TimeUnit.NANOSECONDS);
    }

    private void beforeHarvestSynthetics(String appName) {
        DistributedSamplingPriorityQueue<TransactionEvent> currentReservoir = syntheticsListForApp.get(appName);
        int decidedLast = AdaptiveSampling.decidedLast(currentReservoir, config.getTargetSamplesStored());

        DistributedSamplingPriorityQueue<TransactionEvent> current = syntheticsListForApp.put(appName,
                new DistributedSamplingPriorityQueue<TransactionEvent>(appName, "Synthetics Event Service", MAX_SYNTHETIC_EVENTS_PER_APP, decidedLast,
                        config.getTargetSamplesStored()));
        if (current != null && current.size() > 0) {
            if (pendingSyntheticsHeaps.size() < MAX_UNSENT_SYNTHETICS_HOLDERS) {
                pendingSyntheticsHeaps.add(current);
            } else {
                Agent.LOG.fine("Some synthetic transaction events were discarded.");
            }
        }

        /*
         * If things are working normally, pendingSyntheticsHeaps now contains either 0 or 1 element. If there is one,
         * we simply send it. But if we are catching up after an outage, we send a few of them, and then defer to the
         * next call here and so on. This prevents having all the Agents in the world send e.g. 25 * 200 events when
         * recovering from a long outage. Worse case we'll catch up by 4 buffers per minute, so about 6 minutes.
         */
        final int maxToSend = 5;
        for (int nSent = 0; nSent < maxToSend; ++nSent) {
            DistributedSamplingPriorityQueue<TransactionEvent> toSend = pendingSyntheticsHeaps.poll();
            if (toSend == null) {
                break;
            }
            try {
                ServiceFactory.getRPMServiceManager()
                        .getOrCreateRPMService(appName)
                        .sendAnalyticsEvents(MAX_SYNTHETIC_EVENTS_PER_APP, toSend.getNumberOfTries(), Collections.unmodifiableList(toSend.asList()));
                nSent++;
            } catch (HttpError e) {
                if (!e.discardHarvestData()) {
                    Agent.LOG.log(Level.FINE, "Unable to send events for synthetic transactions. Unsent events will be included in the next harvest.", e);
                    pendingSyntheticsHeaps.add(toSend);
                    break;
                } else {
                    Agent.LOG.log(Level.FINE, "Unable to send events for synthetic transactions. Unsent events will be dropped.", e);
                    break;
                }
            } catch (Exception e) {
                Agent.LOG.log(Level.FINE, "Unable to send events for synthetic transactions. Unsent events will be dropped.", e);
                break;
            }
        }
    }

    private boolean getIsEnabledForApp(AgentConfig config, String currentAppName) {
        Boolean appEnabled = isEnabledForApp.get(currentAppName);
        if (appEnabled == null) {
            appEnabled = config.getTransactionEventsConfig().isEnabled();
            isEnabledForApp.put(currentAppName, appEnabled);
        }
        return appEnabled;
    }

    @Override
    public void dispatcherTransactionFinished(TransactionData transactionData, TransactionStats transactionStats) {
        String appName = transactionData.getApplicationName();

        if (!getIsEnabledForApp(transactionData.getAgentConfig(), appName)) {
            reservoirForApp.remove(appName);
            return;
        }

        boolean persisted = false;

        int target = config.getTargetSamplesStored();
        if (transactionData.isSyntheticTransaction()) {
            DistributedSamplingPriorityQueue<TransactionEvent> currentSyntheticsList = syntheticsListForApp.get(appName);
            while (currentSyntheticsList == null) {
                // I don't think this loop can actually execute more than once, but it's prudent to assume it can.
                syntheticsListForApp.putIfAbsent(appName,
                        new DistributedSamplingPriorityQueue<TransactionEvent>(appName, "Synthetics Event Service", MAX_SYNTHETIC_EVENTS_PER_APP, 0, target));
                currentSyntheticsList = syntheticsListForApp.get(appName);
            }

            persisted = currentSyntheticsList.add(createEvent(transactionData, transactionStats, getMetricName(transactionData)));
            String msg = MessageFormat.format("Added Synthetics transaction event: {0}, persisted: {1}", transactionData, persisted);
            Agent.LOG.finest(msg);
        }

        if (!persisted) { // the event is not from synthetics, or it is but the synthetics buffer is full
            DistributedSamplingPriorityQueue<TransactionEvent> currentReservoir = reservoirForApp.get(appName);
            while (currentReservoir == null) {
                // I don't think this loop can actually execute more than once, but it's prudent to assume it can.
                reservoirForApp.putIfAbsent(appName,
                        new DistributedSamplingPriorityQueue<TransactionEvent>(appName, "Transaction Event Service", maxSamplesStored, 0, target));
                currentReservoir = reservoirForApp.get(appName);
            }
            if (!currentReservoir.isFull() || currentReservoir.getMinPriority() < transactionData.getPriority()) {
                // If the reservoir is not full or it is full and our current transaction
                // is greater than the min in the reservoir we should try to create and add it
                currentReservoir.add(createEvent(transactionData, transactionStats, getMetricName(transactionData)));
            } else {
                currentReservoir.incrementNumberOfTries();
            }
        }
    }

    /**
     * Returns the metric name for the given transaction data using a cache so that only one copy
     * of any single metric name is kept in memory.
     */
    private String getMetricName(TransactionData transactionData) {
        String metricName = transactionData.getBlameOrRootMetricName();
        try {
            metricName = transactionNameCache.get(metricName);
        } catch (ExecutionException e) {
            Agent.LOG.finest("Error fetching cached transaction name: " + e.toString());
        }
        return metricName;
    }

    // public for testing purposes
    public TransactionEvent createEvent(TransactionData transactionData, TransactionStats transactionStats, String metricName) {
        long startTime = transactionData.getWallClockStartTimeMs();
        long durationInNanos = transactionData.getLegacyDuration();

        boolean distributedTracingEnabled = ServiceFactory.getConfigService().getDefaultAgentConfig().getDistributedTracingConfig().isEnabled();
        Integer port = ServiceFactory.getEnvironmentService().getEnvironment().getAgentIdentity().getServerPort();

        String syntheticsResourceId = transactionData.getSyntheticsResourceId();
        String syntheticsMonitorId = transactionData.getSyntheticsMonitorId();
        String syntheticsJobId = transactionData.getSyntheticsJobId();
        SyntheticsIds syntheticsIds = new SyntheticsIds(syntheticsResourceId, syntheticsMonitorId, syntheticsJobId);
        TransactionEventBuilder eventBuilder = new TransactionEventBuilder()
                .setAppName(transactionData.getApplicationName())
                .setTimestamp(startTime)
                .setName(metricName)
                .setDuration((float) durationInNanos / TimeConversion.NANOSECONDS_PER_SECOND)
                .setGuid(transactionData.getGuid())
                .setReferringGuid(transactionData.getReferrerGuid())
                .setPort(port)
                .setTripId(transactionData.getTripId())
                .setApdexPerfZone(transactionData.getApdexPerfZone())
                .setSyntheticsIds(syntheticsIds)
                .setError(transactionData.hasReportableErrorThatIsNotIgnored())
                .setpTotalTime((float) transactionData.getTransactionTime().getTotalSumTimeInNanos() / TimeConversion.NANOSECONDS_PER_SECOND)
                .setTimeoutCause(transactionData.getTransaction().getTimeoutCause())
                .setPriority(transactionData.getPriority());

        if (distributedTracingEnabled) {
            DistributedTracePayloadImpl inboundDistributedTracePayload = transactionData.getInboundDistributedTracePayload();
            eventBuilder = eventBuilder
                    .setDecider(inboundDistributedTracePayload == null || inboundDistributedTracePayload.priority == null);

            Map<String, Object> distributedTraceServiceIntrinsics = transactionDataToDistributedTraceIntrinsics.buildDistributedTracingIntrinsics(transactionData,
                    true);
            eventBuilder = eventBuilder.setDistributedTraceIntrinsics(distributedTraceServiceIntrinsics);

        }

        final boolean attributesEnabled = ServiceFactory.getAttributesService()
                .isAttributesEnabledForTransactionEvents(transactionData.getApplicationName());

        if (attributesEnabled) {
            eventBuilder.putAllUserAttributes(transactionData.getUserAttributes());
        }

        Integer pathHash = null;
        if (transactionData.getTripId() != null) {
            pathHash = transactionData.generatePathHash();
        }
        PathHashes pathHashes = new PathHashes(pathHash, transactionData.getReferringPathHash(), transactionData.getAlternatePathHashes());
        eventBuilder.setPathHashes(pathHashes);

        if (transactionData.getTransactionTime().getTimeToFirstByteInNanos() > 0) {
            float timeToFirstByte = (float) transactionData.getTransactionTime().getTimeToFirstByteInNanos()
                    / TimeConversion.NANOSECONDS_PER_SECOND;
            eventBuilder.setTimeToFirstByte(timeToFirstByte);
        }

        if (transactionData.getTransactionTime().getTimetoLastByteInNanos() > 0) {
            float timeToLastByte = (float) transactionData.getTransactionTime().getTimetoLastByteInNanos()
                    / TimeConversion.NANOSECONDS_PER_SECOND;
            eventBuilder.setTimeToLastByte(timeToLastByte);
        }

        eventBuilder.setQueueDuration(retrieveMetricIfExists(transactionStats, MetricNames.QUEUE_TIME).getTotal());

        float externalDuration = retrieveMetricIfExists(transactionStats, MetricNames.EXTERNAL_ALL).getTotal();
        float externalCallCount = retrieveMetricIfExists(transactionStats, MetricNames.EXTERNAL_ALL).getCallCount();
        eventBuilder.setExternal(new CountedDuration(externalDuration, externalCallCount));

        float databaseDuration = retrieveMetricIfExists(transactionStats, DatastoreMetrics.ALL).getTotal();
        float databaseCallCount = retrieveMetricIfExists(transactionStats, DatastoreMetrics.ALL).getCallCount();
        eventBuilder.setDatabase(new CountedDuration(databaseDuration, databaseCallCount));

        float gcCumulative = retrieveMetricIfExists(transactionStats, MetricNames.GC_CUMULATIVE).getTotal();
        eventBuilder.setGcCumulative(gcCumulative);

        TransactionEvent event = eventBuilder.build();

        if (attributesEnabled) {
            // trans events take user and agent atts - any desired intrinsics should have already been grabbed

            event.agentAttributes = transactionData.getAgentAttributes();
            // request/message parameters are sent up in the same bucket as agent attributes
            event.agentAttributes.putAll(AttributesUtils.appendAttributePrefixes(transactionData.getPrefixedAttributes()));
        }

        return event;
    }

    private static CountStats retrieveMetricIfExists(TransactionStats transactionStats, String metricName) {
        if (!transactionStats.getUnscopedStats().getStatsMap().containsKey(metricName)) {
            return NoCallCountStats.NO_STATS;
        }
        return transactionStats.getUnscopedStats().getOrCreateResponseTimeStats(metricName);
    }

    @Override
    public void configChanged(String appName, AgentConfig agentConfig) {
        // if the config has changed for the app, just remove it and regenerate enabled next transaction
        isEnabledForApp.remove(appName);
        config = agentConfig.getTransactionEventsConfig();
    }

    private static class NoCallCountStats extends AbstractStats {
        static final NoCallCountStats NO_STATS = new NoCallCountStats();

        @Override
        public float getTotal() {
            return TransactionEvent.UNASSIGNED_FLOAT;
        }

        @Override
        public float getTotalExclusiveTime() {
            return TransactionEvent.UNASSIGNED_FLOAT;
        }

        @Override
        public float getMinCallTime() {
            return TransactionEvent.UNASSIGNED_FLOAT;
        }

        @Override
        public float getMaxCallTime() {
            return TransactionEvent.UNASSIGNED_FLOAT;
        }

        @Override
        public double getSumOfSquares() {
            return TransactionEvent.UNASSIGNED_FLOAT;
        }

        @Override
        public boolean hasData() {
            return false;
        }

        @Override
        public void reset() {
        }

        @Override
        public void merge(StatsBase stats) {
        }

        @Override
        public Object clone() throws CloneNotSupportedException {
            return NO_STATS;
        }
    }

    public DistributedSamplingPriorityQueue<TransactionEvent> getDistributedSamplingReservoir(String appName) {
        return reservoirForApp.get(appName);
    }

    public DistributedSamplingPriorityQueue<TransactionEvent> getOrCreateDistributedSamplingReservoir(String appName) {
        DistributedSamplingPriorityQueue<TransactionEvent> reservoir = reservoirForApp.get(appName);
        if (reservoir == null) {
            int target = config.getTargetSamplesStored();
            reservoir = reservoirForApp.putIfAbsent(appName,
                    new DistributedSamplingPriorityQueue<TransactionEvent>(appName, "Transaction Event Service", maxSamplesStored, 0, target));
            if (reservoir == null) {
                reservoir = reservoirForApp.get(appName);
            }
        }
        return reservoir;
    }
}