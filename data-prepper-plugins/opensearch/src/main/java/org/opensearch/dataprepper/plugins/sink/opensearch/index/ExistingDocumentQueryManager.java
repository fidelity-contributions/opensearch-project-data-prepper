package org.opensearch.dataprepper.plugins.sink.opensearch.index;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.TermsQuery;
import org.opensearch.client.opensearch._types.query_dsl.TermsQueryField;
import org.opensearch.client.opensearch.core.MsearchRequest;
import org.opensearch.client.opensearch.core.MsearchResponse;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.plugins.sink.opensearch.BulkOperationWrapper;
import org.opensearch.dataprepper.plugins.sink.opensearch.index.model.QueryManagerBulkOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ExistingDocumentQueryManager implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ExistingDocumentQueryManager.class);

    private static final Duration QUERY_INTERVAL = Duration.ofSeconds(20);

    static final String EVENTS_DROPPED_AND_RELEASED = "eventsDroppedAndReleasedAfterQuery";

    static final String EVENTS_ADDED_FOR_QUERYING = "eventsAddedForQuerying";

    static final String EVENTS_RETURNED_FOR_INDEXING = "eventsReturnedForIndexingAfterQuery";

    static final String DOCUMENTS_CURRENTLY_BEING_QUERIED = "documentsBeingQueried";

    static final String DUPLICATE_EVENTS_IN_QUERY_MANAGER = "duplicateEventsInQueryManager";

    static final String QUERY_TIME = "queryDuplicatesTime";

    static final String POTENTIAL_DUPLICATES = "potentialDuplicates";

    private final Counter eventsDroppedAndReleasedCounter;

    private final Counter eventsAddedForQuerying;

    private final Counter eventsReturnedForIndexing;

    private final Counter duplicateEventsInQueryManager;

    private final Counter potentialDuplicatesDeleted;

    private final Timer queryTimePerLoop;

    private final AtomicInteger documentsCurrentlyBeingQueried = new AtomicInteger(0);

    private final AtomicInteger documentsCurrentlyBeingQueriedGauge;

    private final IndexConfiguration indexConfiguration;

    private final Map<String, Map<String, QueryManagerBulkOperation>> bulkOperationsWaitingForQuery;

    private Set<BulkOperationWrapper> bulkOperationsReadyToIngest;

    private final PluginMetrics pluginMetrics;

    private final OpenSearchClient openSearchClient;

    private boolean shouldStop = false;

    private Instant lastQueryTime;

    private final Lock lockReadyToIngest;

    private final Lock lockWaitingForQuery;

    private final String queryTerm;

    public ExistingDocumentQueryManager(final IndexConfiguration indexConfiguration,
                                        final PluginMetrics pluginMetrics,
                                        final OpenSearchClient openSearchClient) {
        this.indexConfiguration = indexConfiguration;
        this.queryTerm = indexConfiguration.getQueryTerm();
        this.bulkOperationsWaitingForQuery = new ConcurrentHashMap<>();
        this.bulkOperationsReadyToIngest = ConcurrentHashMap.newKeySet();
        this.pluginMetrics = pluginMetrics;
        this.openSearchClient = openSearchClient;
        this.eventsAddedForQuerying = pluginMetrics.counter(EVENTS_ADDED_FOR_QUERYING);
        this.eventsDroppedAndReleasedCounter = pluginMetrics.counter(EVENTS_DROPPED_AND_RELEASED);
        this.eventsReturnedForIndexing = pluginMetrics.counter(EVENTS_RETURNED_FOR_INDEXING);
        this.documentsCurrentlyBeingQueriedGauge = pluginMetrics.gauge(DOCUMENTS_CURRENTLY_BEING_QUERIED, documentsCurrentlyBeingQueried, AtomicInteger::get);
        this.duplicateEventsInQueryManager = pluginMetrics.counter(DUPLICATE_EVENTS_IN_QUERY_MANAGER);
        this.queryTimePerLoop = pluginMetrics.timer(QUERY_TIME);
        this.potentialDuplicatesDeleted = pluginMetrics.counter(POTENTIAL_DUPLICATES);
        this.lockReadyToIngest = new ReentrantLock();
        this.lockWaitingForQuery = new ReentrantLock();
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted() && !shouldStop) {
            try {
                queryTimePerLoop.record(this::runQueryLoop);
            } catch (final Exception e) {
                LOG.error("Exception in primary loop responsible for querying for existing documents, retrying", e);
            } finally {
                try {
                    Thread.sleep(QUERY_INTERVAL.toMillis());
                } catch (final InterruptedException e) {
                    LOG.error("Interrupted, exiting");
                }
            }
        }
    }

    @VisibleForTesting
    void runQueryLoop() {
        if (!bulkOperationsWaitingForQuery.isEmpty() && documentsCurrentlyBeingQueriedGauge.get() > 0) {

            // Query for existing documents
            final MsearchRequest msearchRequest = buildMultiSearchRequest();
            final MsearchResponse<ObjectNode> msearchResponse = queryForTermValues(msearchRequest);

            // Drop and Release Existing Documents
            dropAndReleaseFoundEvents(msearchResponse);

            // Move non-existing documents past query_duration to bulkOperationsReadyForIndex
            moveBulkRequestsThatHaveReachedQueryDuration();

            lastQueryTime = Instant.now();
        }
    }

    public void stop() {
        shouldStop = true;
    }

    public void addBulkOperation(final BulkOperationWrapper bulkOperationWrapper) {
        lockWaitingForQuery.lock();
        final String termValue = bulkOperationWrapper.getTermValue();
        try {
            final QueryManagerBulkOperation queryManagerBulkOperation = bulkOperationsWaitingForQuery.computeIfAbsent(bulkOperationWrapper.getIndex(),
                    k -> new ConcurrentHashMap<>()).put(termValue, new QueryManagerBulkOperation(bulkOperationWrapper, Instant.now(), termValue));
            // Only increment if this is a new document
            if (queryManagerBulkOperation == null) {
                documentsCurrentlyBeingQueriedGauge.incrementAndGet();
            } else {
                duplicateEventsInQueryManager.increment();
            }
        } finally {
            lockWaitingForQuery.unlock();
        }
        eventsAddedForQuerying.increment();
    }

    public Set<BulkOperationWrapper> getAndClearBulkOperationsReadyToIndex() {
        while (documentsCurrentlyBeingQueried.get() > indexConfiguration.getQueryAsyncDocumentLimit()) {
            try {
                Thread.sleep(QUERY_INTERVAL.toMillis());
            } catch (final InterruptedException e) {
                LOG.warn("Interrupted while waiting for documents currently being queried to be under limit {}", indexConfiguration.getQueryAsyncDocumentLimit());
            }
        }

        lockReadyToIngest.lock();
        try {
            final Set<BulkOperationWrapper> copyOfBulkOperations = bulkOperationsReadyToIngest;
            bulkOperationsReadyToIngest = ConcurrentHashMap.newKeySet();
            eventsReturnedForIndexing.increment(copyOfBulkOperations.size());
            return copyOfBulkOperations;
        } finally {
            lockReadyToIngest.unlock();
        }
    }

    private MsearchRequest buildMultiSearchRequest() {
        return MsearchRequest.of(m -> {
            for (final Map.Entry<String, Map<String, QueryManagerBulkOperation>> entry : bulkOperationsWaitingForQuery.entrySet()) {
                final String index = entry.getKey();
                final List<FieldValue> values = getTermValues(entry.getValue().values());
                final int batchSize = 1000;

                LOG.info("Creating search requests for {} query term values in batches of {}", values.size(), batchSize);
                for (int i = 0; i < values.size(); i += batchSize) {
                    final List<FieldValue> chunk = values.subList(i, Math.min(i + batchSize, values.size()));

                    m.searches(s -> s
                            .header(h -> h.index(index))
                            .body(b -> b
                                    .size(chunk.size() * 2)
                                    .source(source -> source.filter(f -> f.includes(queryTerm)))
                                    .query(Query.of(q -> q
                                            .terms(TermsQuery.of(t -> t
                                                    .field(queryTerm)
                                                    .terms(TermsQueryField.of(tf -> tf.value(chunk)))
                                            ))
                                    ))
                            ));
                }
            }
            return m;
        });
    }

    private MsearchResponse<ObjectNode> queryForTermValues(final MsearchRequest searchRequest) {
        try {
            return openSearchClient.msearch(searchRequest, ObjectNode.class);
        } catch (final Exception e) {
            LOG.error("Exception while querying for indexes {}", searchRequest.index());
            throw new RuntimeException(String.format("Exception while querying for indexes %s: %s",
                    searchRequest.index(), e.getMessage()));
        }
    }

    private List<FieldValue> getTermValues(final Collection<QueryManagerBulkOperation> queryManagerBulkOperations) {
        final List<FieldValue> termValues = new ArrayList<>();
        for (final QueryManagerBulkOperation queryManagerBulkOperation : queryManagerBulkOperations) {
            termValues.add(FieldValue.of(queryManagerBulkOperation.getTermValue()));
        }

        return termValues;
    }

    private void moveBulkRequestsThatHaveReachedQueryDuration() {
        for (final Iterator<Map.Entry<String, Map<String, QueryManagerBulkOperation>>> indexIterator =
             bulkOperationsWaitingForQuery.entrySet().iterator(); indexIterator.hasNext();) {
            final Map.Entry<String, Map<String, QueryManagerBulkOperation>> operationsForEachIndex = indexIterator.next();
            final Iterator<Map.Entry<String, QueryManagerBulkOperation>> bulkOperationIterator = operationsForEachIndex.getValue().entrySet().iterator();

            while (bulkOperationIterator.hasNext()) {
                final Map.Entry<String, QueryManagerBulkOperation> entry = bulkOperationIterator.next();
                final QueryManagerBulkOperation bulkOperation = entry.getValue();
                if (lastQueryTime != null && bulkOperation.getStartTime().plus(indexConfiguration.getQueryDuration()).isBefore(lastQueryTime)) {
                    lockReadyToIngest.lock();
                    try {
                        LOG.debug("Moving bulk operation for index {} and term value {} to be ingested after querying and finding no existing document",
                                bulkOperation.getBulkOperationWrapper().getIndex(),
                                bulkOperation.getTermValue());
                        bulkOperationsReadyToIngest.add(bulkOperation.getBulkOperationWrapper());
                        bulkOperationIterator.remove();
                        documentsCurrentlyBeingQueriedGauge.decrementAndGet();
                    } finally {
                        lockReadyToIngest.unlock();
                    }
                }
            }
        }
    }

    private void dropAndReleaseFoundEvents(final MsearchResponse<ObjectNode> msearchResponse) {
        msearchResponse.responses().forEach(response -> {
            if (response.isFailure()) {
                LOG.error("Search response failed, potential for duplicate documents: {}", response.failure().error().toString());
            } else {
                response.result().hits().hits().forEach(hit -> {
                    final String indexForHit = hit.index();
                    final ObjectNode sourceForHit = hit.source();
                    final String queryTermValue = sourceForHit.findValue(queryTerm).textValue();

                    lockWaitingForQuery.lock();
                    try {
                        final Map<String, QueryManagerBulkOperation> bulkOperationsForIndex = bulkOperationsWaitingForQuery.get(indexForHit);
                        final QueryManagerBulkOperation bulkOperationToRelease = bulkOperationsForIndex.get(queryTermValue);
                        if (bulkOperationToRelease == null) {
                            // Means two documents with the same query term value were found
                            LOG.warn("Bulk operation for term value {} with id {} is null, potentially a duplicate document", queryTermValue, hit.id());
                            potentialDuplicatesDeleted.increment();
                        } else {
                            LOG.debug("Found document with query term {}, dropping and releasing Event handle", queryTermValue);
                            bulkOperationToRelease.getBulkOperationWrapper().releaseEventHandle(true);
                            eventsDroppedAndReleasedCounter.increment();
                            documentsCurrentlyBeingQueriedGauge.decrementAndGet();
                            bulkOperationsForIndex.remove(queryTermValue);
                        }
                    } finally {
                        lockWaitingForQuery.unlock();
                    }
                });
            }
        });
    }
}
