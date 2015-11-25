package org.unizin.cmp.harvester.job;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Observer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.http.Header;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.unizin.cmp.oai.harvester.HarvestParams;
import org.unizin.cmp.oai.harvester.Harvester;
import org.unizin.cmp.oai.harvester.response.OAIResponseHandler;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper.FailedBatch;

/**
 * A combination of a single consumer and one or more producer threads.
 * <p>
 * The producers create {@link HarvestedOAIRecord} instances and place them on a
 * {@link BlockingQueue}. The consumer reads objects from this queue and writes
 * them in batches to DynamoDB.
 * </p>
 * <p>
 * Instances are safe for use in multiple threads.
 * </p>
 */
public final class HarvestJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            HarvestJob.class);
    public static final String DIGEST_ALGORITHM = "MD5";
    public static final Collection<? extends Header> DEFAULT_HEADERS =
            Collections.unmodifiableCollection(Arrays.asList(
                    new BasicHeader("from", "dev@unizin.org")));


    private static void validateBatchSize(final int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "batchSize must be positive.");
        }
    }


    public static final class Builder {
        /**
         * Default batch size is the maximum the DynamoDB mapper will try to
         * send in one API request.
         */
        private static final int DEFAULT_BATCH_SIZE = 25;
        private static final Timeout DEFAULT_TIMEOUT = new Timeout(100,
                TimeUnit.MILLISECONDS);
        private static final int DEFAULT_QUEUE_CAPACITY = 10 * 1000;

        private final DynamoDBMapper mapper;

        private int batchSize = DEFAULT_BATCH_SIZE;
        private Timeout offerTimeout;
        private Timeout pollTimeout;
        private HttpClient httpClient;
        private BlockingQueue<HarvestedOAIRecord> harvestedRecordQueue;
        private ExecutorService executorService;
        private List<HarvestParams> harvestParams;
        private List<Observer> harvestObservers;


        public Builder(final DynamoDBMapper mapper) {
            Objects.requireNonNull(mapper, "mapper");
            this.mapper = mapper;
        }

        /**
         * Set the {@code HttpClient} to use for this job.
         * <p>
         * It is <em>strongly</em> recommended that this client have reasonable
         * timeouts set. Note that the default configuration (created by e.g.,
         * {@link HttpClients#createDefault()}) has no timeouts.
         * </p>
         * <p>
         * The default HTTP client created by this builder if this method is not
         * called is the same as that provided by {@link Harvester.Builder}.
         * </p>
         *
         * @see Harvester.Builder#withHttpClient(HttpClient)
         */
        public Builder withHttpClient(final HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder withRecordQueue(
                final BlockingQueue<HarvestedOAIRecord> queue) {
            this.harvestedRecordQueue = queue;
            return this;
        }

        public Builder withExecutorService(
                final ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        public Builder withOfferTimeout(final Timeout timeout) {
            this.offerTimeout = timeout;
            return this;
        }

        public Builder withPollTimeout(final Timeout timeout) {
            this.pollTimeout = timeout;
            return this;
        }

        public Builder withBatchSize(final int batchSize) {
            validateBatchSize(batchSize);
            this.batchSize = batchSize;
            return this;
        }

        public Builder withHarvestObservers(final Observer...observers) {
            harvestObservers = Arrays.asList(observers);
            return this;
        }

        public Builder withHarvestParams(final HarvestParams...params) {
            harvestParams = Arrays.asList(params);
            return this;
        }

        public HarvestJob build() throws NoSuchAlgorithmException {
            if (httpClient == null) {
                httpClient = Harvester.defaultHttpClient()
                        .setDefaultHeaders(DEFAULT_HEADERS)
                        .build();
            }
            if (harvestedRecordQueue == null) {
                harvestedRecordQueue = new ArrayBlockingQueue<>(
                        DEFAULT_QUEUE_CAPACITY);
            }
            if (executorService == null) {
                executorService = Executors.newCachedThreadPool();
            }
            if (offerTimeout == null) {
                offerTimeout = DEFAULT_TIMEOUT;
            }
            if (pollTimeout == null) {
                pollTimeout = DEFAULT_TIMEOUT;
            }
            if (harvestParams == null) {
                harvestParams = Collections.emptyList();
            }
            if (harvestObservers == null) {
                harvestObservers = Collections.emptyList();
            }
            return new HarvestJob(httpClient, mapper, harvestedRecordQueue,
                    executorService, offerTimeout, pollTimeout, batchSize,
                    harvestParams, harvestObservers);
        }
    }


    private final HttpClient httpClient;
    private final DynamoDBMapper mapper;
    private final BlockingQueue<HarvestedOAIRecord> harvestedRecordQueue;
    private final List<Runnable> tasks = new ArrayList<>();
    private final ExecutorService executorService;
    private final Timeout offerTimeout;
    private final Timeout pollTimeout;
    private final int batchSize;
    private final RunningHarvesters runningHarvesters =
            new RunningHarvesters();
    private volatile boolean running;


    /**
     * Create a new instance.
     *
     * @param httpClient
     *            the HTTP client to use for harvesting. It is <em>strongly</em>
     *            recommended that this client have reasonable timeouts set.
     *            Note that the default configuration (created by e.g.,
     *            {@link HttpClients#createDefault()}) has no timeouts.
     * @param mapper
     *            the DynamoDB mapper to use to write records received from the
     *            harvest.
     * @param harvestedRecordQueue
     *            a blocking queue used to transfer records from producer
     *            threads to the consumer thread. It should have a reasonable
     *            size limit to limit memory use.
     * @param executorService
     *            the executor service that will run each harvest in a separate
     *            producer thread.
     * @param offerTimeout
     *            the maximum time producers should wait before giving up when
     *            putting a record onto the queue.
     * @param pollTimeout
     *            the maximum time the consumer should wait before giving up
     *            when reading a record from the queue.
     * @param batchSize
     *            the number of records to write at a time to DynamoDB. Note
     *            that the DynamoDB mapper may split batches up into smaller
     *            chunks for its own purposes, so this number does not
     *            necessarily reflect the number of records updated per DynamoDB
     *            API request.
     * @param harvestParams
     *            list of harvest parameters. A new producer thread will be
     *            created and started for each of these when {@link #start()} is
     *            invoked.
     * @param harvestObservers
     *            list of observers. Each observer will observe each producing
     *            harvester.
     *
     * @throws NoSuchAlgorithmException
     *             in the extraordinary event that the JVM in which this is
     *             executed does not support the <tt>MD5</tt> digest algorithm.
     */
    public HarvestJob(final HttpClient httpClient,
            final DynamoDBMapper mapper,
            final BlockingQueue<HarvestedOAIRecord> harvestedRecordQueue,
            final ExecutorService executorService,
            final Timeout offerTimeout,
            final Timeout pollTimeout,
            final int batchSize,
            final List<HarvestParams> harvestParams,
            final List<Observer> harvestObservers) throws NoSuchAlgorithmException {
        Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(harvestedRecordQueue, "harvestedRecordQueue");
        Objects.requireNonNull(executorService, "executorService");
        Objects.requireNonNull(offerTimeout, "offerTimeout");
        Objects.requireNonNull(pollTimeout, "pollTimeout");
        Objects.requireNonNull(harvestParams, "harvestParams");
        Objects.requireNonNull(harvestObservers, "harvestObservers");
        validateBatchSize(batchSize);
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.harvestedRecordQueue = harvestedRecordQueue;
        this.executorService = executorService;
        this.offerTimeout = offerTimeout;
        this.pollTimeout = pollTimeout;
        this.batchSize = batchSize;

        for (final HarvestParams hp: harvestParams) {
            final Runnable r = createHarvestRunnable(hp, harvestObservers);
            tasks.add(r);
        }
    }

    /**
     * Create a new {@code MessageDigest} instance using the MD5 algorithm.
     *
     * @return a new MD5 message digest instance.
     * @throws NoSuchAlgorithmException
     *             if this JVM doesn't support the MD5 algorithm.
     */
    public static MessageDigest digest()
            throws NoSuchAlgorithmException {
        return MessageDigest.getInstance(DIGEST_ALGORITHM);
    }

    private Runnable createHarvestRunnable(final HarvestParams params,
            final Iterable<Observer> observers) throws NoSuchAlgorithmException {
        final Harvester harvester = new Harvester.Builder()
                .withHttpClient(httpClient)
                .build();
        observers.forEach(harvester::addObserver);
        final OAIResponseHandler handler = new JobOAIResponseHandler(
                params.getBaseURI(), harvestedRecordQueue, offerTimeout);
        final Runnable harvest = () -> {
            MDC.put("baseURI", params.getBaseURI().toString());
            MDC.put("parameters", params.getParameters().toString());
            try {
                harvester.start(params, handler);
            } catch (final Exception e) {
                LOGGER.error("Error in harvester thread.", e);
            }
        };
        return runningHarvesters.wrappedRunnable(harvester, harvest);
    }

    public void start() {
        if (running || tasks.isEmpty()) {
            return;
        }
        running = true;
        tasks.forEach(executorService::submit);
        /*
         * No reason to hold onto these. Let them get collected when they're
         * done rather than when this thread is done.
         */
        tasks.clear();
        runLoop();
    }

    private void runLoop() {
        final List<HarvestedOAIRecord> batch = new ArrayList<>(batchSize);
        while (!shouldStop()) {
            try {
                final HarvestedOAIRecord record = tryPoll();
                if (record == null) {
                    continue;
                }
                batch.add(record);
                if (batch.size() % batchSize == 0) {
                    LOGGER.info("Writing {} records to database.",
                            batch.size());
                    writeBatch(batch);
                    batch.clear();
                }
            } catch (final InterruptedException e) {
                LOGGER.warn("Consumer interrupted. Stopping.", e);
                Thread.interrupted();
            }
        }
        stop();
        if (!batch.isEmpty()) {
            // Write any leftovers from the last batch.
            LOGGER.info("Writing final batch of {} records to database.",
                    batch.size());
            writeBatch(batch);
        }
    }

    private boolean shouldStop() {
        return !running || runningHarvesters.isEmpty() ||
                Thread.currentThread().isInterrupted();
    }

    private HarvestedOAIRecord tryPoll() throws InterruptedException {
        return harvestedRecordQueue.poll(pollTimeout.getTime(),
                pollTimeout.getUnit());
    }

    /**
     * Stop this job.
     * <p>
     * All running harvests are cancelled, and the consumer thread is notified
     * that it should stop.
     * </p>
     * <p>
     * Note that running harvests and the consumer might not stop immediately.
     * Registered harvest observers will be notified when each harvest stops,
     * and job observers will be notified when the consumer thread stops.
     * </p>
     */
    public void stop() {
        LOGGER.info("Shutting down.");
        runningHarvesters.cancelAll();
        running = false;
    }

    /**
     * Write the given records to DynamoDB in a batch operation.
     * <p>
     * Failures and exceptions are logged, but they do not stop the harvest.
     * </p>
     *
     * @param batch
     *            the records to write.
     */
    private void writeBatch(final List<HarvestedOAIRecord> batch) {
        try {
            // Add the current timestamp to each record before writing.
            final Date batchWritten = new Date();
            batch.forEach((r) -> r.setHarvestedTimestamp(batchWritten));

            final List<FailedBatch> failed = mapper.batchSave(batch);
            if (!failed.isEmpty() && LOGGER.isErrorEnabled()) {
                final StringBuilder sb = new StringBuilder("Batch failed: "
                        + batch + "\t[");
                failed.forEach((fb) -> {
                    sb.append(fb.getClass().getName())
                    .append("[unprocessedItems=")
                    .append(fb.getUnprocessedItems())
                    .append(", exception=")
                    .append(fb.getException())
                    .append("]");
                });
                sb.append("]");
                LOGGER.error(sb.toString());
            }
        } catch (final AmazonClientException e) {
            /*
             * Looking at the code, I _think_ this only happens when the mapper
             * is interrupted while sleeping when backing off, but I can't be
             * sure. It's also not clear which records have been written and
             * which haven't, making accurate statistics impossible.
             */
            if (LOGGER.isErrorEnabled()) {
                final String msg = "Error writing batch: " + batch;
                LOGGER.error(msg, e);
            }
        }
    }
}